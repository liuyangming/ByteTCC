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
import java.util.List;
import java.util.Map;

import javax.transaction.xa.XAException;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bytesoft.bytetcc.supports.CompensableInvocationImpl;
import org.bytesoft.common.utils.ByteUtils;
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

import com.mongodb.BasicDBObject;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.result.UpdateResult;

public class MongoCompensableRepository
		implements TransactionRepository, CompensableEndpointAware, CompensableBeanFactoryAware {
	static Logger logger = LoggerFactory.getLogger(MongoCompensableRepository.class);
	static final String CONSTANTS_DB_NAME = "bytetcc";
	static final String CONSTANTS_TB_TRANSACTIONS = "transactions";
	static final String CONSTANTS_TB_PARTICIPANTS = "participants";
	static final String CONSTANTS_TB_COMPENSABLES = "compensables";
	static final String CONSTANTS_FD_GLOBAL = "gxid";
	static final String CONSTANTS_FD_BRANCH = "bxid";
	static final String CONSTANTS_FD_SYSTEM = "system";

	@javax.annotation.Resource
	private MongoClient mongoClient;
	private String endpoint;
	@javax.inject.Inject
	private CompensableBeanFactory beanFactory;

	public void putTransaction(TransactionXid xid, Transaction transaction) {
	}

	@SuppressWarnings("unchecked")
	public Transaction getTransaction(TransactionXid xid) throws TransactionException {
		TransactionRecovery compensableRecovery = this.beanFactory.getCompensableRecovery();
		XidFactory compensableXidFactory = this.beanFactory.getCompensableXidFactory();

		MongoCursor<Document> transactionCursor = null;
		try {
			MongoDatabase mdb = this.mongoClient.getDatabase(CONSTANTS_DB_NAME);
			MongoCollection<Document> transactions = mdb.getCollection(CONSTANTS_TB_TRANSACTIONS);

			String[] values = this.endpoint.split("\\s*:\\s*");
			String application = values[1];
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
		String[] values = this.endpoint.split("\\s*:\\s*");
		String application = values[1];
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
		String[] values = this.endpoint.split("\\s*:\\s*");
		String application = values[1];
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
		return null;
	}

	public void putErrorTransaction(TransactionXid transactionXid, Transaction transaction) {
		try {
			TransactionArchive archive = (TransactionArchive) transaction.getTransactionArchive();
			byte[] global = transactionXid.getGlobalTransactionId();
			String identifier = ByteUtils.byteArrayToString(global);

			String[] values = this.endpoint.split("\\s*:\\s*");
			String application = values[1];

			int status = archive.getCompensableStatus();

			MongoDatabase mdb = this.mongoClient.getDatabase(CONSTANTS_DB_NAME);
			MongoCollection<Document> collection = mdb.getCollection(CONSTANTS_TB_TRANSACTIONS);

			Document target = new Document();
			target.append("modified", this.endpoint);
			target.append("status", status);
			target.append("error", true);

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
		} catch (RuntimeException error) {
			logger.error("Error occurred while setting the error flag.", error);
		}
	}

	@SuppressWarnings("unchecked")
	public Transaction getErrorTransaction(TransactionXid xid) throws TransactionException {
		TransactionRecovery compensableRecovery = this.beanFactory.getTransactionRecovery();
		XidFactory compensableXidFactory = this.beanFactory.getCompensableXidFactory();

		MongoCursor<Document> transactionCursor = null;
		try {
			MongoDatabase mdb = this.mongoClient.getDatabase(CONSTANTS_DB_NAME);
			MongoCollection<Document> transactions = mdb.getCollection(CONSTANTS_TB_TRANSACTIONS);

			String[] values = this.endpoint.split("\\s*:\\s*");
			String application = values[1];
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
		TransactionRecovery compensableRecovery = this.beanFactory.getTransactionRecovery();
		XidFactory compensableXidFactory = this.beanFactory.getCompensableXidFactory();

		List<Transaction> transactionList = new ArrayList<Transaction>();

		MongoCursor<Document> transactionCursor = null;
		try {
			MongoDatabase mdb = this.mongoClient.getDatabase(CONSTANTS_DB_NAME);
			MongoCollection<Document> transactions = mdb.getCollection(CONSTANTS_TB_TRANSACTIONS);

			String[] values = this.endpoint.split("\\s*:\\s*");
			String application = values[1];

			Bson systemFilter = Filters.eq(CONSTANTS_FD_SYSTEM, application);
			Bson errorFilter = Filters.eq("error", true);
			Bson coordinatorFilter = Filters.eq("coordinator", true);

			FindIterable<Document> transactionItr = //
					transactions.find(Filters.and(systemFilter, errorFilter, coordinatorFilter));
			for (transactionCursor = transactionItr.iterator(); transactionCursor.hasNext();) {
				Document document = transactionCursor.next();
				TransactionArchive archive = new TransactionArchive();

				String global = document.getString(CONSTANTS_FD_GLOBAL);
				boolean propagated = document.getBoolean("propagated");
				String propagatedBy = document.getString("propagated_by");
				boolean compensable = document.getBoolean("compensable");
				boolean coordinator = document.getBoolean("coordinator");
				int compensableStatus = document.getInteger("status");
				String textVariables = document.getString("variables");

				TransactionXid globalXid = compensableXidFactory.createGlobalXid(global.getBytes());
				archive.setXid(globalXid);

				byte[] variablesByteArray = ByteUtils.stringToByteArray(textVariables);
				Map<String, Serializable> variables = //
						(Map<String, Serializable>) SerializeUtils.deserializeObject(variablesByteArray);

				archive.setVariables(variables);

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

	public List<Transaction> getActiveTransactionList() {
		return null;
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
