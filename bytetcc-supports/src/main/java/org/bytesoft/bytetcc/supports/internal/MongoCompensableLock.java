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

import org.apache.commons.io.IOUtils;
import org.apache.curator.framework.CuratorFramework;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException.NodeExistsException;
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

import com.mongodb.client.ListIndexesIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.result.DeleteResult;

public class MongoCompensableLock
		implements TransactionLock, CompensableEndpointAware, CompensableBeanFactoryAware, InitializingBean {
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

	public void afterPropertiesSet() throws Exception {
		if (this.initializeEnabled) {
			this.initializeIndexIfNecessary();
		}

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

		this.curatorFramework.create().withMode(CreateMode.EPHEMERAL).forPath(String.format("%s/%s", parent, this.endpoint));
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
		try {
			MongoDatabase mdb = this.mongoClient.getDatabase(CONSTANTS_DB_NAME);
			MongoCollection<Document> collection = mdb.getCollection(CONSTANTS_TB_LOCKS);

			String[] values = this.endpoint.split("\\s*:\\s*");
			String application = values[1];

			byte[] global = transactionXid.getGlobalTransactionId();
			String instanceId = ByteUtils.byteArrayToString(global);

			Document document = new Document();
			document.append(CONSTANTS_FD_GLOBAL, instanceId);
			document.append(CONSTANTS_FD_SYSTEM, application);
			document.append("identifier", identifier);

			collection.insertOne(document);
			return true;
		} catch (com.mongodb.MongoWriteException error) {
			com.mongodb.WriteError writeError = error.getError();
			if (MONGODB_ERROR_DUPLICATE_KEY != writeError.getCode()) {
				logger.error("Error occurred while creating transaction-archive.", error);
			}
			return false;
		} catch (RuntimeException rex) {
			logger.error("Error occurred while creating transaction-archive.", rex);
			return false;
		}
	}

	public void unlockTransaction(TransactionXid transactionXid, String identifier) {
		try {
			MongoDatabase mdb = this.mongoClient.getDatabase(CONSTANTS_DB_NAME);
			MongoCollection<Document> collection = mdb.getCollection(CONSTANTS_TB_LOCKS);

			String[] values = this.endpoint.split("\\s*:\\s*");
			String system = values[1];

			byte[] global = transactionXid.getGlobalTransactionId();
			String instanceId = ByteUtils.byteArrayToString(global);

			Bson globalFilter = Filters.eq(CONSTANTS_FD_GLOBAL, instanceId);
			Bson systemFilter = Filters.eq(CONSTANTS_FD_SYSTEM, system);

			DeleteResult result = collection.deleteOne(Filters.and(globalFilter, systemFilter));
			if (result.getDeletedCount() == 0) {
				logger.warn("Error occurred while unlocking transaction(gxid= {}).", instanceId);
			}
		} catch (RuntimeException rex) {
			logger.error("Error occurred while unlocking transaction!", rex);
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
