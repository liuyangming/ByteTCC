package org.bytesoft.bytetcc.supports.work;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import javax.resource.spi.work.Work;
import javax.transaction.xa.XAException;
import javax.transaction.xa.Xid;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bytesoft.bytejta.supports.jdbc.RecoveredResource;
import org.bytesoft.bytejta.supports.resource.LocalXAResourceDescriptor;
import org.bytesoft.bytetcc.supports.internal.MongoCompensableLogger;
import org.bytesoft.bytetcc.supports.resource.LocalResourceCleaner;
import org.bytesoft.common.utils.ByteUtils;
import org.bytesoft.common.utils.CommonUtils;
import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.aware.CompensableBeanFactoryAware;
import org.bytesoft.compensable.aware.CompensableEndpointAware;
import org.bytesoft.transaction.cmd.CommandDispatcher;
import org.bytesoft.transaction.supports.serialize.XAResourceDeserializer;
import org.bytesoft.transaction.xa.TransactionXid;
import org.bytesoft.transaction.xa.XidFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;

public class CompensableCleanupWork
		implements Work, LocalResourceCleaner, CompensableEndpointAware, CompensableBeanFactoryAware {
	static Logger logger = LoggerFactory.getLogger(MongoCompensableLogger.class);
	static final String CONSTANTS_TB_REMOVEDRESES = "removedreses";
	static final String CONSTANTS_FD_GLOBAL = "gxid";
	static final String CONSTANTS_FD_BRANCH = "bxid";

	static final long CONSTANTS_SECOND_MILLIS = 1000L;
	static final int CONSTANTS_MAX_HANDLE_RECORDS = 1000;

	@javax.annotation.Resource
	private MongoClient mongoClient;
	@javax.inject.Inject
	private CommandDispatcher commandDispatcher;
	private String endpoint;
	private boolean released;
	@javax.inject.Inject
	private CompensableBeanFactory beanFactory;

	public void forget(Xid xid, String resourceId) throws RuntimeException {
		try {
			String application = CommonUtils.getApplication(this.endpoint);
			String databaseName = application.replaceAll("\\W", "_");
			MongoDatabase mdb = this.mongoClient.getDatabase(databaseName);
			MongoCollection<Document> collection = mdb.getCollection(CONSTANTS_TB_REMOVEDRESES);

			byte[] global = xid.getGlobalTransactionId();
			byte[] branch = xid.getBranchQualifier();

			Document document = new Document();
			document.append(CONSTANTS_FD_GLOBAL, ByteUtils.byteArrayToString(global));
			document.append(CONSTANTS_FD_BRANCH, ByteUtils.byteArrayToString(branch));
			document.append("resource_id", resourceId);
			document.append("created", this.endpoint);

			collection.insertOne(document);
		} catch (RuntimeException error) {
			logger.error("Error occurred while forgetting resource({}).", resourceId, error);
		}
	}

	public void run() {
		long nextMillis = System.currentTimeMillis() + CONSTANTS_SECOND_MILLIS * 60;
		while (this.released == false) {
			if (System.currentTimeMillis() < nextMillis) {
				this.waitForMillis(100);
			} else {
				int number = 0;
				try {
					number = (Integer) this.commandDispatcher.dispatch(new Callable<Object>() {
						public Object call() throws Exception {
							return timingExecution(CONSTANTS_MAX_HANDLE_RECORDS);
						}
					});
				} catch (SecurityException rex) {
					logger.debug(rex.getMessage());
				} catch (Exception rex) {
					logger.error("Error occurred while cleaning up resources.", rex);
					nextMillis = System.currentTimeMillis() + CONSTANTS_SECOND_MILLIS * 30;
					continue;
				}

				if (number < CONSTANTS_MAX_HANDLE_RECORDS) {
					nextMillis = System.currentTimeMillis() + CONSTANTS_SECOND_MILLIS * 30;
				} // end-if (number < CONSTANTS_MAX_HANDLE_RECORDS)
			}
		}
	}

	public int timingExecution(int batchSize) {
		String databaseName = CommonUtils.getApplication(this.endpoint).replaceAll("\\W", "_");
		MongoDatabase mdb = this.mongoClient.getDatabase(databaseName);
		MongoCollection<Document> collection = mdb.getCollection(CONSTANTS_TB_REMOVEDRESES);

		int length = 0;

		XidFactory xidFactory = this.beanFactory.getCompensableXidFactory();

		Map<String, List<Xid>> resource2XidListMap = new HashMap<String, List<Xid>>();
		MongoCursor<Document> cursor = null;
		try {
			cursor = collection.find().limit(batchSize).iterator();
			for (; cursor.hasNext(); length++) {
				Document document = cursor.next();
				String globalValue = document.getString(CONSTANTS_FD_GLOBAL);
				String branchValue = document.getString(CONSTANTS_FD_BRANCH);
				byte[] global = ByteUtils.stringToByteArray(globalValue);
				byte[] branch = ByteUtils.stringToByteArray(branchValue);

				TransactionXid globalXid = xidFactory.createGlobalXid(global);
				TransactionXid branchXid = xidFactory.createBranchXid(globalXid, branch);

				String resourceId = document.getString("resource_id");
				if (StringUtils.isBlank(resourceId)) {
					continue;
				}

				List<Xid> xidList = resource2XidListMap.get(resourceId);
				if (xidList == null) {
					xidList = new ArrayList<Xid>();
					resource2XidListMap.put(resourceId, xidList);
				}

				xidList.add(branchXid);
			}
		} finally {
			IOUtils.closeQuietly(cursor);
		}

		for (Iterator<Map.Entry<String, List<Xid>>> itr = resource2XidListMap.entrySet().iterator(); itr.hasNext();) {
			Map.Entry<String, List<Xid>> entry = itr.next();
			String resourceId = entry.getKey();
			List<Xid> xidList = entry.getValue();
			this.cleanupByResource(resourceId, xidList);
		}

		for (Iterator<Map.Entry<String, List<Xid>>> itr = resource2XidListMap.entrySet().iterator(); itr.hasNext();) {
			Map.Entry<String, List<Xid>> entry = itr.next();
			List<Xid> xidList = entry.getValue();
			for (int i = 0; i < xidList.size(); i++) {
				Xid transactionXid = xidList.get(i);
				byte[] global = transactionXid.getGlobalTransactionId();
				byte[] branch = transactionXid.getBranchQualifier();

				Bson globalFilter = Filters.eq(CONSTANTS_FD_GLOBAL, ByteUtils.byteArrayToString(global));
				Bson branchFilter = Filters.eq(CONSTANTS_FD_BRANCH, ByteUtils.byteArrayToString(branch));

				collection.deleteOne(Filters.and(globalFilter, branchFilter));
			}
		}

		return length;
	}

	private void cleanupByResource(String resourceId, List<Xid> xidList) throws RuntimeException {
		XAResourceDeserializer resourceDeserializer = this.beanFactory.getResourceDeserializer();
		if (StringUtils.isBlank(resourceId)) {
			throw new IllegalStateException();
		}

		Xid[] xidArray = new Xid[xidList.size()];
		xidList.toArray(xidArray);
		LocalXAResourceDescriptor descriptor = //
				(LocalXAResourceDescriptor) resourceDeserializer.deserialize(resourceId);
		RecoveredResource resource = (RecoveredResource) descriptor.getDelegate();
		try {
			resource.forget(xidArray);
		} catch (XAException xaex) {
			logger.error("Error occurred while forgetting resource: {}.", resourceId, xaex);

			switch (xaex.errorCode) {
			case XAException.XAER_NOTA:
				break;
			case XAException.XAER_RMERR:
			case XAException.XAER_RMFAIL:
				throw new IllegalStateException();
			}
		}

	}

	private void waitForMillis(long millis) {
		try {
			Thread.sleep(millis);
		} catch (Exception ex) {
			logger.debug(ex.getMessage());
		}
	}

	public void release() {
		this.released = true;
	}

	public CompensableBeanFactory getBeanFactory() {
		return this.beanFactory;
	}

	public void setBeanFactory(CompensableBeanFactory tbf) {
		this.beanFactory = tbf;
	}

	public String getEndpoint() {
		return this.endpoint;
	}

	public void setEndpoint(String identifier) {
		this.endpoint = identifier;
	}

	public CommandDispatcher getCommandDispatcher() {
		return commandDispatcher;
	}

	public void setCommandDispatcher(CommandDispatcher commandDispatcher) {
		this.commandDispatcher = commandDispatcher;
	}

}
