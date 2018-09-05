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
package org.bytesoft.bytetcc.supports.logging;

import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.transaction.xa.Xid;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bytesoft.bytetcc.supports.CompensableInvocationImpl;
import org.bytesoft.common.utils.ByteUtils;
import org.bytesoft.common.utils.SerializeUtils;
import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.CompensableInvocation;
import org.bytesoft.compensable.archive.CompensableArchive;
import org.bytesoft.compensable.archive.TransactionArchive;
import org.bytesoft.compensable.aware.CompensableBeanFactoryAware;
import org.bytesoft.compensable.aware.CompensableEndpointAware;
import org.bytesoft.compensable.logging.CompensableLogger;
import org.bytesoft.transaction.archive.XAResourceArchive;
import org.bytesoft.transaction.recovery.TransactionRecoveryCallback;
import org.bytesoft.transaction.supports.resource.XAResourceDescriptor;
import org.bytesoft.transaction.supports.serialize.XAResourceDeserializer;
import org.bytesoft.transaction.xa.TransactionXid;
import org.bytesoft.transaction.xa.XidFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.BasicDBObject;
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

public class MongoCompensableLogger
		implements CompensableLogger, CompensableEndpointAware, CompensableBeanFactoryAware, InitializingBean {
	static Logger logger = LoggerFactory.getLogger(MongoCompensableLogger.class);
	static final String CONSTANTS_DB_NAME = "bytetcc";
	static final String CONSTANTS_TB_TRANSACTIONS = "transactions";
	static final String CONSTANTS_TB_PARTICIPANTS = "participants";
	static final String CONSTANTS_TB_COMPENSABLES = "compensables";
	static final String CONSTANTS_FD_GLOBAL = "gxid";
	static final String CONSTANTS_FD_BRANCH = "bxid";
	static final String CONSTANTS_FD_SYSTEM = "system";

	static final int MONGODB_ERROR_DUPLICATE_KEY = 11000;

	@javax.annotation.Resource(name = "compensableMongoClient")
	private MongoClient mongoClient;
	private String endpoint;
	@javax.inject.Inject
	private CompensableBeanFactory beanFactory;
	private boolean initializeEnabled = true;

	public void afterPropertiesSet() throws Exception {
		if (this.initializeEnabled) {
			this.initializeIndexIfNecessary();
		}
	}

	private void initializeIndexIfNecessary() {
		this.createTransactionsGlobalTxKeyIndexIfNecessary();
		this.createTransactionsApplicationIndexIfNecessary();
		this.createParticipantsGlobalTxKeyIndexIfNecessary();
		this.createCompensablesGlobalTxKeyIndexIfNecessary();
	}

	private void createTransactionsApplicationIndexIfNecessary() {
		MongoDatabase database = this.mongoClient.getDatabase(CONSTANTS_DB_NAME);
		MongoCollection<Document> transactions = database.getCollection(CONSTANTS_TB_TRANSACTIONS);
		ListIndexesIterable<Document> transactionIndexList = transactions.listIndexes();
		boolean applicationIndexExists = false;
		MongoCursor<Document> applicationCursor = null;
		try {
			applicationCursor = transactionIndexList.iterator();
			while (applicationIndexExists == false && applicationCursor.hasNext()) {
				Document document = applicationCursor.next();
				Boolean unique = document.getBoolean("unique");
				Document key = (Document) document.get("key");

				boolean systemExists = key.containsKey(CONSTANTS_FD_SYSTEM);
				boolean lengthEquals = key.size() == 1;
				applicationIndexExists = lengthEquals && systemExists;

				if (applicationIndexExists && unique) {
					throw new IllegalStateException();
				}
			}
		} finally {
			IOUtils.closeQuietly(applicationCursor);
		}

		if (applicationIndexExists == false) {
			transactions.createIndex(new Document(CONSTANTS_FD_SYSTEM, 1), new IndexOptions().unique(false));
		}
	}

	private void createTransactionsGlobalTxKeyIndexIfNecessary() {
		MongoDatabase database = this.mongoClient.getDatabase(CONSTANTS_DB_NAME);
		MongoCollection<Document> transactions = database.getCollection(CONSTANTS_TB_TRANSACTIONS);
		ListIndexesIterable<Document> transactionIndexList = transactions.listIndexes();
		boolean transactionIndexExists = false;
		MongoCursor<Document> transactionCursor = null;
		try {
			transactionCursor = transactionIndexList.iterator();
			while (transactionIndexExists == false && transactionCursor.hasNext()) {
				Document document = transactionCursor.next();
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
			IOUtils.closeQuietly(transactionCursor);
		}

		if (transactionIndexExists == false) {
			Document index = new Document(CONSTANTS_FD_GLOBAL, 1).append(CONSTANTS_FD_SYSTEM, 1);
			transactions.createIndex(index, new IndexOptions().unique(true));
		}
	}

	private void createParticipantsGlobalTxKeyIndexIfNecessary() {
		MongoDatabase database = this.mongoClient.getDatabase(CONSTANTS_DB_NAME);
		MongoCollection<Document> participants = database.getCollection(CONSTANTS_TB_PARTICIPANTS);
		ListIndexesIterable<Document> participantIndexList = participants.listIndexes();
		boolean participantIndexExists = false;
		MongoCursor<Document> participantCursor = null;
		try {
			participantCursor = participantIndexList.iterator();
			while (participantIndexExists == false && participantCursor.hasNext()) {
				Document document = participantCursor.next();
				Boolean unique = document.getBoolean("unique");
				Document key = (Document) document.get("key");

				boolean globalExists = key.containsKey(CONSTANTS_FD_GLOBAL);
				boolean branchExists = key.containsKey(CONSTANTS_FD_BRANCH);
				boolean lengthEquals = key.size() == 2;
				participantIndexExists = lengthEquals && globalExists && branchExists;

				if (participantIndexExists && unique == false) {
					throw new IllegalStateException();
				}
			}
		} finally {
			IOUtils.closeQuietly(participantCursor);
		}

		if (participantIndexExists == false) {
			Document index = new Document(CONSTANTS_FD_GLOBAL, 1).append(CONSTANTS_FD_BRANCH, 1);
			participants.createIndex(index, new IndexOptions().unique(true));
		}
	}

	private void createCompensablesGlobalTxKeyIndexIfNecessary() {
		MongoDatabase database = this.mongoClient.getDatabase(CONSTANTS_DB_NAME);
		MongoCollection<Document> compensables = database.getCollection(CONSTANTS_TB_COMPENSABLES);
		ListIndexesIterable<Document> compensableIndexList = compensables.listIndexes();
		boolean compensableIndexExists = false;
		MongoCursor<Document> compensableCursor = null;
		try {
			compensableCursor = compensableIndexList.iterator();
			while (compensableIndexExists == false && compensableCursor.hasNext()) {
				Document document = compensableCursor.next();
				Boolean unique = document.getBoolean("unique");
				Document key = (Document) document.get("key");

				boolean globalExists = key.containsKey(CONSTANTS_FD_GLOBAL);
				boolean branchExists = key.containsKey(CONSTANTS_FD_BRANCH);
				boolean lengthEquals = key.size() == 2;
				compensableIndexExists = lengthEquals && globalExists && branchExists;

				if (compensableIndexExists && unique == false) {
					throw new IllegalStateException();
				}
			}
		} finally {
			IOUtils.closeQuietly(compensableCursor);
		}

		if (compensableIndexExists == false) {
			Document index = new Document(CONSTANTS_FD_GLOBAL, 1).append(CONSTANTS_FD_BRANCH, 1);
			compensables.createIndex(index, new IndexOptions().unique(true));
		}
	}

	public void createTransaction(TransactionArchive archive) {
		try {
			TransactionXid transactionXid = (TransactionXid) archive.getXid();
			boolean compensable = archive.isCompensable();
			boolean coordinator = archive.isCoordinator();
			Object propagatedBy = archive.getPropagatedBy();
			int status = archive.getCompensableStatus();
			boolean propagated = archive.isPropagated();

			Map<String, Serializable> variables = archive.getVariables();
			ObjectMapper mapper = new ObjectMapper();
			Object jsonVariables = mapper.writeValueAsString(variables);
			byte[] variablesByteArray = SerializeUtils.serializeObject((Serializable) variables);
			String textVariables = ByteUtils.byteArrayToString(variablesByteArray);

			MongoDatabase mdb = this.mongoClient.getDatabase(CONSTANTS_DB_NAME);
			MongoCollection<Document> collection = mdb.getCollection(CONSTANTS_TB_TRANSACTIONS);

			String[] values = this.endpoint.split("\\s*:\\s*");
			String application = values[1];

			byte[] global = transactionXid.getGlobalTransactionId();
			String identifier = ByteUtils.byteArrayToString(global);

			Document document = new Document();
			document.append(CONSTANTS_FD_GLOBAL, identifier);
			document.append(CONSTANTS_FD_SYSTEM, application);
			document.append("created", this.endpoint);
			document.append("modified", this.endpoint);
			document.append("propagated", propagated);
			document.append("propagated_by", propagatedBy);
			document.append("compensable", compensable);
			document.append("coordinator", coordinator);
			document.append("status", status);
			document.append("lock", 0);
			document.append("locked_by", this.endpoint);
			document.append("vars", jsonVariables);
			document.append("variables", textVariables);
			document.append("error", false);
			document.append("version", 0L);

			collection.insertOne(document);

			List<XAResourceArchive> participantList = archive.getRemoteResources();
			for (int i = 0; participantList != null && i < participantList.size(); i++) {
				XAResourceArchive resource = participantList.get(i);
				this.createParticipant(resource, true);
			}

			List<CompensableArchive> compensableList = archive.getCompensableResourceList();
			for (int i = 0; compensableList != null && i < compensableList.size(); i++) {
				CompensableArchive resource = compensableList.get(i);
				this.createCompensable(resource, true);
			}

		} catch (IOException error) {
			logger.error("Error occurred while creating transaction.", error);
			this.beanFactory.getCompensableManager().setRollbackOnlyQuietly();
		} catch (RuntimeException error) {
			logger.error("Error occurred while creating transaction.", error);
			this.beanFactory.getCompensableManager().setRollbackOnlyQuietly();
		}

	}

	public void updateTransaction(TransactionArchive archive) {
		try {
			TransactionXid transactionXid = (TransactionXid) archive.getXid();
			byte[] global = transactionXid.getGlobalTransactionId();
			String identifier = ByteUtils.byteArrayToString(global);

			String[] values = this.endpoint.split("\\s*:\\s*");
			String application = values[1];

			int status = archive.getCompensableStatus();

			MongoDatabase mdb = this.mongoClient.getDatabase(CONSTANTS_DB_NAME);
			MongoCollection<Document> collection = mdb.getCollection(CONSTANTS_TB_TRANSACTIONS);

			Map<String, Serializable> variables = archive.getVariables();
			ObjectMapper mapper = new ObjectMapper();
			Object jsonVariables = mapper.writeValueAsString(variables);
			byte[] variablesByteArray = SerializeUtils.serializeObject((Serializable) variables);
			String textVariables = ByteUtils.byteArrayToString(variablesByteArray);

			Document target = new Document();
			target.append("modified", this.endpoint);
			target.append("status", status);
			target.append("vars", jsonVariables);
			target.append("variables", textVariables);

			Document document = new Document();
			document.append("$set", target);
			document.append("$inc", new BasicDBObject("version", 1));

			Bson globalFilter = Filters.eq(CONSTANTS_FD_GLOBAL, identifier);
			Bson systemFilter = Filters.eq(CONSTANTS_FD_SYSTEM, application);
			UpdateResult result = collection.updateOne(Filters.and(globalFilter, systemFilter), document);
			if (result.getModifiedCount() != 1) {
				throw new IllegalStateException(
						String.format("Error occurred while updating transaction(matched= %s, modified= %s).",
								result.getMatchedCount(), result.getModifiedCount()));
			}

			List<XAResourceArchive> participantList = archive.getRemoteResources();
			for (int i = 0; participantList != null && i < participantList.size(); i++) {
				XAResourceArchive resource = participantList.get(i);
				this.updateParticipant(resource, true);
			}

			List<CompensableArchive> compensableList = archive.getCompensableResourceList();
			for (int i = 0; compensableList != null && i < compensableList.size(); i++) {
				CompensableArchive resource = compensableList.get(i);
				this.updateCompensable(resource, true);
			}

		} catch (IOException error) {
			logger.error("Error occurred while updating transaction.", error);
			this.beanFactory.getCompensableManager().setRollbackOnlyQuietly();
		} catch (RuntimeException error) {
			logger.error("Error occurred while updating transaction.", error);
			this.beanFactory.getCompensableManager().setRollbackOnlyQuietly();
		}
	}

	public void deleteTransaction(TransactionArchive archive) {
		try {
			TransactionXid transactionXid = (TransactionXid) archive.getXid();
			byte[] global = transactionXid.getGlobalTransactionId();
			String identifier = ByteUtils.byteArrayToString(global);

			String[] values = this.endpoint.split("\\s*:\\s*");
			String application = values[1];

			MongoDatabase mdb = this.mongoClient.getDatabase(CONSTANTS_DB_NAME);
			MongoCollection<Document> transactions = mdb.getCollection(CONSTANTS_TB_TRANSACTIONS);
			MongoCollection<Document> participants = mdb.getCollection(CONSTANTS_TB_PARTICIPANTS);
			MongoCollection<Document> compensables = mdb.getCollection(CONSTANTS_TB_COMPENSABLES);

			Bson globalFilter = Filters.eq(CONSTANTS_FD_GLOBAL, identifier);
			Bson systemFilter = Filters.eq(CONSTANTS_FD_SYSTEM, application);

			compensables.deleteMany(Filters.and(globalFilter, systemFilter));

			participants.deleteMany(Filters.and(globalFilter, systemFilter));

			DeleteResult result = transactions.deleteOne(Filters.and(globalFilter, systemFilter));
			if (result.getDeletedCount() != 1) {
				throw new IllegalStateException(
						String.format("Error occurred while deleting transaction(deleted= %s).", result.getDeletedCount()));
			}
		} catch (RuntimeException error) {
			logger.error("Error occurred while deleting transaction!", error);
		}
	}

	public void createParticipant(XAResourceArchive archive) {
		try {
			this.createParticipant(archive, true);
		} catch (RuntimeException error) {
			logger.error("Error occurred while creating participant!", error);
			this.beanFactory.getCompensableManager().setRollbackOnlyQuietly();
		}
	}

	private void createParticipant(XAResourceArchive archive, boolean redirect) {
		try {
			TransactionXid transactionXid = (TransactionXid) archive.getXid();
			byte[] global = transactionXid.getGlobalTransactionId();
			byte[] branch = transactionXid.getBranchQualifier();

			MongoDatabase mdb = this.mongoClient.getDatabase(CONSTANTS_DB_NAME);
			MongoCollection<Document> collection = mdb.getCollection(CONSTANTS_TB_PARTICIPANTS);

			Document document = new Document();

			XAResourceDescriptor descriptor = archive.getDescriptor();
			String descriptorType = descriptor.getClass().getName();
			String descriptorKey = descriptor.getIdentifier();

			int branchVote = archive.getVote();
			boolean readonly = archive.isReadonly();
			boolean committed = archive.isCommitted();
			boolean rolledback = archive.isRolledback();
			boolean completed = archive.isCompleted();
			boolean heuristic = archive.isHeuristic();

			String[] values = this.endpoint.split("\\s*:\\s*");
			String application = values[1];

			document.append(CONSTANTS_FD_GLOBAL, ByteUtils.byteArrayToString(global));
			document.append(CONSTANTS_FD_BRANCH, ByteUtils.byteArrayToString(branch));
			document.append(CONSTANTS_FD_SYSTEM, application);
			document.append("created", this.endpoint);

			document.append("type", descriptorType);
			document.append("resource", descriptorKey);

			document.append("vote", branchVote);
			document.append("committed", committed);
			document.append("rolledback", rolledback);
			document.append("readonly", readonly);
			document.append("completed", completed);
			document.append("heuristic", heuristic);

			document.append("created", this.endpoint);
			document.append("modified", this.endpoint);

			collection.insertOne(document);
		} catch (com.mongodb.MongoWriteException error) {
			com.mongodb.WriteError writeError = error.getError();
			if (MONGODB_ERROR_DUPLICATE_KEY == writeError.getCode() && redirect) {
				this.updateParticipant(archive, false);
			} else {
				throw error;
			}
		}
	}

	public void updateParticipant(XAResourceArchive archive) {
		try {
			this.updateParticipant(archive, true);
		} catch (RuntimeException error) {
			logger.error("Error occurred while updating participant.", error);
			this.beanFactory.getCompensableManager().setRollbackOnlyQuietly();
		}
	}

	private void updateParticipant(XAResourceArchive archive, boolean redirect) {
		TransactionXid transactionXid = (TransactionXid) archive.getXid();
		byte[] global = transactionXid.getGlobalTransactionId();
		byte[] branch = transactionXid.getBranchQualifier();

		MongoDatabase mdb = this.mongoClient.getDatabase(CONSTANTS_DB_NAME);
		MongoCollection<Document> collection = mdb.getCollection(CONSTANTS_TB_PARTICIPANTS);

		int branchVote = archive.getVote();
		boolean readonly = archive.isReadonly();
		boolean committed = archive.isCommitted();
		boolean rolledback = archive.isRolledback();
		boolean completed = archive.isCompleted();
		boolean heuristic = archive.isHeuristic();

		Document variables = new Document();
		variables.append("vote", branchVote);
		variables.append("committed", committed);
		variables.append("rolledback", rolledback);
		variables.append("readonly", readonly);
		variables.append("completed", completed);
		variables.append("heuristic", heuristic);

		variables.append("modified", this.endpoint);

		Bson globalFilter = Filters.eq(CONSTANTS_FD_GLOBAL, ByteUtils.byteArrayToString(global));
		Bson branchFilter = Filters.eq(CONSTANTS_FD_BRANCH, ByteUtils.byteArrayToString(branch));

		UpdateResult result = collection.updateOne(Filters.and(globalFilter, branchFilter), new Document("$set", variables));
		if (result.getModifiedCount() == 0 && redirect) {
			this.createParticipant(archive, false);
		} else if (result.getModifiedCount() != 1) {
			throw new IllegalStateException(
					String.format("Error occurred while updating participant(matched= %s, modified= %s).",
							result.getMatchedCount(), result.getModifiedCount()));
		}
	}

	public void deleteParticipant(XAResourceArchive archive) {
	}

	public void createCompensable(CompensableArchive archive) {
		try {
			this.createCompensable(archive, true);
		} catch (IOException error) {
			logger.error("Error occurred while creating compensable!", error);
			this.beanFactory.getCompensableManager().setRollbackOnlyQuietly();
		} catch (RuntimeException error) {
			logger.error("Error occurred while creating compensable!", error);
			this.beanFactory.getCompensableManager().setRollbackOnlyQuietly();
		}
	}

	private void createCompensable(CompensableArchive archive, boolean redirect) throws IOException {
		try {
			TransactionXid xid = (TransactionXid) archive.getIdentifier();
			byte[] global = xid.getGlobalTransactionId();
			byte[] branch = xid.getBranchQualifier();

			MongoDatabase mdb = this.mongoClient.getDatabase(CONSTANTS_DB_NAME);
			MongoCollection<Document> collection = mdb.getCollection(CONSTANTS_TB_COMPENSABLES);

			CompensableInvocation invocation = archive.getCompensable();
			String beanId = (String) invocation.getIdentifier();

			Method method = invocation.getMethod();
			Object[] args = invocation.getArgs();

			String methodDesc = SerializeUtils.serializeMethod(invocation.getMethod());
			byte[] argsByteArray = SerializeUtils.serializeObject(args);
			String argsValue = ByteUtils.byteArrayToString(argsByteArray);

			String[] values = this.endpoint.split("\\s*:\\s*");
			String application = values[1];

			Document document = new Document();
			document.append(CONSTANTS_FD_GLOBAL, ByteUtils.byteArrayToString(global));
			document.append(CONSTANTS_FD_BRANCH, ByteUtils.byteArrayToString(branch));
			document.append(CONSTANTS_FD_SYSTEM, application);
			document.append("created", this.endpoint);

			document.append("transaction_key", archive.getTransactionResourceKey());
			document.append("compensable_key", archive.getCompensableResourceKey());

			Xid transactionXid = archive.getTransactionXid();
			Xid compensableXid = archive.getCompensableXid();

			document.append("transaction_xid", String.valueOf(transactionXid));
			document.append("compensable_xid", String.valueOf(compensableXid));

			document.append("coordinator", archive.isCoordinator());
			document.append("tried", archive.isTried());
			document.append("confirmed", archive.isConfirmed());
			document.append("cancelled", archive.isCancelled());
			document.append("modified", this.endpoint);

			document.append("serviceId", beanId);
			document.append("simplified", invocation.isSimplified());
			document.append("confirmable_key", invocation.getConfirmableKey());
			document.append("cancellable_key", invocation.getCancellableKey());
			document.append("args", argsValue);
			document.append("interface", method.getDeclaringClass().getName());
			document.append("method", methodDesc);

			collection.insertOne(document);
		} catch (com.mongodb.MongoWriteException error) {
			com.mongodb.WriteError writeError = error.getError();
			if (MONGODB_ERROR_DUPLICATE_KEY == writeError.getCode() && redirect) {
				this.updateCompensable(archive, false);
			} else {
				throw error;
			}
		}
	}

	public void updateCompensable(CompensableArchive archive) {
		try {
			this.updateCompensable(archive, true);
		} catch (IOException error) {
			logger.error("Error occurred while updating compensable.", error);
			this.beanFactory.getCompensableManager().setRollbackOnlyQuietly();
		} catch (RuntimeException error) {
			logger.error("Error occurred while updating compensable.", error);
			this.beanFactory.getCompensableManager().setRollbackOnlyQuietly();
		}
	}

	private void updateCompensable(CompensableArchive archive, boolean redirect) throws IOException {
		TransactionXid xid = (TransactionXid) archive.getIdentifier();
		byte[] global = xid.getGlobalTransactionId();
		byte[] branch = xid.getBranchQualifier();

		MongoDatabase mdb = this.mongoClient.getDatabase(CONSTANTS_DB_NAME);
		MongoCollection<Document> collection = mdb.getCollection(CONSTANTS_TB_COMPENSABLES);

		Xid transactionXid = archive.getTransactionXid();
		Xid compensableXid = archive.getCompensableXid();

		Document variables = new Document();
		variables.append("tried", archive.isTried());
		variables.append("confirmed", archive.isConfirmed());
		variables.append("cancelled", archive.isCancelled());
		variables.append("modified", this.endpoint);
		variables.append("transaction_key", archive.getTransactionResourceKey());
		variables.append("compensable_key", archive.getCompensableResourceKey());
		variables.append("transaction_xid", String.valueOf(transactionXid));
		variables.append("compensable_xid", String.valueOf(compensableXid));

		Bson globalFilter = Filters.eq(CONSTANTS_FD_GLOBAL, ByteUtils.byteArrayToString(global));
		Bson branchFilter = Filters.eq(CONSTANTS_FD_BRANCH, ByteUtils.byteArrayToString(branch));
		UpdateResult result = collection.updateOne(Filters.and(globalFilter, branchFilter), new Document("$set", variables));

		if (result.getModifiedCount() == 0 && redirect) {
			this.createCompensable(archive, false);
		} else if (result.getModifiedCount() != 1) {
			throw new IllegalStateException(
					String.format("Error occurred while updating compensable(matched= %s, modified= %s).",
							result.getMatchedCount(), result.getModifiedCount()));
		}
	}

	@SuppressWarnings("unchecked")
	public void recover(TransactionRecoveryCallback callback) {
		XidFactory transactionXidFactory = this.beanFactory.getTransactionXidFactory();
		XidFactory compensableXidFactory = this.beanFactory.getCompensableXidFactory();
		ClassLoader cl = Thread.currentThread().getContextClassLoader();

		Map<Xid, TransactionArchive> archiveMap = new HashMap<Xid, TransactionArchive>();
		try {
			MongoDatabase mdb = this.mongoClient.getDatabase(CONSTANTS_DB_NAME);
			MongoCollection<Document> transactions = mdb.getCollection(CONSTANTS_TB_TRANSACTIONS);
			MongoCollection<Document> participants = mdb.getCollection(CONSTANTS_TB_PARTICIPANTS);
			MongoCollection<Document> compensables = mdb.getCollection(CONSTANTS_TB_COMPENSABLES);

			String[] values = this.endpoint.split("\\s*:\\s*");
			String application = values[1];
			Bson systemFilter = Filters.eq(CONSTANTS_FD_SYSTEM, application);
			Bson errorFilter = Filters.eq("error", true);

			FindIterable<Document> transactionItr = transactions.find(Filters.and(systemFilter, errorFilter));
			MongoCursor<Document> transactionCursor = null;
			try {
				transactionCursor = transactionItr.iterator();
				for (; transactionCursor.hasNext();) {
					Document document = transactionCursor.next();
					TransactionArchive archive = new TransactionArchive();

					String gxid = document.getString(CONSTANTS_FD_GLOBAL);
					byte[] globalTransactionId = ByteUtils.stringToByteArray(gxid);
					TransactionXid globalXid = compensableXidFactory.createGlobalXid(globalTransactionId);
					archive.setXid(globalXid);

					boolean propagated = document.getBoolean("propagated");
					String propagatedBy = document.getString("propagated_by");
					boolean compensable = document.getBoolean("compensable");
					boolean coordinator = document.getBoolean("coordinator");
					int compensableStatus = document.getInteger("status");

					String textVariables = document.getString("variables");
					byte[] variablesByteArray = ByteUtils.stringToByteArray(textVariables);
					Map<String, Serializable> variables = //
							(Map<String, Serializable>) SerializeUtils.deserializeObject(variablesByteArray);

					archive.setVariables(variables);

					archive.setCompensable(compensable);
					archive.setCoordinator(coordinator);
					archive.setCompensableStatus(compensableStatus);
					archive.setPropagated(propagated);
					archive.setPropagatedBy(propagatedBy);

					archiveMap.put(globalXid, archive);
				}
			} finally {
				IOUtils.closeQuietly(transactionCursor);
			}

			Bson filter = Filters.eq(CONSTANTS_FD_SYSTEM, application);
			FindIterable<Document> participantItr = participants.find(filter);
			MongoCursor<Document> participantCursor = null;
			try {
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

					TransactionArchive archive = archiveMap.get(globalXid);
					if (archive != null) {
						archive.getRemoteResources().add(participant);
					} // end-if (archive != null)
				}
			} finally {
				IOUtils.closeQuietly(participantCursor);
			}

			FindIterable<Document> compensableItr = compensables.find(filter);
			MongoCursor<Document> compensableCursor = null;
			try {
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

					Class<?> clazz = cl.loadClass(clazzName);
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

					TransactionArchive archive = archiveMap.get(globalXid);
					if (archive != null) {
						archive.getCompensableResourceList().add(compensable);
					} // end-if (archive != null)
				}
			} finally {
				IOUtils.closeQuietly(compensableCursor);
			}
		} catch (RuntimeException error) {
			logger.error("Error occurred while recovering transaction.", error);
		} catch (Exception error) {
			logger.error("Error occurred while recovering transaction.", error);
		}

		Iterator<Map.Entry<Xid, TransactionArchive>> itr = archiveMap.entrySet().iterator();
		for (; itr.hasNext();) {
			Map.Entry<Xid, TransactionArchive> entry = itr.next();
			TransactionArchive archive = entry.getValue();
			callback.recover(archive);
		}

	}

	public boolean isInitializeEnabled() {
		return initializeEnabled;
	}

	public void setInitializeEnabled(boolean initializeEnabled) {
		this.initializeEnabled = initializeEnabled;
	}

	public void setBeanFactory(CompensableBeanFactory tbf) {
		this.beanFactory = tbf;
	}

	public void setEndpoint(String identifier) {
		this.endpoint = identifier;
	}

}
