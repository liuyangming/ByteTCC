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
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.transaction.xa.Xid;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.bson.Document;
import org.bytesoft.bytetcc.supports.CompensableInvocationImpl;
import org.bytesoft.common.utils.ByteUtils;
import org.bytesoft.common.utils.CommonUtils;
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
import org.springframework.beans.factory.SmartInitializingSingleton;

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
		implements CompensableLogger, CompensableEndpointAware, CompensableBeanFactoryAware, SmartInitializingSingleton {
	static Logger logger = LoggerFactory.getLogger(MongoCompensableLogger.class);
	static final String CONSTANTS_TB_TRANSACTIONS = "compensables";
	static final String CONSTANTS_FD_GLOBAL = "gxid";
	static final String CONSTANTS_FD_BRANCH = "bxid";

	static final int MONGODB_ERROR_DUPLICATE_KEY = 11000;

	@javax.annotation.Resource
	private MongoClient mongoClient;
	private String endpoint;
	@javax.inject.Inject
	private CompensableInstVersionManager versionManager;
	@javax.inject.Inject
	private CompensableBeanFactory beanFactory;
	private volatile boolean initializeEnabled = true;

	public void createTransaction(TransactionArchive archive) {
		try {
			long version = this.versionManager.getInstanceVersion(this.endpoint);
			if (version <= 0) {
				throw new IllegalStateException(String.format("Invalid version(%s)!", this.endpoint));
			}

			String databaseName = CommonUtils.getApplication(this.endpoint).replaceAll("\\W", "_");
			MongoDatabase mdb = this.mongoClient.getDatabase(databaseName);
			MongoCollection<Document> collection = mdb.getCollection(CONSTANTS_TB_TRANSACTIONS);

			TransactionXid globalXid = (TransactionXid) archive.getXid();
			boolean compensable = archive.isCompensable();
			boolean coordinator = archive.isCoordinator();
			Object propagatedBy = archive.getPropagatedBy();
			boolean propagated = archive.isPropagated();

			byte[] globalByteArray = globalXid.getGlobalTransactionId();
			String identifier = ByteUtils.byteArrayToString(globalByteArray);
			String application = CommonUtils.getApplication(this.endpoint);

			Map<String, Serializable> variables = archive.getVariables();
			byte[] variablesByteArray = variables == null || variables.isEmpty() ? null
					: SerializeUtils.serializeObject((Serializable) variables);
			String textVariables = variablesByteArray == null || variablesByteArray.length == 0 ? null
					: ByteUtils.byteArrayToString(variablesByteArray);

			Document document = new Document();
			document.append(CONSTANTS_FD_GLOBAL, identifier);
			document.append("system", application);
			document.append("propagated", propagated);
			document.append("propagated_by", propagatedBy);
			document.append("compensable", compensable);
			document.append("coordinator", coordinator);
			document.append("version", version);
			document.append("status", archive.getCompensableStatus());
			document.append("created", this.endpoint);
			document.append("modified", this.endpoint);
			document.append("error", false);
			document.append("variables", textVariables);
			document.append("participants", this.constructParticipantsDocument(archive));
			document.append("compensables", this.constructCompensablesDocument(archive));
			document.append("recovered_at", archive.getRecoveredAt() == 0 ? null : new Date(archive.getRecoveredAt()));
			document.append("recovered_times", archive.getRecoveredTimes());

			collection.insertOne(document);
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
			String application = CommonUtils.getApplication(this.endpoint);
			String databaseName = application.replaceAll("\\W", "_");
			MongoDatabase mdb = this.mongoClient.getDatabase(databaseName);
			MongoCollection<Document> collection = mdb.getCollection(CONSTANTS_TB_TRANSACTIONS);

			TransactionXid globalXid = (TransactionXid) archive.getXid();
			byte[] global = globalXid.getGlobalTransactionId();
			String identifier = ByteUtils.byteArrayToString(global);

			Document document = new Document();

			Document target = new Document();
			Map<String, Serializable> variables = archive.getVariables();
			byte[] variablesByteArray = variables == null || variables.isEmpty() ? null
					: SerializeUtils.serializeObject((Serializable) variables);
			String textVariables = variablesByteArray == null || variablesByteArray.length == 0 ? null
					: ByteUtils.byteArrayToString(variablesByteArray);
			target.append("status", archive.getCompensableStatus());
			target.append("modified", this.endpoint);
			target.append("variables", textVariables);
			target.append("participants", this.constructParticipantsDocument(archive));
			target.append("compensables", this.constructCompensablesDocument(archive));
			target.append("recovered_at", archive.getRecoveredAt() == 0 ? null : new Date(archive.getRecoveredAt()));
			target.append("recovered_times", archive.getRecoveredTimes());

			document.append("$set", target);

			UpdateResult result = collection.updateOne(Filters.eq(CONSTANTS_FD_GLOBAL, identifier), document);
			if (result.getMatchedCount() != 1) {
				throw new IllegalStateException(
						String.format("Error occurred while updating transaction(matched= %s, modified= %s).",
								result.getMatchedCount(), result.getModifiedCount()));
			}
		} catch (IOException error) {
			logger.error("Error occurred while updating transaction.", error);
			this.beanFactory.getCompensableManager().setRollbackOnlyQuietly();
		} catch (RuntimeException error) {
			logger.error("Error occurred while updating transaction.", error);
			this.beanFactory.getCompensableManager().setRollbackOnlyQuietly();
		}
	}

	private Document constructParticipantsDocument(TransactionArchive archive) {
		String application = CommonUtils.getApplication(this.endpoint);

		List<XAResourceArchive> participantList = archive.getRemoteResources();
		Document participants = new Document();
		for (int i = 0; participantList != null && i < participantList.size(); i++) {
			XAResourceArchive resource = participantList.get(i);

			TransactionXid resourceXid = (TransactionXid) resource.getXid();
			byte[] globalByteArray = resourceXid.getGlobalTransactionId();
			byte[] branchByteArray = resourceXid.getBranchQualifier();
			String globalKey = ByteUtils.byteArrayToString(globalByteArray);
			String branchKey = ByteUtils.byteArrayToString(branchByteArray);

			XAResourceDescriptor descriptor = resource.getDescriptor();
			String descriptorType = descriptor.getClass().getName();
			String descriptorKey = descriptor.getIdentifier();

			int branchVote = resource.getVote();
			boolean readonly = resource.isReadonly();
			boolean committed = resource.isCommitted();
			boolean rolledback = resource.isRolledback();
			boolean completed = resource.isCompleted();
			boolean heuristic = resource.isHeuristic();

			Document participant = new Document();
			participant.append(CONSTANTS_FD_GLOBAL, globalKey);
			participant.append(CONSTANTS_FD_BRANCH, branchKey);

			participant.append("system", application);
			participant.append("type", descriptorType);
			participant.append("resource", descriptorKey);

			participant.append("vote", branchVote);
			participant.append("committed", committed);
			participant.append("rolledback", rolledback);
			participant.append("readonly", readonly);
			participant.append("completed", completed);
			participant.append("heuristic", heuristic);
			participant.append("modified", this.endpoint);

			participants.append(branchKey, participant);
		}

		return participants;
	}

	private Document constructCompensablesDocument(TransactionArchive archive) throws IOException {
		List<CompensableArchive> compensableList = archive.getCompensableResourceList();
		Document compensables = new Document();
		for (int i = 0; compensableList != null && i < compensableList.size(); i++) {
			CompensableArchive resource = compensableList.get(i);

			Xid resourceXid = resource.getIdentifier();
			byte[] globalByteArray = resourceXid.getGlobalTransactionId();
			byte[] branchByteArray = resourceXid.getBranchQualifier();
			String globalKey = ByteUtils.byteArrayToString(globalByteArray);
			String branchKey = ByteUtils.byteArrayToString(branchByteArray);

			CompensableInvocation invocation = resource.getCompensable();
			String beanId = (String) invocation.getIdentifier();

			Method method = invocation.getMethod();
			Object[] args = invocation.getArgs();

			String methodDesc = SerializeUtils.serializeMethod(invocation.getMethod());
			byte[] argsByteArray = SerializeUtils.serializeObject(args);
			String argsValue = ByteUtils.byteArrayToString(argsByteArray);

			Document service = new Document();
			service.append(CONSTANTS_FD_GLOBAL, globalKey);
			service.append(CONSTANTS_FD_BRANCH, branchKey);

			// service.append("system", application);
			// service.append("created", this.endpoint);

			service.append("transaction_key", resource.getTransactionResourceKey());
			service.append("compensable_key", resource.getCompensableResourceKey());

			Xid transactionXid = resource.getTransactionXid();
			Xid compensableXid = resource.getCompensableXid();

			service.append("transaction_xid", String.valueOf(transactionXid));
			service.append("compensable_xid", String.valueOf(compensableXid));

			service.append("coordinator", resource.isCoordinator());
			service.append("tried", resource.isTried());
			service.append("confirmed", resource.isConfirmed());
			service.append("cancelled", resource.isCancelled());
			service.append("modified", this.endpoint);

			service.append("serviceId", beanId);
			service.append("simplified", invocation.isSimplified());
			service.append("confirmable_key", invocation.getConfirmableKey());
			service.append("cancellable_key", invocation.getCancellableKey());
			service.append("args", argsValue);
			service.append("interface", method.getDeclaringClass().getName());
			service.append("method", methodDesc);

			compensables.put(branchKey, service);
		}

		return compensables;
	}

	public void deleteTransaction(TransactionArchive archive) {
		try {
			TransactionXid transactionXid = (TransactionXid) archive.getXid();
			byte[] global = transactionXid.getGlobalTransactionId();
			String identifier = ByteUtils.byteArrayToString(global);

			String application = CommonUtils.getApplication(this.endpoint);
			String databaseName = application.replaceAll("\\W", "_");
			MongoDatabase mdb = this.mongoClient.getDatabase(databaseName);
			MongoCollection<Document> transactions = mdb.getCollection(CONSTANTS_TB_TRANSACTIONS);

			DeleteResult result = transactions.deleteOne(Filters.eq(CONSTANTS_FD_GLOBAL, identifier));
			if (result.getDeletedCount() != 1) {
				logger.error("Error occurred while deleting transaction(deleted= {}).", result.getDeletedCount());
			}
		} catch (RuntimeException error) {
			logger.error("Error occurred while deleting transaction!", error);
		}
	}

	public void createParticipant(XAResourceArchive archive) {
		try {
			this.upsertParticipant(archive);
		} catch (RuntimeException error) {
			logger.error("Error occurred while creating participant!", error);
			this.beanFactory.getCompensableManager().setRollbackOnlyQuietly();
		}
	}

	public void updateParticipant(XAResourceArchive archive) {
		try {
			this.upsertParticipant(archive);
		} catch (RuntimeException error) {
			logger.error("Error occurred while updating participant.", error);
			this.beanFactory.getCompensableManager().setRollbackOnlyQuietly();
		}
	}

	private void upsertParticipant(XAResourceArchive archive) {
		TransactionXid transactionXid = (TransactionXid) archive.getXid();
		byte[] global = transactionXid.getGlobalTransactionId();
		byte[] branch = transactionXid.getBranchQualifier();
		String globalKey = ByteUtils.byteArrayToString(global);
		String branchKey = ByteUtils.byteArrayToString(branch);
		XAResourceDescriptor descriptor = archive.getDescriptor();
		String descriptorType = descriptor.getClass().getName();
		String descriptorKey = descriptor.getIdentifier();

		int branchVote = archive.getVote();
		boolean readonly = archive.isReadonly();
		boolean committed = archive.isCommitted();
		boolean rolledback = archive.isRolledback();
		boolean completed = archive.isCompleted();
		boolean heuristic = archive.isHeuristic();

		String application = CommonUtils.getApplication(this.endpoint);

		Document participant = new Document();
		participant.append(CONSTANTS_FD_GLOBAL, globalKey);
		participant.append(CONSTANTS_FD_BRANCH, branchKey);

		participant.append("type", descriptorType);
		participant.append("resource", descriptorKey);

		participant.append("vote", branchVote);
		participant.append("committed", committed);
		participant.append("rolledback", rolledback);
		participant.append("readonly", readonly);
		participant.append("completed", completed);
		participant.append("heuristic", heuristic);

		String databaseName = application.replaceAll("\\W", "_");
		MongoDatabase mdb = this.mongoClient.getDatabase(databaseName);
		MongoCollection<Document> collection = mdb.getCollection(CONSTANTS_TB_TRANSACTIONS);

		Document participants = new Document();
		participants.append(String.format("participants.%s", branchKey), participant);

		Document document = new Document();
		document.append("$set", participants);

		UpdateResult result = collection.updateOne(Filters.eq(CONSTANTS_FD_GLOBAL, globalKey), document);
		if (result.getMatchedCount() != 1) {
			throw new IllegalStateException(
					String.format("Error occurred while creating/updating participant(matched= %s, modified= %s).",
							result.getMatchedCount(), result.getModifiedCount()));
		}
	}

	public void deleteParticipant(XAResourceArchive archive) {
		try {
			TransactionXid transactionXid = (TransactionXid) archive.getXid();
			byte[] global = transactionXid.getGlobalTransactionId();
			byte[] branch = transactionXid.getBranchQualifier();
			String globalKey = ByteUtils.byteArrayToString(global);
			String branchKey = ByteUtils.byteArrayToString(branch);

			String application = CommonUtils.getApplication(this.endpoint);

			String databaseName = application.replaceAll("\\W", "_");
			MongoDatabase mdb = this.mongoClient.getDatabase(databaseName);
			MongoCollection<Document> collection = mdb.getCollection(CONSTANTS_TB_TRANSACTIONS);

			Document participants = new Document();
			participants.append(String.format("participants.%s", branchKey), null);

			Document document = new Document();
			document.append("$unset", participants);

			UpdateResult result = collection.updateOne(Filters.eq(CONSTANTS_FD_GLOBAL, globalKey), document);
			if (result.getMatchedCount() != 1) {
				throw new IllegalStateException(
						String.format("Error occurred while deleting participant(matched= %s, modified= %s).",
								result.getMatchedCount(), result.getModifiedCount()));
			}
		} catch (RuntimeException error) {
			logger.error("Error occurred while deleting participant.", error);
			this.beanFactory.getCompensableManager().setRollbackOnlyQuietly();
		}
	}

	public void createCompensable(CompensableArchive archive) {
		try {
			this.upsertCompensable(archive);
		} catch (IOException error) {
			logger.error("Error occurred while creating compensable.", error);
			this.beanFactory.getCompensableManager().setRollbackOnlyQuietly();
		} catch (RuntimeException error) {
			logger.error("Error occurred while creating compensable.", error);
			this.beanFactory.getCompensableManager().setRollbackOnlyQuietly();
		}
	}

	public void updateCompensable(CompensableArchive archive) {
		try {
			this.upsertCompensable(archive);
		} catch (IOException error) {
			logger.error("Error occurred while updating compensable.", error);
			this.beanFactory.getCompensableManager().setRollbackOnlyQuietly();
		} catch (RuntimeException error) {
			logger.error("Error occurred while updating compensable.", error);
			this.beanFactory.getCompensableManager().setRollbackOnlyQuietly();
		}
	}

	private void upsertCompensable(CompensableArchive archive) throws IOException {
		TransactionXid xid = (TransactionXid) archive.getIdentifier();
		byte[] global = xid.getGlobalTransactionId();
		byte[] branch = xid.getBranchQualifier();
		String globalKey = ByteUtils.byteArrayToString(global);
		String branchKey = ByteUtils.byteArrayToString(branch);
		CompensableInvocation invocation = archive.getCompensable();
		String beanId = (String) invocation.getIdentifier();

		Method method = invocation.getMethod();
		Object[] args = invocation.getArgs();

		String methodDesc = SerializeUtils.serializeMethod(invocation.getMethod());
		byte[] argsByteArray = SerializeUtils.serializeObject(args);
		String argsValue = ByteUtils.byteArrayToString(argsByteArray);

		String application = CommonUtils.getApplication(this.endpoint);

		Document compensable = new Document();
		compensable.append(CONSTANTS_FD_GLOBAL, globalKey);
		compensable.append(CONSTANTS_FD_BRANCH, branchKey);

		compensable.append("transaction_key", archive.getTransactionResourceKey());
		compensable.append("compensable_key", archive.getCompensableResourceKey());

		Xid transactionXid = archive.getTransactionXid();
		Xid compensableXid = archive.getCompensableXid();

		compensable.append("transaction_xid", String.valueOf(transactionXid));
		compensable.append("compensable_xid", String.valueOf(compensableXid));

		compensable.append("coordinator", archive.isCoordinator());
		compensable.append("tried", archive.isTried());
		compensable.append("confirmed", archive.isConfirmed());
		compensable.append("cancelled", archive.isCancelled());

		compensable.append("serviceId", beanId);
		compensable.append("simplified", invocation.isSimplified());
		compensable.append("confirmable_key", invocation.getConfirmableKey());
		compensable.append("cancellable_key", invocation.getCancellableKey());
		compensable.append("args", argsValue);
		compensable.append("interface", method.getDeclaringClass().getName());
		compensable.append("method", methodDesc);

		String databaseName = application.replaceAll("\\W", "_");
		MongoDatabase mdb = this.mongoClient.getDatabase(databaseName);
		MongoCollection<Document> collection = mdb.getCollection(CONSTANTS_TB_TRANSACTIONS);

		Document compensables = new Document();
		compensables.append(String.format("compensables.%s", branchKey), compensable);

		Document document = new Document();
		document.append("$set", compensables);

		UpdateResult result = collection.updateOne(Filters.eq(CONSTANTS_FD_GLOBAL, globalKey), document);
		if (result.getMatchedCount() != 1) {
			throw new IllegalStateException(
					String.format("Error occurred while creating/updating compensable(matched= %s, modified= %s).",
							result.getMatchedCount(), result.getModifiedCount()));
		}
	}

	public void recover(TransactionRecoveryCallback callback) {
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

				String targetApplication = document.getString("system");
				long expectVersion = document.getLong("version");
				long actualVersion = this.versionManager.getInstanceVersion(targetApplication);

				if (error == false && actualVersion > 0 && actualVersion <= expectVersion) {
					continue; // ignore
				}

				callback.recover(this.reconstructTransactionArchive(document));
			}
		} catch (RuntimeException error) {
			logger.error("Error occurred while recovering transaction.", error);
		} catch (Exception error) {
			logger.error("Error occurred while recovering transaction.", error);
		} finally {
			IOUtils.closeQuietly(transactionCursor);
		}
	}

	@SuppressWarnings("unchecked")
	public TransactionArchive reconstructTransactionArchive(Document document) throws Exception {
		XidFactory compensableXidFactory = this.beanFactory.getCompensableXidFactory();

		boolean propagated = document.getBoolean("propagated");
		String propagatedBy = document.getString("propagated_by");
		boolean compensable = document.getBoolean("compensable");
		boolean coordinator = document.getBoolean("coordinator");
		int compensableStatus = document.getInteger("status");
		// boolean error = document.getBoolean("error");
		Integer recoveredTimes = document.getInteger("recovered_times");
		Date recoveredAt = document.getDate("recovered_at");

		TransactionArchive archive = new TransactionArchive();

		String global = document.getString(CONSTANTS_FD_GLOBAL);
		byte[] globalByteArray = ByteUtils.stringToByteArray(global);
		TransactionXid globalXid = compensableXidFactory.createGlobalXid(globalByteArray);
		archive.setXid(globalXid);

		String textVariables = document.getString("variables");
		byte[] variablesByteArray = null;
		if (StringUtils.isNotBlank(textVariables) && StringUtils.equals(textVariables, "null") == false) {
			variablesByteArray = ByteUtils.stringToByteArray(textVariables);
		}

		if (variablesByteArray == null || variablesByteArray.length == 0) {
			archive.setVariables(new HashMap<String, Serializable>());
		} else {
			Map<String, Serializable> variables = //
					(Map<String, Serializable>) SerializeUtils.deserializeObject(variablesByteArray);
			archive.setVariables(variables);
		}

		archive.setRecoveredAt(recoveredAt == null ? 0 : recoveredAt.getTime());
		archive.setRecoveredTimes(recoveredTimes == null ? 0 : recoveredTimes);

		archive.setCompensable(compensable);
		archive.setCoordinator(coordinator);
		archive.setCompensableStatus(compensableStatus);
		archive.setPropagated(propagated);
		archive.setPropagatedBy(propagatedBy);

		archive.getRemoteResources().addAll(this.constructParticipantList(document));
		archive.getCompensableResourceList().addAll(this.constructCompensableList(document));

		return archive;
	}

	private List<XAResourceArchive> constructParticipantList(Document document) {
		XidFactory compensableXidFactory = this.beanFactory.getCompensableXidFactory();

		List<XAResourceArchive> resourceList = new ArrayList<XAResourceArchive>();
		Document participants = document.get("participants", Document.class);
		for (Iterator<String> itr = participants.keySet().iterator(); itr.hasNext();) {
			String key = itr.next();
			Document element = participants.get(key, Document.class);

			XAResourceArchive participant = new XAResourceArchive();

			String gxid = element.getString(CONSTANTS_FD_GLOBAL);
			String bxid = element.getString(CONSTANTS_FD_BRANCH);

			String descriptorType = element.getString("type");
			String identifier = element.getString("resource");

			int vote = element.getInteger("vote");
			boolean committed = element.getBoolean("committed");
			boolean rolledback = element.getBoolean("rolledback");
			boolean readonly = element.getBoolean("readonly");
			boolean completed = element.getBoolean("completed");
			boolean heuristic = element.getBoolean("heuristic");

			byte[] globalTransactionId = ByteUtils.stringToByteArray(gxid);
			byte[] branchQualifier = ByteUtils.stringToByteArray(bxid);
			TransactionXid globalId = compensableXidFactory.createGlobalXid(globalTransactionId);
			TransactionXid branchId = compensableXidFactory.createBranchXid(globalId, branchQualifier);
			participant.setXid(branchId);

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

			resourceList.add(participant);
		}

		return resourceList;
	}

	private List<CompensableArchive> constructCompensableList(Document document) throws Exception {
		XidFactory transactionXidFactory = this.beanFactory.getTransactionXidFactory();
		ClassLoader cl = Thread.currentThread().getContextClassLoader();

		List<CompensableArchive> resourceList = new ArrayList<CompensableArchive>();

		Document compensables = document.get("compensables", Document.class);
		for (Iterator<String> itr = compensables.keySet().iterator(); itr.hasNext();) {
			String key = itr.next();
			Document element = compensables.get(key, Document.class);
			CompensableArchive service = new CompensableArchive();

			String gxid = element.getString(CONSTANTS_FD_GLOBAL);
			String bxid = element.getString(CONSTANTS_FD_BRANCH);

			boolean coordinatorFlag = element.getBoolean("coordinator");
			boolean tried = element.getBoolean("tried");
			boolean confirmed = element.getBoolean("confirmed");
			boolean cancelled = element.getBoolean("cancelled");
			String serviceId = element.getString("serviceId");
			boolean simplified = element.getBoolean("simplified");
			String confirmableKey = element.getString("confirmable_key");
			String cancellableKey = element.getString("cancellable_key");
			String argsValue = element.getString("args");
			String clazzName = element.getString("interface");
			String methodDesc = element.getString("method");

			String transactionKey = element.getString("transaction_key");
			String compensableKey = element.getString("compensable_key");

			String transactionXid = element.getString("transaction_xid");
			String compensableXid = element.getString("compensable_xid");

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

			service.setCompensable(invocation);

			service.setConfirmed(confirmed);
			service.setCancelled(cancelled);
			service.setTried(tried);
			service.setCoordinator(coordinatorFlag);

			service.setTransactionResourceKey(transactionKey);
			service.setCompensableResourceKey(compensableKey);

			String[] transactionArray = transactionXid.split("\\s*\\-\\s*");
			if (transactionArray.length == 3) {
				String transactionGlobalId = transactionArray[1];
				String transactionBranchId = transactionArray[2];
				TransactionXid transactionGlobalXid = transactionXidFactory
						.createGlobalXid(ByteUtils.stringToByteArray(transactionGlobalId));
				if (StringUtils.isNotBlank(transactionBranchId)) {
					TransactionXid transactionBranchXid = transactionXidFactory.createBranchXid(transactionGlobalXid,
							ByteUtils.stringToByteArray(transactionBranchId));
					service.setTransactionXid(transactionBranchXid);
				} else {
					service.setTransactionXid(transactionGlobalXid);
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
					service.setCompensableXid(compensableBranchXid);
				} else {
					service.setCompensableXid(compensableGlobalXid);
				}
			}

			byte[] globalTransactionId = ByteUtils.stringToByteArray(gxid);
			byte[] branchQualifier = ByteUtils.stringToByteArray(bxid);
			TransactionXid globalId = transactionXidFactory.createGlobalXid(globalTransactionId);
			TransactionXid branchId = transactionXidFactory.createBranchXid(globalId, branchQualifier);
			service.setIdentifier(branchId);

			resourceList.add(service);
		}

		return resourceList;
	}

	public void afterSingletonsInstantiated() {
		try {
			this.afterPropertiesSet();
		} catch (Exception error) {
			throw new RuntimeException(error);
		}
	}

	public void afterPropertiesSet() throws Exception {
		if (this.initializeEnabled) {
			this.createTransactionsGlobalTxKeyIndexIfNecessary();
		}
	}

	private void createTransactionsGlobalTxKeyIndexIfNecessary() {
		String databaseName = CommonUtils.getApplication(this.endpoint).replaceAll("\\W", "_");
		MongoDatabase database = this.mongoClient.getDatabase(databaseName);
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
				boolean lengthEquals = key.size() == 1;
				transactionIndexExists = lengthEquals && globalExists;

				if (transactionIndexExists && (unique == null || unique == false)) {
					throw new IllegalStateException();
				}
			}
		} finally {
			IOUtils.closeQuietly(transactionCursor);
		}

		if (transactionIndexExists == false) {
			Document index = new Document(CONSTANTS_FD_GLOBAL, 1);
			transactions.createIndex(index, new IndexOptions().unique(true));
		}
	}

	public boolean isInitializeEnabled() {
		return initializeEnabled;
	}

	public void setInitializeEnabled(boolean initializeEnabled) {
		this.initializeEnabled = initializeEnabled;
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

}
