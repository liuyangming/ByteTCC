/**
 * Copyright 2014-2018 yangming.liu<bytefox@126.com>.
 *
 * This copyrighted material is made available to anyone wishing to use, modify,
 * copy, or redistribute it subject to the terms and conditions of the GNU
 * Lesser General Public License, as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this distribution; if not, see <http://www.gnu.org/licenses/>.
 */
package org.bytesoft.bytetcc.supports.internal;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import javax.transaction.Status;
import javax.transaction.SystemException;
import javax.transaction.xa.XAException;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.api.BackgroundCallback;
import org.apache.curator.framework.api.CuratorEvent;
import org.apache.curator.framework.api.CuratorEventType;
import org.apache.curator.framework.api.CuratorWatcher;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException.NodeExistsException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher.Event.EventType;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bytesoft.bytetcc.CompensableManagerImpl;
import org.bytesoft.bytetcc.CompensableTransactionImpl;
import org.bytesoft.bytetcc.supports.CompensableRolledbackMarker;
import org.bytesoft.common.utils.ByteUtils;
import org.bytesoft.common.utils.CommonUtils;
import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.archive.TransactionArchive;
import org.bytesoft.compensable.aware.CompensableBeanFactoryAware;
import org.bytesoft.compensable.aware.CompensableEndpointAware;
import org.bytesoft.compensable.logging.CompensableLogger;
import org.bytesoft.transaction.Transaction;
import org.bytesoft.transaction.TransactionException;
import org.bytesoft.transaction.TransactionRecovery;
import org.bytesoft.transaction.TransactionRepository;
import org.bytesoft.transaction.cmd.CommandDispatcher;
import org.bytesoft.transaction.xa.TransactionXid;
import org.bytesoft.transaction.xa.XidFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.result.UpdateResult;

public class MongoCompensableRepository implements TransactionRepository, CompensableRolledbackMarker, CompensableEndpointAware,
		CompensableBeanFactoryAware, CuratorWatcher, BackgroundCallback, SmartInitializingSingleton {
	static Logger logger = LoggerFactory.getLogger(MongoCompensableRepository.class);
	static final String CONSTANTS_ROOT_PATH = "/org/bytesoft/bytetcc";
	static final String CONSTANTS_TB_TRANSACTIONS = "compensables";
	static final String CONSTANTS_FD_GLOBAL = "gxid";
	static final String CONSTANTS_FD_BRANCH = "bxid";

	@javax.annotation.Resource
	private CuratorFramework curatorFramework;
	@javax.annotation.Resource
	private MongoClient mongoClient;
	private String endpoint;
	@javax.inject.Inject
	private CompensableInstVersionManager versionManager;
	@javax.inject.Inject
	private CompensableBeanFactory beanFactory;
	@javax.inject.Inject
	private CommandDispatcher commandDispatcher;

	private long rollbackEntryExpireTime = 1000L * 60 * 5;

	public void afterSingletonsInstantiated() {
		try {
			this.afterPropertiesSet();
		} catch (Exception error) {
			throw new RuntimeException(error);
		}
	}

	public void afterPropertiesSet() throws Exception {
		this.curatorFramework.blockUntilConnected();
		this.initializeSubsystemRollbackDirectory();
		this.listenRollbackTransactionAndRegisterWatcher();
	}

	private void initializeSubsystemRollbackDirectory() throws Exception {
		String parent = String.format("%s/%s/rollback", CONSTANTS_ROOT_PATH, CommonUtils.getApplication(this.endpoint));
		try {
			this.curatorFramework.create() //
					.creatingParentContainersIfNeeded().withMode(CreateMode.PERSISTENT).forPath(parent);
		} catch (NodeExistsException nex) {
			logger.debug("Path exists(path= {})!", parent); // ignore
		}
	}

	private void listenRollbackTransactionAndRegisterWatcher() throws Exception {
		String parent = String.format("%s/%s/rollback", CONSTANTS_ROOT_PATH, CommonUtils.getApplication(this.endpoint));
		this.curatorFramework.getChildren().usingWatcher(this).inBackground(this).forPath(parent);
	}

	public void processResult(CuratorFramework client, CuratorEvent event) throws Exception {
		XidFactory xidFactory = this.beanFactory.getCompensableXidFactory();

		String system = CommonUtils.getApplication(this.endpoint);
		String prefix = String.format("%s/%s/rollback/", CONSTANTS_ROOT_PATH, system);
		String parent = String.format("%s/%s/rollback", CONSTANTS_ROOT_PATH, system);
		String target = event.getPath();
		if (CuratorEventType.CHILDREN.equals(event.getType())) {
			if (StringUtils.equalsIgnoreCase(target, parent) == false || event.getStat() == null) {
				return;
			}

			List<String> children = event.getChildren();
			for (int i = 0; children != null && i < children.size(); i++) {
				String global = children.get(i);
				String path = String.format("%s/%s", parent, global);
				this.curatorFramework.getData().inBackground(this).forPath(path);
			}
		} else if (CuratorEventType.GET_DATA.equals(event.getType())) {
			if (target.startsWith(prefix) == false || event.getStat() == null || event.getData() == null) {
				return;
			}

			byte[] instanceByteArray = event.getData();
			String instanceId = instanceByteArray == null ? StringUtils.EMPTY : new String(instanceByteArray);
			if (StringUtils.equalsIgnoreCase(this.endpoint, instanceId)) {
				return;
			}

			int startIdx = prefix.length();
			int endIndex = target.indexOf("/", startIdx);
			String global = endIndex == -1 ? target.substring(startIdx) : target.substring(startIdx, endIndex);
			byte[] globalByteArray = ByteUtils.stringToByteArray(global);
			final TransactionXid transactionXid = xidFactory.createGlobalXid(globalByteArray);

			CompensableManagerImpl compensableManager = (CompensableManagerImpl) this.beanFactory.getCompensableManager();
			CompensableTransactionImpl transaction = //
					(CompensableTransactionImpl) compensableManager.getTransaction(transactionXid);
			if (transaction != null) {
				transaction.markBusinessStageRollbackOnly(transactionXid);
			} // end-if (transaction != null)

			long createdAt = event.getStat().getCtime();
			long interval = System.currentTimeMillis() - createdAt;

			if (interval < 0) {
				logger.warn("The system time between servers is inconsistent.");
			} // end-if (interval < 0)

			if (interval >= this.rollbackEntryExpireTime) {
				try {
					this.commandDispatcher.dispatch(new Runnable() {
						public void run() {
							remvBusinessStageRollbackFlag(transactionXid);
						}
					});
				} catch (SecurityException error) {
					// Only the master node can perform the recovery operation!
				} catch (RuntimeException error) {
					logger.error("Error occurred while removing transaction rolled back status from zk!", error);
				} catch (Exception error) {
					logger.error("Error occurred while removing transaction rolled back status from zk!", error);
				}
			} else {
				try {
					this.commandDispatcher.dispatch(new Runnable() {
						public void run() {
							markTransactionRollback(transactionXid);
						}
					});
				} catch (SecurityException error) {
					// Only the master node can perform the recovery operation!
				} catch (RuntimeException error) {
					logger.error("Error occurred while marking transaction status as rolled back!", error);
				} catch (Exception error) {
					logger.error("Error occurred while marking transaction status as rolled back!", error);
				}
			}

		}
	}

	public void process(WatchedEvent event) throws Exception {
		if (EventType.NodeChildrenChanged.equals(event.getType())) {
			String parent = String.format("%s/%s/rollback", CONSTANTS_ROOT_PATH, CommonUtils.getApplication(this.endpoint));
			this.curatorFramework.getChildren().usingWatcher(this).inBackground(this).forPath(parent);
		}
	}

	private void remvBusinessStageRollbackFlag(TransactionXid transactionXid) {
		String global = ByteUtils.byteArrayToString(transactionXid.getGlobalTransactionId());
		String parent = String.format("%s/%s/rollback", CONSTANTS_ROOT_PATH, CommonUtils.getApplication(this.endpoint));
		String target = String.format("%s/%s", parent, global);
		try {
			this.curatorFramework.delete().inBackground(this).forPath(target);
		} catch (Exception error) {
			logger.warn("Error occurred while deleting zookeeper path({}).", target);
		}
	}

	public void markBusinessStageRollbackOnly(TransactionXid transactionXid) throws SystemException {
		String global = ByteUtils.byteArrayToString(transactionXid.getGlobalTransactionId());
		String parent = String.format("%s/%s/rollback", CONSTANTS_ROOT_PATH, CommonUtils.getApplication(this.endpoint));
		String target = String.format("%s/%s", parent, global);
		try {
			byte[] instanceByteArray = this.endpoint == null ? new byte[0] : this.endpoint.getBytes();
			this.curatorFramework.create().withMode(CreateMode.PERSISTENT).forPath(target, instanceByteArray);
		} catch (NodeExistsException error) {
			logger.debug("Path exists(path= {})!", target); // ignore
		} catch (Exception error) {
			SystemException systemEx = new SystemException(XAException.XAER_RMERR);
			systemEx.initCause(error);
			throw systemEx;
		}
	}

	private void markTransactionRollback(TransactionXid transactionXid) {
		try {
			byte[] global = transactionXid.getGlobalTransactionId();
			String identifier = ByteUtils.byteArrayToString(global);

			String application = CommonUtils.getApplication(this.endpoint);

			String databaseName = application.replaceAll("\\W", "_");
			MongoDatabase mdb = this.mongoClient.getDatabase(databaseName);
			MongoCollection<Document> collection = mdb.getCollection(CONSTANTS_TB_TRANSACTIONS);

			Document document = new Document();
			document.append("$set", new Document("status", Status.STATUS_MARKED_ROLLBACK));

			Bson globalFilter = Filters.eq(CONSTANTS_FD_GLOBAL, identifier);
			Bson statusFilter = Filters.eq("status", Status.STATUS_ACTIVE);

			collection.updateOne(Filters.and(globalFilter, statusFilter), document);
		} catch (RuntimeException error) {
			logger.error("Error occurred while setting the error flag.", error);
		}
	}

	public void putTransaction(TransactionXid xid, Transaction transaction) {
	}

	public Transaction getTransaction(TransactionXid xid) throws TransactionException {
		CompensableManagerImpl compensableManager = //
				(CompensableManagerImpl) this.beanFactory.getCompensableManager();
		Transaction transaction = compensableManager.getTransaction(xid);
		if (transaction != null) {
			return transaction;
		}

		return this.getTransactionFromMongoDB(xid);
	}

	private Transaction getTransactionFromMongoDB(TransactionXid xid) throws TransactionException {
		TransactionRecovery compensableRecovery = this.beanFactory.getCompensableRecovery();
		CompensableLogger compensableLogger = this.beanFactory.getCompensableLogger();

		MongoCursor<Document> transactionCursor = null;
		try {
			String application = CommonUtils.getApplication(this.endpoint);

			String databaseName = application.replaceAll("\\W", "_");
			MongoDatabase mdb = this.mongoClient.getDatabase(databaseName);
			MongoCollection<Document> transactions = mdb.getCollection(CONSTANTS_TB_TRANSACTIONS);

			byte[] global = xid.getGlobalTransactionId();
			String globalKey = ByteUtils.byteArrayToString(global);

			FindIterable<Document> transactionItr = transactions.find(Filters.eq(CONSTANTS_FD_GLOBAL, globalKey));
			transactionCursor = transactionItr.iterator();
			if (transactionCursor.hasNext() == false) {
				return null;
			}
			Document document = transactionCursor.next();

			MongoCompensableLogger mongoCompensableLogger = (MongoCompensableLogger) compensableLogger;
			TransactionArchive archive = mongoCompensableLogger.reconstructTransactionArchive(document);

			return compensableRecovery.reconstruct(archive);
		} catch (RuntimeException error) {
			logger.error("Error occurred while getting transaction.", error);
			throw new TransactionException(XAException.XAER_RMERR);
		} catch (Exception error) {
			logger.error("Error occurred while getting transaction.", error);
			throw new TransactionException(XAException.XAER_RMERR);
		} finally {
			IOUtils.closeQuietly(transactionCursor);
		}
	}

	public Transaction removeTransaction(TransactionXid xid) {
		return null;
	}

	public void putErrorTransaction(TransactionXid transactionXid, Transaction transaction) {
		try {
			TransactionArchive archive = (TransactionArchive) transaction.getTransactionArchive();
			byte[] global = transactionXid.getGlobalTransactionId();
			String identifier = ByteUtils.byteArrayToString(global);

			int status = archive.getCompensableStatus();

			String databaseName = CommonUtils.getApplication(this.endpoint).replaceAll("\\W", "_");
			MongoDatabase mdb = this.mongoClient.getDatabase(databaseName);
			MongoCollection<Document> collection = mdb.getCollection(CONSTANTS_TB_TRANSACTIONS);

			Document target = new Document();
			target.append("modified", this.endpoint);
			target.append("status", status);
			target.append("error", true);
			target.append("recovered_at", archive.getRecoveredAt() == 0 ? null : new Date(archive.getRecoveredAt()));
			target.append("recovered_times", archive.getRecoveredTimes());

			Document document = new Document();
			document.append("$set", target);
			// document.append("$inc", new BasicDBObject("modified_time", 1));

			UpdateResult result = collection.updateOne(Filters.eq(CONSTANTS_FD_GLOBAL, identifier), document);
			if (result.getMatchedCount() != 1) {
				throw new IllegalStateException(
						String.format("Error occurred while updating transaction(matched= %s, modified= %s).",
								result.getMatchedCount(), result.getModifiedCount()));
			}
		} catch (RuntimeException error) {
			logger.error("Error occurred while setting the error flag.", error);
		}
	}

	public Transaction getErrorTransaction(TransactionXid xid) throws TransactionException {
		TransactionRecovery compensableRecovery = this.beanFactory.getCompensableRecovery();
		CompensableLogger compensableLogger = this.beanFactory.getCompensableLogger();

		MongoCursor<Document> transactionCursor = null;
		try {
			String application = CommonUtils.getApplication(this.endpoint);
			String databaseName = application.replaceAll("\\W", "_");
			MongoDatabase mdb = this.mongoClient.getDatabase(databaseName);
			MongoCollection<Document> transactions = mdb.getCollection(CONSTANTS_TB_TRANSACTIONS);

			byte[] global = xid.getGlobalTransactionId();

			Bson globalFilter = Filters.eq(CONSTANTS_FD_GLOBAL, ByteUtils.byteArrayToString(global));
			Bson errorFilter = Filters.eq("error", true);

			FindIterable<Document> transactionItr = transactions.find(Filters.and(globalFilter, errorFilter));
			transactionCursor = transactionItr.iterator();
			if (transactionCursor.hasNext() == false) {
				return null;
			}

			Document document = transactionCursor.next();

			MongoCompensableLogger mongoCompensableLogger = (MongoCompensableLogger) compensableLogger;
			TransactionArchive archive = mongoCompensableLogger.reconstructTransactionArchive(document);

			return compensableRecovery.reconstruct(archive);
		} catch (RuntimeException error) {
			logger.error("Error occurred while getting error transaction.", error);
			throw new TransactionException(XAException.XAER_RMERR);
		} catch (Exception error) {
			logger.error("Error occurred while getting error transaction.", error);
			throw new TransactionException(XAException.XAER_RMERR);
		} finally {
			IOUtils.closeQuietly(transactionCursor);
		}
	}

	public Transaction removeErrorTransaction(TransactionXid xid) {
		return null;
	}

	public List<Transaction> getErrorTransactionList() throws TransactionException {
		TransactionRecovery compensableRecovery = this.beanFactory.getCompensableRecovery();
		CompensableLogger compensableLogger = this.beanFactory.getCompensableLogger();

		List<Transaction> transactionList = new ArrayList<Transaction>();

		MongoCursor<Document> transactionCursor = null;
		try {
			String application = CommonUtils.getApplication(this.endpoint);
			String databaseName = application.replaceAll("\\W", "_");
			MongoDatabase mdb = this.mongoClient.getDatabase(databaseName);
			MongoCollection<Document> transactions = mdb.getCollection(CONSTANTS_TB_TRANSACTIONS);

			FindIterable<Document> transactionItr = transactions.find(Filters.eq("coordinator", true));
			for (transactionCursor = transactionItr.iterator(); transactionCursor.hasNext();) {
				Document document = transactionCursor.next();
				boolean error = document.getBoolean("error");

				String targetApplication = document.getString("created");
				long expectVersion = document.getLong("version");
				long actualVersion = this.versionManager.getInstanceVersion(targetApplication);

				if (error == false && actualVersion > 0 && actualVersion <= expectVersion) {
					continue; // ignore
				}

				MongoCompensableLogger mongoCompensableLogger = (MongoCompensableLogger) compensableLogger;
				TransactionArchive archive = mongoCompensableLogger.reconstructTransactionArchive(document);

				Transaction transaction = compensableRecovery.reconstruct(archive);
				transactionList.add(transaction);
			}

			return transactionList;
		} catch (RuntimeException error) {
			logger.error("Error occurred while getting error transactions.", error);
			throw new TransactionException(XAException.XAER_RMERR);
		} catch (Exception error) {
			logger.error("Error occurred while getting error transactions.", error);
			throw new TransactionException(XAException.XAER_RMERR);
		} finally {
			IOUtils.closeQuietly(transactionCursor);
		}
	}

	public CommandDispatcher getCommandDispatcher() {
		return commandDispatcher;
	}

	public void setCommandDispatcher(CommandDispatcher commandDispatcher) {
		this.commandDispatcher = commandDispatcher;
	}

	public long getRollbackEntryExpireTime() {
		return rollbackEntryExpireTime;
	}

	public void setRollbackEntryExpireTime(long rollbackEntryExpireTime) {
		this.rollbackEntryExpireTime = rollbackEntryExpireTime;
	}

	public List<Transaction> getActiveTransactionList() {
		return new ArrayList<Transaction>();
	}

	public void setEndpoint(String identifier) {
		this.endpoint = identifier;
	}

	public String getEndpoint() {
		return endpoint;
	}

	public CompensableBeanFactory getBeanFactory() {
		return beanFactory;
	}

	public void setBeanFactory(CompensableBeanFactory tbf) {
		this.beanFactory = tbf;
	}

}
