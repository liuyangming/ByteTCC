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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
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
import org.springframework.beans.factory.SmartInitializingSingleton;

import com.mongodb.client.FindIterable;
import com.mongodb.client.ListIndexesIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;

public class MongoCompensableLock implements TransactionLock, CompensableInstVersionManager, CompensableEndpointAware,
		CompensableBeanFactoryAware, CuratorWatcher, ConnectionStateListener, BackgroundCallback, SmartInitializingSingleton {
	static Logger logger = LoggerFactory.getLogger(MongoCompensableLock.class);
	static final String CONSTANTS_ROOT_PATH = "/org/bytesoft/bytetcc";
	static final String CONSTANTS_DB_NAME = "bytetcc";
	static final String CONSTANTS_TB_LOCKS = "locks";
	static final String CONSTANTS_TB_INSTS = "instances";
	static final String CONSTANTS_FD_GLOBAL = "gxid";
	static final String CONSTANTS_FD_BRANCH = "bxid";
	static final String CONSTANTS_FD_SYSTEM = "system";

	static final int MONGODB_ERROR_DUPLICATE_KEY = 11000;

	@javax.annotation.Resource
	private MongoClient mongoClient;
	@javax.annotation.Resource
	private CuratorFramework curatorFramework;
	private String endpoint;
	@javax.inject.Inject
	private CompensableBeanFactory beanFactory;
	private boolean initializeEnabled = true;

	private final Map<String, Long> instances = new HashMap<String, Long>();
	// private transient ConnectionState curatorState;

	private transient long instanceVersion;

	public void afterSingletonsInstantiated() {
		try {
			this.afterPropertiesSet();
		} catch (Exception error) {
			throw new RuntimeException(error);
		}
	}

	public void afterPropertiesSet() throws Exception {
		if (this.initializeEnabled) {
			this.initializeIndexIfNecessary();
		}

		this.curatorFramework.blockUntilConnected();
		this.curatorFramework.getConnectionStateListenable().addListener(this);

		this.initializeClusterInstancesDirectory();

		this.initializeClusterInstanceVersion();
		this.initializeClusterInstanceConfig();
	}

	private void initializeClusterInstancesDirectory() throws Exception {
		String parent = String.format("%s/%s/instances", CONSTANTS_ROOT_PATH, CommonUtils.getApplication(this.endpoint));
		try {
			this.curatorFramework.create() //
					.creatingParentContainersIfNeeded().withMode(CreateMode.PERSISTENT).forPath(parent);
		} catch (NodeExistsException nex) {
			logger.debug("Path exists(path= {})!", parent); // ignore
		}
	}

	private void initializeClusterInstanceConfig() throws Exception {
		this.initializeCurrentClusterInstanceConfigIfNecessary();
		this.getInstancesDirectorysChildrenAndRegisterWatcher();
	}

	private void initializeCurrentClusterInstanceConfigIfNecessary() throws Exception {
		String parent = String.format("%s/%s/instances", CONSTANTS_ROOT_PATH, CommonUtils.getApplication(this.endpoint));
		String path = String.format("%s/%s", parent, this.endpoint);
		byte[] versionByteArray = ByteUtils.longToByteArray(this.instanceVersion);
		try {
			this.curatorFramework.create().withMode(CreateMode.EPHEMERAL).forPath(path, versionByteArray);
		} catch (NodeExistsException error) {
			this.curatorFramework.setData().forPath(path, versionByteArray); // Node exists!
		}
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

				if (transactionIndexExists && (unique == null || unique == false)) {
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

	private void initializeClusterInstanceVersion() {
		MongoDatabase database = this.mongoClient.getDatabase(CONSTANTS_DB_NAME);
		MongoCollection<Document> instances = database.getCollection(CONSTANTS_TB_INSTS);

		Bson condition = Filters.eq("_id", this.endpoint);

		Document increases = new Document();
		increases.append("version", 1L);

		Document variables = new Document();
		variables.append(CONSTANTS_FD_SYSTEM, CommonUtils.getApplication(this.endpoint));

		Document document = new Document();
		document.append("$inc", increases);
		document.append("$set", variables);

		FindOneAndUpdateOptions options = new FindOneAndUpdateOptions();
		options.upsert(true);

		Document target = instances.findOneAndUpdate(condition, document, new FindOneAndUpdateOptions().upsert(true));
		this.instanceVersion = (target == null) ? 1 : (target.getLong("version") + 1);
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
			instanceCrashed = this.instances.containsKey(instanceId) == false;
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

			String application = CommonUtils.getApplication(this.endpoint);

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

			String application = CommonUtils.getApplication(this.endpoint);

			Bson globalFilter = Filters.eq(CONSTANTS_FD_GLOBAL, instanceId);
			Bson systemFilter = Filters.eq(CONSTANTS_FD_SYSTEM, application);
			Bson instIdFilter = Filters.eq("identifier", source);

			Document document = new Document("$set", new Document("identifier", target));

			UpdateResult result = collection.updateOne(Filters.and(globalFilter, systemFilter, instIdFilter), document);
			return result.getMatchedCount() == 1;
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

			String application = CommonUtils.getApplication(this.endpoint);

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

			String system = CommonUtils.getApplication(this.endpoint);

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
		String application = CommonUtils.getApplication(this.endpoint);
		String prefix = String.format("%s/%s/instances/", CONSTANTS_ROOT_PATH, application);
		String parent = String.format("%s/%s/instances", CONSTANTS_ROOT_PATH, application);
		String current = event.getPath();
		if (CuratorEventType.CHILDREN.equals(event.getType())) {
			if (StringUtils.equalsIgnoreCase(parent, current) || StringUtils.equalsIgnoreCase(prefix, current)) {
				Set<String> original = this.instances.keySet();
				List<String> children = event.getChildren();

				Set<String> deleted = new HashSet<String>(original);
				Set<String> created = new HashSet<String>(children);

				deleted.removeAll(children);
				created.removeAll(original);

				for (Iterator<String> itr = deleted.iterator(); itr.hasNext();) {
					String element = itr.next();
					this.instances.remove(element);
				}

				for (Iterator<String> itr = created.iterator(); itr.hasNext();) {
					String element = itr.next();
					String path = String.format("%s/%s", parent, element);
					this.curatorFramework.getData().usingWatcher(this).inBackground(this).forPath(path);
				} // end-for (Iterator<String> itr = created.iterator(); itr.hasNext();)
			}
		} else if (CuratorEventType.GET_DATA.equals(event.getType())) {
			if (current.startsWith(prefix)) {
				String system = current.substring(prefix.length());
				long version = ByteUtils.byteArrayToLong(event.getData());
				this.instances.put(system, version);
			}
		} else if (CuratorEventType.SET_DATA.equals(event.getType())) {
			if (current.startsWith(prefix)) {
				String system = current.substring(prefix.length());
				long version = ByteUtils.byteArrayToLong(event.getData());
				this.instances.put(system, version);
			}
		} else if (CuratorEventType.DELETE.equals(event.getType())) {
			String path = String.format("%s/%s", parent, this.endpoint);
			if (StringUtils.equalsIgnoreCase(path, current)) {
				try {
					this.initializeCurrentClusterInstanceConfigIfNecessary();
				} catch (Exception error) {
					logger.error("Error occurred while re-initializing instance node!", error);
				}
			}
		}
	}

	public synchronized void stateChanged(CuratorFramework client, final ConnectionState target) {
		// ConnectionState source = this.curatorState;
		// this.curatorState = target;
		//
		// if (target.equals(source)) {
		// return; // should never happen
		// }

		switch (target) {
		case CONNECTED:
		case RECONNECTED:
			try {
				this.initializeClusterInstanceConfig();
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

		// ConnectionState target = this.getCuratorConnectionState(event.getState());
		// if (target != null && target.equals(this.curatorState) == false) {
		// this.stateChanged(this.curatorFramework, target);
		// } // end-if (target != null && target.equals(this.curatorState) == false)
	}

	private void processNodeChildrenChanged(WatchedEvent event) throws Exception {
		this.getInstancesDirectorysChildrenAndRegisterWatcher();
	}

	private void getInstancesDirectorysChildrenAndRegisterWatcher() throws Exception {
		String parent = String.format("%s/%s/instances", CONSTANTS_ROOT_PATH, CommonUtils.getApplication(this.endpoint));
		this.curatorFramework.getChildren().usingWatcher(this).inBackground(this).forPath(parent);
	}

	// private ConnectionState getCuratorConnectionState(final KeeperState state) {
	// if (state == null) {
	// return null;
	// } else {
	// switch (state) {
	// case SyncConnected:
	// return ConnectionState.RECONNECTED;
	// case Disconnected:
	// return ConnectionState.LOST;
	// case Expired:
	// return ConnectionState.LOST;
	// case AuthFailed:
	// case ConnectedReadOnly:
	// case SaslAuthenticated:
	// default:
	// return null;
	// }
	// }
	// }

	public long getInstanceVersion(String instanceId) {
		Long version = this.instances.get(instanceId);
		return version == null ? -1 : version;
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
