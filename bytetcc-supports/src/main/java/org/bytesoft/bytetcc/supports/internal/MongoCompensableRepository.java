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

import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
import org.bytesoft.bytetcc.CompensableTransactionImpl;
import org.bytesoft.bytetcc.supports.CompensableInvocationImpl;
import org.bytesoft.bytetcc.supports.CompensableRolledbackMarker;
import org.bytesoft.bytetcc.work.CommandManager;
import org.bytesoft.common.utils.ByteUtils;
import org.bytesoft.common.utils.CommonUtils;
import org.bytesoft.common.utils.SerializeUtils;
import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.archive.CompensableArchive;
import org.bytesoft.compensable.archive.TransactionArchive;
import org.bytesoft.compensable.aware.CompensableBeanFactoryAware;
import org.bytesoft.compensable.aware.CompensableEndpointAware;
import org.bytesoft.transaction.Transaction;
import org.bytesoft.transaction.TransactionException;
import org.bytesoft.transaction.TransactionRecovery;
import org.bytesoft.transaction.TransactionRepository;
import org.bytesoft.transaction.archive.XAResourceArchive;
import org.bytesoft.transaction.supports.resource.XAResourceDescriptor;
import org.bytesoft.transaction.supports.serialize.XAResourceDeserializer;
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
	static final String CONSTANTS_DB_NAME = "bytetcc";
	static final String CONSTANTS_TB_TRANSACTIONS = "transactions";
	static final String CONSTANTS_TB_PARTICIPANTS = "participants";
	static final String CONSTANTS_TB_COMPENSABLES = "compensables";
	static final String CONSTANTS_FD_GLOBAL = "gxid";
	static final String CONSTANTS_FD_BRANCH = "bxid";
	static final String CONSTANTS_FD_SYSTEM = "system";

	private final Map<TransactionXid, Transaction> transactionMap = new ConcurrentHashMap<TransactionXid, Transaction>();

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
	private CommandManager commandManager;

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
			String instanceId = ByteUtils.byteArrayToString(instanceByteArray);
			if (StringUtils.equalsIgnoreCase(this.endpoint, instanceId)) {
				return;
			}

			int startIdx = prefix.length();
			int endIndex = target.indexOf("/", startIdx);
			String global = endIndex == -1 ? target.substring(startIdx) : target.substring(startIdx, endIndex);
			byte[] globalByteArray = ByteUtils.stringToByteArray(global);
			TransactionXid transactionXid = xidFactory.createGlobalXid(globalByteArray);

			CompensableTransactionImpl transaction = //
					(CompensableTransactionImpl) this.transactionMap.get(transactionXid);
			if (transaction != null) {
				transaction.markBusinessStageRollbackOnly(transactionXid);
			} // end-if (transaction != null)

			long createdAt = event.getStat().getCtime();
			long interval = System.currentTimeMillis() - createdAt;

			if (interval < 0) {
				logger.warn("The system time between servers is inconsistent.");
			} // end-if (interval < 0)

			if (interval >= this.rollbackEntryExpireTime) {
				this.commandManager.execute(new Runnable() {
					public void run() {
						remvBusinessStageRollbackFlag(transactionXid);
					}
				});
			} else {
				this.commandManager.execute(new Runnable() {
					public void run() {
						markTransactionRollback(transactionXid);
					}
				});
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
			byte[] instanceByteArray = ByteUtils.stringToByteArray(this.endpoint);
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

			MongoDatabase mdb = this.mongoClient.getDatabase(CONSTANTS_DB_NAME);
			MongoCollection<Document> collection = mdb.getCollection(CONSTANTS_TB_TRANSACTIONS);

			Document document = new Document();
			document.append("$set", new Document("status", Status.STATUS_MARKED_ROLLBACK));

			Bson globalFilter = Filters.eq(CONSTANTS_FD_GLOBAL, identifier);
			Bson systemFilter = Filters.eq(CONSTANTS_FD_SYSTEM, application);
			Bson statusFilter = Filters.eq("status", Status.STATUS_ACTIVE);

			UpdateResult result = collection.updateOne(Filters.and(globalFilter, systemFilter, statusFilter), document);
			if (result.getMatchedCount() != 1) {
				throw new IllegalStateException(
						String.format("Error occurred while updating transaction(matched= %s, modified= %s).",
								result.getMatchedCount(), result.getModifiedCount()));
			}
		} catch (RuntimeException error) {
			logger.error("Error occurred while setting the error flag.", error);
		}
	}

	public void putTransaction(TransactionXid xid, Transaction transaction) {
		this.transactionMap.put(xid, transaction);
	}

	@SuppressWarnings("unchecked")
	public Transaction getTransaction(TransactionXid xid) throws TransactionException {
		TransactionRecovery compensableRecovery = this.beanFactory.getCompensableRecovery();
		XidFactory compensableXidFactory = this.beanFactory.getCompensableXidFactory();

		MongoCursor<Document> transactionCursor = null;
		try {
			MongoDatabase mdb = this.mongoClient.getDatabase(CONSTANTS_DB_NAME);
			MongoCollection<Document> transactions = mdb.getCollection(CONSTANTS_TB_TRANSACTIONS);

			String application = CommonUtils.getApplication(this.endpoint);
			byte[] global = xid.getGlobalTransactionId();

			Bson globalFilter = Filters.eq(CONSTANTS_FD_GLOBAL, ByteUtils.byteArrayToString(global));
			Bson systemFilter = Filters.eq(CONSTANTS_FD_SYSTEM, application);

			FindIterable<Document> transactionItr = transactions.find(Filters.and(globalFilter, systemFilter));
			transactionCursor = transactionItr.iterator();
			if (transactionCursor.hasNext() == false) {
				return null;
			}
			Document document = transactionCursor.next();
			TransactionArchive archive = new TransactionArchive();

			TransactionXid globalXid = compensableXidFactory.createGlobalXid(global);
			archive.setXid(globalXid);

			boolean propagated = document.getBoolean("propagated");
			String propagatedBy = document.getString("propagated_by");
			boolean compensable = document.getBoolean("compensable");
			boolean coordinator = document.getBoolean("coordinator");
			int compensableStatus = document.getInteger("status");
			Integer recoveredTimes = document.getInteger("recovered_times");
			Date recoveredAt = document.getDate("recovered_at");

			String textVariables = document.getString("variables");
			byte[] variablesByteArray = ByteUtils.stringToByteArray(textVariables);
			Map<String, Serializable> variables = //
					(Map<String, Serializable>) SerializeUtils.deserializeObject(variablesByteArray);

			archive.setVariables(variables);

			archive.setRecoveredAt(recoveredAt == null ? 0 : recoveredAt.getTime());
			archive.setRecoveredTimes(recoveredTimes == null ? 0 : recoveredTimes);

			archive.setCompensable(compensable);
			archive.setCoordinator(coordinator);
			archive.setCompensableStatus(compensableStatus);
			archive.setPropagated(propagated);
			archive.setPropagatedBy(propagatedBy);

			this.initializeParticipantList(archive);
			this.initializeCompensableList(archive);

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

	private void initializeCompensableList(TransactionArchive archive) throws ClassNotFoundException, Exception, IOException {
		XidFactory transactionXidFactory = this.beanFactory.getTransactionXidFactory();

		MongoDatabase mdb = this.mongoClient.getDatabase(CONSTANTS_DB_NAME);
		MongoCollection<Document> compensables = mdb.getCollection(CONSTANTS_TB_COMPENSABLES);

		TransactionXid xid = (TransactionXid) archive.getXid();
		String application = CommonUtils.getApplication(this.endpoint);
		byte[] global = xid.getGlobalTransactionId();

		Bson globalFilter = Filters.eq(CONSTANTS_FD_GLOBAL, ByteUtils.byteArrayToString(global));
		Bson systemFilter = Filters.eq(CONSTANTS_FD_SYSTEM, application);

		MongoCursor<Document> compensableCursor = null;
		try {
			FindIterable<Document> compensableItr = compensables.find(Filters.and(globalFilter, systemFilter));
			compensableCursor = compensableItr.iterator();
			for (; compensableCursor.hasNext();) {
				Document document = compensableCursor.next();
				CompensableArchive compensable = new CompensableArchive();

				String gxid = document.getString(CONSTANTS_FD_GLOBAL);
				String bxid = document.getString(CONSTANTS_FD_BRANCH);

				boolean coordinator = document.getBoolean("coordinator");
				boolean tried = document.getBoolean("tried");
				boolean confirmed = document.getBoolean("confirmed");
				boolean cancelled = document.getBoolean("cancelled");
				String serviceId = document.getString("serviceId");
				boolean simplified = document.getBoolean("simplified");
				String confirmableKey = document.getString("confirmable_key");
				String cancellableKey = document.getString("cancellable_key");
				String argsValue = document.getString("args");
				String clazzName = document.getString("interface");
				String methodDesc = document.getString("method");

				String transactionKey = document.getString("transaction_key");
				String compensableKey = document.getString("compensable_key");

				String transactionXid = document.getString("transaction_xid");
				String compensableXid = document.getString("compensable_xid");

				CompensableInvocationImpl invocation = new CompensableInvocationImpl();
				invocation.setIdentifier(serviceId);
				invocation.setSimplified(simplified);

				Class<?> clazz = Thread.currentThread().getContextClassLoader().loadClass(clazzName);
				Method method = SerializeUtils.deserializeMethod(clazz, methodDesc);
				invocation.setMethod(method);

				byte[] argsByteArray = ByteUtils.stringToByteArray(argsValue);
				Object[] args = (Object[]) SerializeUtils.deserializeObject(argsByteArray);
				invocation.setArgs(args);

				invocation.setConfirmableKey(confirmableKey);
				invocation.setCancellableKey(cancellableKey);

				compensable.setCompensable(invocation);

				compensable.setConfirmed(confirmed);
				compensable.setCancelled(cancelled);
				compensable.setTried(tried);
				compensable.setCoordinator(coordinator);

				compensable.setTransactionResourceKey(transactionKey);
				compensable.setCompensableResourceKey(compensableKey);

				String[] transactionArray = transactionXid.split("\\s*\\-\\s*");
				if (transactionArray.length == 3) {
					String transactionGlobalId = transactionArray[1];
					String transactionBranchId = transactionArray[2];
					TransactionXid transactionGlobalXid = transactionXidFactory
							.createGlobalXid(ByteUtils.stringToByteArray(transactionGlobalId));
					if (StringUtils.isNotBlank(transactionBranchId)) {
						TransactionXid transactionBranchXid = transactionXidFactory.createBranchXid(transactionGlobalXid,
								ByteUtils.stringToByteArray(transactionBranchId));
						compensable.setTransactionXid(transactionBranchXid);
					} else {
						compensable.setTransactionXid(transactionGlobalXid);
					}
				}

				String[] compensableArray = compensableXid.split("\\s*\\-\\s*");
				if (compensableArray.length == 3) {
					String compensableGlobalId = compensableArray[1];
					String compensableBranchId = compensableArray[2];
					TransactionXid compensableGlobalXid = transactionXidFactory
							.createGlobalXid(ByteUtils.stringToByteArray(compensableGlobalId));
					if (StringUtils.isNotBlank(compensableBranchId)) {
						TransactionXid compensableBranchXid = transactionXidFactory.createBranchXid(compensableGlobalXid,
								ByteUtils.stringToByteArray(compensableBranchId));
						compensable.setCompensableXid(compensableBranchXid);
					} else {
						compensable.setCompensableXid(compensableGlobalXid);
					}
				}

				byte[] globalTransactionId = ByteUtils.stringToByteArray(gxid);
				byte[] branchQualifier = ByteUtils.stringToByteArray(bxid);
				TransactionXid globalXid = transactionXidFactory.createGlobalXid(globalTransactionId);
				TransactionXid branchXid = transactionXidFactory.createBranchXid(globalXid, branchQualifier);

				compensable.setIdentifier(branchXid);

				archive.getCompensableResourceList().add(compensable);
			}
		} finally {
			IOUtils.closeQuietly(compensableCursor);
		}
	}

	private void initializeParticipantList(TransactionArchive archive) {
		XidFactory transactionXidFactory = this.beanFactory.getTransactionXidFactory();

		MongoDatabase mdb = this.mongoClient.getDatabase(CONSTANTS_DB_NAME);
		MongoCollection<Document> participants = mdb.getCollection(CONSTANTS_TB_PARTICIPANTS);

		TransactionXid xid = (TransactionXid) archive.getXid();
		String application = CommonUtils.getApplication(this.endpoint);
		byte[] global = xid.getGlobalTransactionId();

		Bson globalFilter = Filters.eq(CONSTANTS_FD_GLOBAL, ByteUtils.byteArrayToString(global));
		Bson systemFilter = Filters.eq(CONSTANTS_FD_SYSTEM, application);

		MongoCursor<Document> participantCursor = null;
		try {
			FindIterable<Document> participantItr = participants.find(Filters.and(globalFilter, systemFilter));
			participantCursor = participantItr.iterator();
			for (; participantCursor.hasNext();) {
				Document document = participantCursor.next();
				XAResourceArchive participant = new XAResourceArchive();

				String gxid = document.getString(CONSTANTS_FD_GLOBAL);
				String bxid = document.getString(CONSTANTS_FD_BRANCH);

				String descriptorType = document.getString("type");
				String identifier = document.getString("resource");

				int vote = document.getInteger("vote");
				boolean committed = document.getBoolean("committed");
				boolean rolledback = document.getBoolean("rolledback");
				boolean readonly = document.getBoolean("readonly");
				boolean completed = document.getBoolean("completed");
				boolean heuristic = document.getBoolean("heuristic");

				byte[] globalTransactionId = ByteUtils.stringToByteArray(gxid);
				byte[] branchQualifier = ByteUtils.stringToByteArray(bxid);
				TransactionXid globalXid = transactionXidFactory.createGlobalXid(globalTransactionId);
				TransactionXid branchXid = transactionXidFactory.createBranchXid(globalXid, branchQualifier);
				participant.setXid(branchXid);

				XAResourceDeserializer resourceDeserializer = this.beanFactory.getResourceDeserializer();
				XAResourceDescriptor descriptor = resourceDeserializer.deserialize(identifier);
				if (descriptor != null //
						&& descriptor.getClass().getName().equals(descriptorType) == false) {
					throw new IllegalStateException();
				}

				participant.setVote(vote);
				participant.setCommitted(committed);
				participant.setRolledback(rolledback);
				participant.setReadonly(readonly);
				participant.setCompleted(completed);
				participant.setHeuristic(heuristic);

				participant.setDescriptor(descriptor);

				archive.getRemoteResources().add(participant);
			}
		} finally {
			IOUtils.closeQuietly(participantCursor);
		}
	}

	public Transaction removeTransaction(TransactionXid xid) {
		return this.transactionMap.remove(xid);
	}

	public void putErrorTransaction(TransactionXid transactionXid, Transaction transaction) {
		try {
			TransactionArchive archive = (TransactionArchive) transaction.getTransactionArchive();
			byte[] global = transactionXid.getGlobalTransactionId();
			String identifier = ByteUtils.byteArrayToString(global);

			String application = CommonUtils.getApplication(this.endpoint);

			int status = archive.getCompensableStatus();

			MongoDatabase mdb = this.mongoClient.getDatabase(CONSTANTS_DB_NAME);
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

			Bson globalFilter = Filters.eq(CONSTANTS_FD_GLOBAL, identifier);
			Bson systemFilter = Filters.eq(CONSTANTS_FD_SYSTEM, application);

			UpdateResult result = collection.updateOne(Filters.and(globalFilter, systemFilter), document);
			if (result.getMatchedCount() != 1) {
				throw new IllegalStateException(
						String.format("Error occurred while updating transaction(matched= %s, modified= %s).",
								result.getMatchedCount(), result.getModifiedCount()));
			}
		} catch (RuntimeException error) {
			logger.error("Error occurred while setting the error flag.", error);
		}
	}

	@SuppressWarnings("unchecked")
	public Transaction getErrorTransaction(TransactionXid xid) throws TransactionException {
		TransactionRecovery compensableRecovery = this.beanFactory.getCompensableRecovery();
		XidFactory compensableXidFactory = this.beanFactory.getCompensableXidFactory();

		MongoCursor<Document> transactionCursor = null;
		try {
			MongoDatabase mdb = this.mongoClient.getDatabase(CONSTANTS_DB_NAME);
			MongoCollection<Document> transactions = mdb.getCollection(CONSTANTS_TB_TRANSACTIONS);

			String application = CommonUtils.getApplication(this.endpoint);
			byte[] global = xid.getGlobalTransactionId();

			Bson globalFilter = Filters.eq(CONSTANTS_FD_GLOBAL, ByteUtils.byteArrayToString(global));
			Bson systemFilter = Filters.eq(CONSTANTS_FD_SYSTEM, application);
			Bson errorFilter = Filters.eq("error", true);

			FindIterable<Document> transactionItr = transactions.find(Filters.and(globalFilter, systemFilter, errorFilter));
			transactionCursor = transactionItr.iterator();
			if (transactionCursor.hasNext() == false) {
				return null;
			}

			Document document = transactionCursor.next();
			TransactionArchive archive = new TransactionArchive();

			TransactionXid globalXid = compensableXidFactory.createGlobalXid(global);
			archive.setXid(globalXid);

			boolean propagated = document.getBoolean("propagated");
			String propagatedBy = document.getString("propagated_by");
			boolean compensable = document.getBoolean("compensable");
			boolean coordinator = document.getBoolean("coordinator");
			int compensableStatus = document.getInteger("status");
			Integer recoveredTimes = document.getInteger("recovered_times");
			Date recoveredAt = document.getDate("recovered_at");

			String textVariables = document.getString("variables");
			byte[] variablesByteArray = ByteUtils.stringToByteArray(textVariables);
			Map<String, Serializable> variables = //
					(Map<String, Serializable>) SerializeUtils.deserializeObject(variablesByteArray);

			archive.setVariables(variables);

			archive.setRecoveredAt(recoveredAt == null ? 0 : recoveredAt.getTime());
			archive.setRecoveredTimes(recoveredTimes == null ? 0 : recoveredTimes);

			archive.setCompensable(compensable);
			archive.setCoordinator(coordinator);
			archive.setCompensableStatus(compensableStatus);
			archive.setPropagated(propagated);
			archive.setPropagatedBy(propagatedBy);

			this.initializeParticipantList(archive);
			this.initializeCompensableList(archive);

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

	@SuppressWarnings("unchecked")
	public List<Transaction> getErrorTransactionList() throws TransactionException {
		TransactionRecovery compensableRecovery = this.beanFactory.getCompensableRecovery();
		XidFactory compensableXidFactory = this.beanFactory.getCompensableXidFactory();

		List<Transaction> transactionList = new ArrayList<Transaction>();

		MongoCursor<Document> transactionCursor = null;
		try {
			MongoDatabase mdb = this.mongoClient.getDatabase(CONSTANTS_DB_NAME);
			MongoCollection<Document> transactions = mdb.getCollection(CONSTANTS_TB_TRANSACTIONS);

			String application = CommonUtils.getApplication(this.endpoint);

			Bson systemFilter = Filters.eq(CONSTANTS_FD_SYSTEM, application);
			Bson coordinatorFilter = Filters.eq("coordinator", true);

			FindIterable<Document> transactionItr = //
					transactions.find(Filters.and(systemFilter, coordinatorFilter));
			for (transactionCursor = transactionItr.iterator(); transactionCursor.hasNext();) {
				Document document = transactionCursor.next();
				TransactionArchive archive = new TransactionArchive();

				String gxid = document.getString(CONSTANTS_FD_GLOBAL);
				boolean propagated = document.getBoolean("propagated");
				String propagatedBy = document.getString("propagated_by");
				boolean compensable = document.getBoolean("compensable");
				boolean coordinator = document.getBoolean("coordinator");
				int compensableStatus = document.getInteger("status");
				boolean error = document.getBoolean("error");
				Integer recoveredTimes = document.getInteger("recovered_times");
				Date recoveredAt = document.getDate("recovered_at");
				String textVariables = document.getString("variables");

				String targetApplication = document.getString("created");
				long expectVersion = document.getLong("version");
				long actualVersion = this.versionManager.getInstanceVersion(targetApplication);

				if (error == false && actualVersion > 0 && actualVersion <= expectVersion) {
					continue; // ignore
				}

				byte[] global = ByteUtils.stringToByteArray(gxid);
				TransactionXid globalXid = compensableXidFactory.createGlobalXid(global);
				archive.setXid(globalXid);

				byte[] variablesByteArray = ByteUtils.stringToByteArray(textVariables);
				Map<String, Serializable> variables = //
						(Map<String, Serializable>) SerializeUtils.deserializeObject(variablesByteArray);

				archive.setVariables(variables);

				archive.setRecoveredAt(recoveredAt == null ? 0 : recoveredAt.getTime());
				archive.setRecoveredTimes(recoveredTimes == null ? 0 : recoveredTimes);

				archive.setCompensable(compensable);
				archive.setCoordinator(coordinator);
				archive.setCompensableStatus(compensableStatus);
				archive.setPropagated(propagated);
				archive.setPropagatedBy(propagatedBy);

				this.initializeParticipantList(archive);
				this.initializeCompensableList(archive);

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
