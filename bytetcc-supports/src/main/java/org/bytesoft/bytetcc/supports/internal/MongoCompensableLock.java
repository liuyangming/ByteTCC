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

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.api.BackgroundCallback;
import org.apache.curator.framework.api.CuratorEvent;
import org.apache.curator.framework.api.CuratorEventType;
import org.apache.curator.framework.api.CuratorWatcher;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.framework.state.ConnectionStateListener;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException.NodeExistsException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher.Event.EventType;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bytesoft.common.utils.ByteUtils;
import org.bytesoft.common.utils.CommonUtils;
import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.aware.CompensableBeanFactoryAware;
import org.bytesoft.compensable.aware.CompensableEndpointAware;
import org.bytesoft.transaction.TransactionLock;
import org.bytesoft.transaction.xa.TransactionXid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

import com.mongodb.client.FindIterable;
import com.mongodb.client.ListIndexesIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;

public class MongoCompensableLock implements TransactionLock, CompensableEndpointAware, CompensableBeanFactoryAware,
		CuratorWatcher, ConnectionStateListener, BackgroundCallback, InitializingBean {
	static Logger logger = LoggerFactory.getLogger(MongoCompensableLock.class);
	static final String CONSTANTS_ROOT_PATH = "/org/bytesoft/bytetcc";
	static final String CONSTANTS_DB_NAME = "bytetcc";
	static final String CONSTANTS_TB_LOCKS = "locks";
	static final String CONSTANTS_FD_GLOBAL = "gxid";
	static final String CONSTANTS_FD_BRANCH = "bxid";
	static final String CONSTANTS_FD_SYSTEM = "system";

	static final int MONGODB_ERROR_DUPLICATE_KEY = 11000;

	@javax.annotation.Resource(name = "compensableMongoClient")
	private MongoClient mongoClient;
	@javax.annotation.Resource(name = "compensableCuratorFramework")
	private CuratorFramework curatorFramework;
	private String endpoint;
	@javax.inject.Inject
	private CompensableBeanFactory beanFactory;
	private boolean initializeEnabled = true;

	private final Set<String> instances = new HashSet<String>();
	private transient ConnectionState curatorState;

	public void afterPropertiesSet() throws Exception {
		if (this.initializeEnabled) {
			this.initializeIndexIfNecessary();
		}

		this.curatorFramework.getConnectionStateListenable().addListener(this);

		this.initializeClusterInstanceConfig();
	}

	private void initializeClusterInstanceConfig() throws Exception {
		String parent = String.format("%s/%s/instances", CONSTANTS_ROOT_PATH, CommonUtils.getApplication(this.endpoint));
		try {
			this.curatorFramework.create() //
					.creatingParentContainersIfNeeded().withMode(CreateMode.PERSISTENT).forPath(parent);
		} catch (NodeExistsException nex) {
			logger.debug("Path exists(path= {})!", parent);
		}

		String path = String.format("%s/%s", parent, this.endpoint);
		this.curatorFramework.create().withMode(CreateMode.EPHEMERAL).forPath(path);

		this.getInstancesDirectorysChildrenAndRegisterWatcher();
	}

	private void initializeIndexIfNecessary() {
		this.createLocksIndexIfNecessary();
	}

	private void createLocksIndexIfNecessary() {
		MongoDatabase database = this.mongoClient.getDatabase(CONSTANTS_DB_NAME);
		MongoCollection<Document> locks = database.getCollection(CONSTANTS_TB_LOCKS);
		ListIndexesIterable<Document> lockIndexList = locks.listIndexes();
		boolean transactionIndexExists = false;
		MongoCursor<Document> lockCursor = null;
		try {
			lockCursor = lockIndexList.iterator();
			while (transactionIndexExists == false && lockCursor.hasNext()) {
				Document document = lockCursor.next();
				Boolean unique = document.getBoolean("unique");
				Document key = (Document) document.get("key");

				boolean globalExists = key.containsKey(CONSTANTS_FD_GLOBAL);
				boolean systemExists = key.containsKey(CONSTANTS_FD_SYSTEM);
				boolean lengthEquals = key.size() == 2;
				transactionIndexExists = lengthEquals && globalExists && systemExists;

				if (transactionIndexExists && unique == false) {
					throw new IllegalStateException();
				}
			}
		} finally {
			IOUtils.closeQuietly(lockCursor);
		}

		if (transactionIndexExists == false) {
			Document index = new Document(CONSTANTS_FD_GLOBAL, 1).append(CONSTANTS_FD_SYSTEM, 1);
			locks.createIndex(index, new IndexOptions().unique(true));
		}
	}

	public boolean lockTransaction(TransactionXid transactionXid, String identifier) {
		if (this.lockTransactionInMongoDB(transactionXid, identifier)) {
			return true;
		}

		String instanceId = this.getTransactionOwnerInMongoDB(transactionXid);
		if (StringUtils.isBlank(instanceId)) {
			return false;
		}

		boolean instanceCrashed = false;
		synchronized (this) {
			instanceCrashed = this.instances.contains(instanceId) == false;
		}

		if (instanceCrashed) {
			return this.takeOverTransactionInMongoDB(transactionXid, instanceId, identifier);
		}

		return false;
	}

	private boolean lockTransactionInMongoDB(TransactionXid transactionXid, String identifier) {
		byte[] global = transactionXid.getGlobalTransactionId();
		String instanceId = ByteUtils.byteArrayToString(global);

		try {
			MongoDatabase mdb = this.mongoClient.getDatabase(CONSTANTS_DB_NAME);
			MongoCollection<Document> collection = mdb.getCollection(CONSTANTS_TB_LOCKS);

			String[] values = this.endpoint.split("\\s*:\\s*");
			String application = values[1];

			Document document = new Document();
			document.append(CONSTANTS_FD_GLOBAL, instanceId);
			document.append(CONSTANTS_FD_SYSTEM, application);
			document.append("identifier", identifier);

			collection.insertOne(document);
			return true;
		} catch (com.mongodb.MongoWriteException error) {
			com.mongodb.WriteError writeError = error.getError();
			if (MONGODB_ERROR_DUPLICATE_KEY != writeError.getCode()) {
				logger.error("Error occurred while locking transaction(gxid= {}).", instanceId, error);
			}
			return false;
		} catch (RuntimeException rex) {
			logger.error("Error occurred while locking transaction(gxid= {}).", instanceId, rex);
			return false;
		}
	}

	private boolean takeOverTransactionInMongoDB(TransactionXid transactionXid, String source, String target) {
		byte[] global = transactionXid.getGlobalTransactionId();
		String instanceId = ByteUtils.byteArrayToString(global);

		try {
			MongoDatabase mdb = this.mongoClient.getDatabase(CONSTANTS_DB_NAME);
			MongoCollection<Document> collection = mdb.getCollection(CONSTANTS_TB_LOCKS);

			String[] values = this.endpoint.split("\\s*:\\s*");
			String application = values[1];

			Bson globalFilter = Filters.eq(CONSTANTS_FD_GLOBAL, instanceId);
			Bson systemFilter = Filters.eq(CONSTANTS_FD_SYSTEM, application);
			Bson instIdFilter = Filters.eq("identifier", source);

			Document document = new Document("$set", new Document("identifier", target));

			UpdateResult result = collection.updateOne(Filters.and(globalFilter, systemFilter, instIdFilter), document);
			return result.getModifiedCount() == 1;
		} catch (RuntimeException rex) {
			logger.error("Error occurred while locking transaction(gxid= {}).", instanceId, rex);
			return false;
		}
	}

	private String getTransactionOwnerInMongoDB(TransactionXid transactionXid) {
		byte[] global = transactionXid.getGlobalTransactionId();
		String instanceId = ByteUtils.byteArrayToString(global);

		try {
			MongoDatabase mdb = this.mongoClient.getDatabase(CONSTANTS_DB_NAME);
			MongoCollection<Document> collection = mdb.getCollection(CONSTANTS_TB_LOCKS);

			String[] values = this.endpoint.split("\\s*:\\s*");
			String application = values[1];

			Bson globalFilter = Filters.eq(CONSTANTS_FD_GLOBAL, instanceId);
			Bson systemFilter = Filters.eq(CONSTANTS_FD_SYSTEM, application);

			FindIterable<Document> findIterable = collection.find(Filters.and(globalFilter, systemFilter));
			MongoCursor<Document> cursor = findIterable.iterator();
			if (cursor.hasNext()) {
				Document document = cursor.next();
				return document.getString("identifier");
			} else {
				return null;
			}
		} catch (RuntimeException rex) {
			logger.error("Error occurred while querying the lock-owner of transaction(gxid= {}).", instanceId, rex);
			return null;
		}
	}

	public void unlockTransaction(TransactionXid transactionXid, String identifier) {
		this.unlockTransactionInMongoDB(transactionXid, identifier);
	}

	public void unlockTransactionInMongoDB(TransactionXid transactionXid, String identifier) {
		byte[] global = transactionXid.getGlobalTransactionId();
		String instanceId = ByteUtils.byteArrayToString(global);

		try {
			MongoDatabase mdb = this.mongoClient.getDatabase(CONSTANTS_DB_NAME);
			MongoCollection<Document> collection = mdb.getCollection(CONSTANTS_TB_LOCKS);

			String[] values = this.endpoint.split("\\s*:\\s*");
			String system = values[1];

			Bson globalFilter = Filters.eq(CONSTANTS_FD_GLOBAL, instanceId);
			Bson systemFilter = Filters.eq(CONSTANTS_FD_SYSTEM, system);
			Bson instIdFilter = Filters.eq("identifier", identifier);

			DeleteResult result = collection.deleteOne(Filters.and(globalFilter, systemFilter, instIdFilter));
			if (result.getDeletedCount() == 0) {
				logger.warn("Error occurred while unlocking transaction(gxid= {}).", instanceId);
			}
		} catch (RuntimeException rex) {
			logger.error("Error occurred while unlocking transaction(gxid= {})!", instanceId, rex);
		}
	}

	public synchronized void processResult(CuratorFramework client, CuratorEvent event) throws Exception {
		if (CuratorEventType.CHILDREN.equals(event.getType())) {
			return;
		}

		List<String> children = event.getChildren();
		this.instances.clear();
		if (children != null) {
			this.instances.addAll(children);
		} // end-if (children != null)
	}

	public synchronized void stateChanged(CuratorFramework client, final ConnectionState target) {
		ConnectionState source = this.curatorState;
		this.curatorState = target;

		if (target.equals(source)) {
			return; // should never happen
		}

		switch (target) {
		case CONNECTED:
		case RECONNECTED:
			try {
				this.getInstancesDirectorysChildrenAndRegisterWatcher();
			} catch (Exception ex) {
				logger.error("Error occurred while registering curator watcher!", ex);
			}
			break;
		default /* SUSPENDED, LOST, READ_ONLY */:
			break;
		}
	}

	public void process(WatchedEvent event) throws Exception {
		if (EventType.NodeChildrenChanged.equals(event.getType())) {
			this.processNodeChildrenChanged(event);
		}

		ConnectionState target = this.getCuratorConnectionState(event.getState());
		if (target != null && target.equals(this.curatorState) == false) {
			this.stateChanged(this.curatorFramework, target);
		} // end-if (target != null && target.equals(this.curatorState) == false)
	}

	private void processNodeChildrenChanged(WatchedEvent event) throws Exception {
		this.getInstancesDirectorysChildrenAndRegisterWatcher();
	}

	private void getInstancesDirectorysChildrenAndRegisterWatcher() throws Exception {
		String parent = String.format("%s/%s/instances", CONSTANTS_ROOT_PATH, CommonUtils.getApplication(this.endpoint));
		this.curatorFramework.getChildren().usingWatcher(this).inBackground(this).forPath(parent);
	}

	private ConnectionState getCuratorConnectionState(final KeeperState state) {
		if (state == null) {
			return null;
		} else {
			switch (state) {
			case SyncConnected:
				return ConnectionState.RECONNECTED;
			case Disconnected:
				return ConnectionState.LOST;
			case Expired:
				return ConnectionState.LOST;
			case AuthFailed:
			case ConnectedReadOnly:
			case SaslAuthenticated:
			default:
				return null;
			}
		}
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
