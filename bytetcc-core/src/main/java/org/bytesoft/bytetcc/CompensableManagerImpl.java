/**
 * Copyright 2014-2016 yangming.liu<bytefox@126.com>.
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
package org.bytesoft.bytetcc;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.transaction.HeuristicMixedException;
import javax.transaction.HeuristicRollbackException;
import javax.transaction.InvalidTransactionException;
import javax.transaction.NotSupportedException;
import javax.transaction.RollbackException;
import javax.transaction.Status;
import javax.transaction.SystemException;
import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;

import org.bytesoft.bytetcc.supports.CompensableSynchronization;
import org.bytesoft.common.utils.ByteUtils;
import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.CompensableManager;
import org.bytesoft.compensable.CompensableTransaction;
import org.bytesoft.compensable.TransactionContext;
import org.bytesoft.compensable.archive.CompensableArchive;
import org.bytesoft.compensable.aware.CompensableBeanFactoryAware;
import org.bytesoft.compensable.aware.CompensableEndpointAware;
import org.bytesoft.compensable.logging.CompensableLogger;
import org.bytesoft.transaction.Transaction;
import org.bytesoft.transaction.TransactionLock;
import org.bytesoft.transaction.TransactionManager;
import org.bytesoft.transaction.TransactionParticipant;
import org.bytesoft.transaction.TransactionRepository;
import org.bytesoft.transaction.remote.RemoteCoordinator;
import org.bytesoft.transaction.xa.TransactionXid;
import org.bytesoft.transaction.xa.XidFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CompensableManagerImpl implements CompensableManager, CompensableBeanFactoryAware, CompensableEndpointAware {
	static final Logger logger = LoggerFactory.getLogger(CompensableManagerImpl.class);

	@javax.inject.Inject
	private CompensableBeanFactory beanFactory;
	private String endpoint;
	private transient boolean statefully;

	private final Map<Thread, Transaction> thread2txMap = new ConcurrentHashMap<Thread, Transaction>();
	private final Map<Xid, Transaction> xid2txMap = new ConcurrentHashMap<Xid, Transaction>();

	public void associateThread(Transaction transaction) {
		TransactionContext transactionContext = (TransactionContext) transaction.getTransactionContext();
		TransactionXid transactionXid = transactionContext.getXid();
		this.xid2txMap.put(transactionXid, (CompensableTransaction) transaction);
		this.thread2txMap.put(Thread.currentThread(), (CompensableTransaction) transaction);
	}

	public CompensableTransaction desociateThread() {
		CompensableTransaction transaction = (CompensableTransaction) this.thread2txMap.remove(Thread.currentThread());
		if (transaction == null) {
			return null;
		}

		TransactionContext transactionContext = (TransactionContext) transaction.getTransactionContext();
		this.xid2txMap.remove(transactionContext.getXid());
		return transaction;
	}

	public int getStatus() throws SystemException {
		Transaction transaction = this.getTransactionQuietly();
		return transaction == null ? Status.STATUS_NO_TRANSACTION : transaction.getStatus();
	}

	public Transaction getTransaction(Xid transactionXid) {
		return this.xid2txMap.get(transactionXid);
	}

	public Transaction getTransaction(Thread thread) {
		CompensableTransaction transaction = this.getCompensableTransaction(thread);
		return transaction == null ? null : transaction.getTransaction();
	}

	public Transaction getTransactionQuietly() {
		CompensableTransaction transaction = this.getCompensableTransactionQuietly();
		return transaction == null ? null : transaction.getTransaction();
	}

	public Transaction getTransaction() throws SystemException {
		CompensableTransaction transaction = this.getCompensableTransactionQuietly();
		return transaction == null ? null : transaction.getTransaction();
	}

	public CompensableTransaction getCompensableTransactionQuietly() {
		return (CompensableTransaction) this.thread2txMap.get(Thread.currentThread());
	}

	public CompensableTransaction getCompensableTransaction(Thread thread) {
		return (CompensableTransaction) this.thread2txMap.get(thread);
	}

	public void resume(javax.transaction.Transaction tobj)
			throws InvalidTransactionException, IllegalStateException, SystemException {
		if (Transaction.class.isInstance(tobj)) {
			TransactionManager transactionManager = this.beanFactory.getTransactionManager();
			Transaction transaction = (Transaction) tobj;
			CompensableTransaction compensable = (CompensableTransaction) transaction.getTransactionalExtra();

			compensable.setTransactionalExtra(transaction);
			compensable.resume();

			TransactionContext compensableContext = compensable.getTransactionContext();
			compensableContext.setPropagationLevel(compensableContext.getPropagationLevel() - 1);

			transactionManager.resume(transaction);
		} else {
			throw new InvalidTransactionException();
		}
	}

	public Transaction suspend() throws SystemException {
		CompensableTransaction compensable = (CompensableTransaction) this.thread2txMap.get(Thread.currentThread());
		if (compensable == null) {
			throw new SystemException(XAException.XAER_NOTA);
		}

		TransactionManager transactionManager = this.beanFactory.getTransactionManager();
		Transaction transaction = transactionManager.suspend();

		TransactionContext compensableContext = compensable.getTransactionContext();
		compensableContext.setPropagationLevel(compensableContext.getPropagationLevel() + 1);

		compensable.suspend();
		compensable.setTransactionalExtra(null);

		return transaction;
	}

	public void begin() throws NotSupportedException, SystemException {
		XidFactory transactionXidFactory = this.beanFactory.getTransactionXidFactory();

		CompensableTransaction compensable = this.getCompensableTransactionQuietly();
		if (compensable == null || compensable.getTransaction() != null) {
			throw new SystemException(XAException.XAER_PROTO);
		}

		CompensableArchive archive = compensable.getCompensableArchive();

		// The current confirm/cancel operation has been assigned an xid.
		TransactionXid compensableXid = archive == null ? null : (TransactionXid) archive.getCompensableXid();
		TransactionXid transactionXid = compensableXid != null //
				? transactionXidFactory.createGlobalXid(compensableXid.getGlobalTransactionId())
				: transactionXidFactory.createGlobalXid();

		TransactionContext compensableContext = compensable.getTransactionContext();
		TransactionContext transactionContext = compensableContext.clone();
		transactionContext.setXid(transactionXid);

		this.invokeBegin(transactionContext, false);
	}

	protected void invokeBegin(TransactionContext transactionContext, boolean createFlag)
			throws NotSupportedException, SystemException {
		TransactionParticipant transactionCoordinator = this.beanFactory.getTransactionNativeParticipant();

		CompensableTransaction compensable = this.getCompensableTransactionQuietly();
		TransactionContext compensableContext = compensable.getTransactionContext();

		TransactionXid compensableXid = compensableContext.getXid();
		TransactionXid transactionXid = transactionContext.getXid();
		try {
			Transaction transaction = transactionCoordinator.start(transactionContext, XAResource.TMNOFLAGS);
			transaction.setTransactionalExtra(compensable);
			compensable.setTransactionalExtra(transaction);

			transaction.registerTransactionResourceListener(compensable);
			transaction.registerTransactionListener(compensable);

			CompensableSynchronization synchronization = this.beanFactory.getCompensableSynchronization();
			synchronization.afterBegin(compensable.getTransaction(), createFlag);
		} catch (XAException tex) {
			logger.info("{}> begin-transaction: error occurred while starting jta-transaction: {}",
					ByteUtils.byteArrayToString(compensableXid.getGlobalTransactionId()),
					ByteUtils.byteArrayToString(transactionXid.getGlobalTransactionId()), tex);
			try {
				transactionCoordinator.end(transactionContext, XAResource.TMFAIL);
				throw new SystemException("Error occurred while beginning a compensable-transaction!");
			} catch (XAException ignore) {
				throw new SystemException("Error occurred while beginning a compensable-transaction!");
			}
		}
	}

	protected void invokeRollbackInBegin(TransactionContext transactionContext) throws NotSupportedException, SystemException {
		TransactionParticipant transactionCoordinator = this.beanFactory.getTransactionNativeParticipant();

		CompensableTransaction compensable = this.getCompensableTransactionQuietly();
		TransactionContext compensableContext = compensable.getTransactionContext();

		TransactionXid compensableXid = compensableContext.getXid();
		TransactionXid transactionXid = transactionContext.getXid();
		try {
			transactionCoordinator.end(transactionContext, XAResource.TMFAIL);
			transactionCoordinator.rollback(transactionXid);
		} catch (XAException tex) {
			logger.info("{}> begin-transaction: error occurred while starting jta-transaction: {}",
					ByteUtils.byteArrayToString(compensableXid.getGlobalTransactionId()),
					ByteUtils.byteArrayToString(transactionXid.getGlobalTransactionId()), tex);
		}
	}

	public void compensableBegin() throws NotSupportedException, SystemException {
		if (this.getCompensableTransactionQuietly() != null) {
			throw new NotSupportedException();
		}

		CompensableLogger compensableLogger = this.beanFactory.getCompensableLogger();
		TransactionLock compensableLock = this.beanFactory.getCompensableLock();
		TransactionRepository compensableRepository = this.beanFactory.getCompensableRepository();
		RemoteCoordinator compensableCoordinator = (RemoteCoordinator) this.beanFactory.getCompensableNativeParticipant();

		XidFactory transactionXidFactory = this.beanFactory.getTransactionXidFactory();
		XidFactory compensableXidFactory = this.beanFactory.getCompensableXidFactory();

		TransactionXid compensableXid = compensableXidFactory.createGlobalXid();
		TransactionXid transactionXid = transactionXidFactory.createGlobalXid(compensableXid.getGlobalTransactionId());

		TransactionContext compensableContext = new TransactionContext();
		compensableContext.setCoordinator(true);
		compensableContext.setCompensable(true);
		compensableContext.setStatefully(this.statefully);
		compensableContext.setXid(compensableXid);
		compensableContext.setPropagatedBy(compensableCoordinator.getIdentifier());
		CompensableTransactionImpl compensable = new CompensableTransactionImpl(compensableContext);
		compensable.setBeanFactory(this.beanFactory);

		this.associateThread(compensable);
		logger.info("{}| compensable transaction begin!", ByteUtils.byteArrayToString(compensableXid.getGlobalTransactionId()));

		TransactionContext transactionContext = new TransactionContext();
		transactionContext.setXid(transactionXid);

		boolean failure = true;
		try {
			this.invokeBegin(transactionContext, true);
			failure = false;
		} finally {
			if (failure) {
				logger.info("{}| compensable transaction failed!",
						ByteUtils.byteArrayToString(compensableXid.getGlobalTransactionId()));
				this.desociateThread();
			}
		}

		compensableRepository.putTransaction(compensableXid, compensable);

		compensableLogger.createTransaction(compensable.getTransactionArchive());
		boolean locked = compensableLock.lockTransaction(compensableXid, this.endpoint);
		if (locked == false) {
			this.invokeRollbackInBegin(transactionContext);

			compensableLogger.deleteTransaction(compensable.getTransactionArchive());
			this.desociateThread();
			compensableRepository.removeTransaction(compensableXid);
			logger.info("{}| compensable transaction failed!",
					ByteUtils.byteArrayToString(compensableXid.getGlobalTransactionId()));

			throw new SystemException(XAException.XAER_PROTO); // should never happen
		}

	}

	public void commit() throws RollbackException, HeuristicMixedException, HeuristicRollbackException, SecurityException,
			IllegalStateException, SystemException {

		CompensableTransaction transaction = this.getCompensableTransactionQuietly();
		if (transaction == null) {
			throw new IllegalStateException();
		}
		TransactionContext transactionContext = transaction.getTransactionContext();
		boolean coordinator = transactionContext.isCoordinator();
		boolean propagated = transactionContext.isPropagated();
		boolean compensable = transactionContext.isCompensable();
		boolean compensating = transactionContext.isCompensating();
		int propagatedLevel = transactionContext.getPropagationLevel();

		if (compensable == false) {
			throw new IllegalStateException();
		} else if (compensating) {
			this.invokeTransactionCommitIfNecessary(transaction);
		} else if (coordinator) {
			if (propagated) {
				this.invokeTransactionCommitIfNecessary(transaction);
			} else if (propagatedLevel > 0) {
				this.invokeTransactionCommitIfNecessary(transaction);
			} else {
				throw new IllegalStateException();
			}
		} else {
			this.invokeTransactionCommitIfNecessary(transaction);
		}
	}

	protected void invokeTransactionCommitIfNecessary(CompensableTransaction compensable) throws RollbackException,
			HeuristicMixedException, HeuristicRollbackException, SecurityException, IllegalStateException, SystemException {
		// compensable.getTransaction().isMarkedRollbackOnly()
		if (compensable.getTransaction().getTransactionStatus() == Status.STATUS_MARKED_ROLLBACK) {
			this.invokeTransactionRollback(compensable);
			throw new HeuristicRollbackException();
		} else {
			this.invokeTransactionCommit(compensable);
		}
	}

	protected void invokeTransactionCommit(CompensableTransaction compensable) throws RollbackException,
			HeuristicMixedException, HeuristicRollbackException, SecurityException, IllegalStateException, SystemException {
		Transaction transaction = compensable.getTransaction();
		boolean isLocalTransaction = transaction.isLocalTransaction();
		try {
			if (isLocalTransaction) {
				this.invokeTransactionCommitIfLocalTransaction(compensable);
			} else {
				this.invokeTransactionCommitIfNotLocalTransaction(compensable);
			}
		} finally {
			compensable.setTransactionalExtra(null);
		}
	}

	protected void invokeTransactionCommitIfLocalTransaction(CompensableTransaction compensable) throws RollbackException,
			HeuristicMixedException, HeuristicRollbackException, SecurityException, IllegalStateException, SystemException {

		Transaction transaction = compensable.getTransaction();
		org.bytesoft.transaction.TransactionContext transactionContext = transaction.getTransactionContext();
		TransactionParticipant transactionCoordinator = this.beanFactory.getTransactionNativeParticipant();

		TransactionXid transactionXid = transactionContext.getXid();
		try {
			transactionCoordinator.end(transactionContext, XAResource.TMSUCCESS);
			transactionCoordinator.commit(transactionXid, true);
		} catch (XAException xaEx) {
			switch (xaEx.errorCode) {
			case XAException.XA_HEURCOM:
				transactionCoordinator.forgetQuietly(transactionXid);
				break;
			case XAException.XA_HEURRB:
				transactionCoordinator.forgetQuietly(transactionXid);
				HeuristicRollbackException hrex = new HeuristicRollbackException();
				hrex.initCause(xaEx);
				throw hrex;
			case XAException.XA_HEURMIX:
				transactionCoordinator.forgetQuietly(transactionXid);
				HeuristicMixedException hmex = new HeuristicMixedException();
				hmex.initCause(xaEx);
				throw hmex;
			case XAException.XAER_RMERR:
			default:
				transactionCoordinator.forgetQuietly(transactionXid); // TODO
				SystemException sysEx = new SystemException(xaEx.errorCode);
				sysEx.initCause(xaEx);
				throw sysEx;
			}
		}

	}

	protected void invokeTransactionCommitIfNotLocalTransaction(CompensableTransaction compensable) throws RollbackException,
			HeuristicMixedException, HeuristicRollbackException, SecurityException, IllegalStateException, SystemException {

		Transaction transaction = compensable.getTransaction();
		org.bytesoft.transaction.TransactionContext transactionContext = transaction.getTransactionContext();
		TransactionParticipant transactionCoordinator = this.beanFactory.getTransactionNativeParticipant();

		TransactionXid transactionXid = transactionContext.getXid();
		try {
			transactionCoordinator.end(transactionContext, XAResource.TMSUCCESS);

			TransactionContext compensableContext = compensable.getTransactionContext();
			logger.error("{}> jta-transaction in try-phase cannot be xa transaction.",
					ByteUtils.byteArrayToString(compensableContext.getXid().getGlobalTransactionId()));

			transactionCoordinator.rollback(transactionXid);
			throw new HeuristicRollbackException();
		} catch (XAException xaEx) {
			transactionCoordinator.forgetQuietly(transactionXid);
			SystemException sysEx = new SystemException(xaEx.errorCode);
			sysEx.initCause(xaEx);
			throw sysEx;
		}
	}

	public void fireCompensableCommit(CompensableTransaction transaction) throws RollbackException, HeuristicMixedException,
			HeuristicRollbackException, SecurityException, IllegalStateException, SystemException {
		try {
			this.associateThread(transaction);

			transaction.commit();
		} finally {
			this.desociateThread();
		}
	}

	public void rollback() throws IllegalStateException, SecurityException, SystemException {

		CompensableTransaction transaction = this.getCompensableTransactionQuietly();
		if (transaction == null) {
			throw new IllegalStateException();
		}
		TransactionContext transactionContext = transaction.getTransactionContext();
		boolean coordinator = transactionContext.isCoordinator();
		boolean propagated = transactionContext.isPropagated();
		boolean compensable = transactionContext.isCompensable();
		boolean compensating = transactionContext.isCompensating();
		int propagatedLevel = transactionContext.getPropagationLevel();

		if (compensable == false) {
			throw new IllegalStateException();
		} else if (compensating) {
			this.invokeTransactionRollback(transaction);
		} else if (coordinator) {
			if (propagated) {
				this.invokeTransactionRollback(transaction);
			} else if (propagatedLevel > 0) {
				this.invokeTransactionRollback(transaction);
			} else {
				throw new IllegalStateException();
			}
		} else {
			this.invokeTransactionRollback(transaction);
		}

	}

	protected void invokeTransactionRollback(CompensableTransaction compensable)
			throws IllegalStateException, SecurityException, SystemException {

		Transaction transaction = compensable.getTransaction();
		org.bytesoft.transaction.TransactionContext transactionContext = transaction.getTransactionContext();
		TransactionParticipant transactionCoordinator = this.beanFactory.getTransactionNativeParticipant();

		TransactionXid transactionXid = transactionContext.getXid();
		try {
			transactionCoordinator.end(transactionContext, XAResource.TMSUCCESS);
			transactionCoordinator.rollback(transactionXid);
		} catch (XAException xaEx) {
			transactionCoordinator.forgetQuietly(transactionXid);
			SystemException sysEx = new SystemException(xaEx.errorCode);
			sysEx.initCause(xaEx);
			throw sysEx;
		} finally {
			compensable.setTransactionalExtra(null);
		}

	}

	public void fireCompensableRollback(CompensableTransaction transaction)
			throws IllegalStateException, SecurityException, SystemException {
		try {
			this.associateThread(transaction);

			transaction.rollback();
		} finally {
			this.desociateThread();
		}
	}

	public void compensableCommit() throws RollbackException, HeuristicMixedException, HeuristicRollbackException,
			SecurityException, IllegalStateException, SystemException {
		CompensableTransaction transaction = this.getCompensableTransactionQuietly();
		if (transaction == null) {
			throw new IllegalStateException();
		} else if (transaction.getTransaction() == null) {
			throw new IllegalStateException();
		}

		TransactionContext transactionContext = transaction.getTransactionContext();
		boolean coordinator = transactionContext.isCoordinator();
		boolean compensable = transactionContext.isCompensable();
		boolean compensating = transactionContext.isCompensating();

		if (compensable == false) {
			throw new IllegalStateException();
		} else if (coordinator == false) {
			throw new IllegalStateException();
		} else if (compensating) {
			throw new IllegalStateException();
		} else if (transactionContext.isRollbackOnly()) {
			this.compensableRollback();
			throw new HeuristicRollbackException();
		}

		TransactionLock compensableLock = this.beanFactory.getCompensableLock();
		TransactionXid xid = transactionContext.getXid();
		boolean success = false;
		try {
			this.desociateThread();
			this.invokeCompensableCommit(transaction);
			success = true;
		} finally {
			compensableLock.unlockTransaction(xid, this.endpoint);
			if (success) {
				transaction.forgetQuietly(); // forget transaction
			} // end-if (success)
		}

	}

	protected void invokeCompensableCommit(CompensableTransaction compensable) throws RollbackException,
			HeuristicMixedException, HeuristicRollbackException, SecurityException, IllegalStateException, SystemException {

		TransactionRepository compensableRepository = this.beanFactory.getCompensableRepository();
		Transaction transaction = compensable.getTransaction();
		TransactionContext compensableContext = compensable.getTransactionContext();

		boolean commitExists = false;
		boolean rollbackExists = false;
		boolean errorExists = false;

		boolean isLocalTransaction = transaction.isLocalTransaction();
		try {
			if (isLocalTransaction) /* transaction in try-phase cannot be xa transaction. */ {
				this.invokeCompensableCommitIfLocalTransaction(compensable);
				commitExists = true;
			} else {
				this.invokeCompensableCommitIfNotLocalTransaction(compensable);
			}
		} catch (HeuristicRollbackException ex) {
			logger.info("Transaction in try-phase has already been rolled back heuristically.", ex);
			rollbackExists = true;
		} catch (SystemException ex) {
			logger.info("Error occurred while committing transaction in try-phase.", ex);
			errorExists = true;
		} catch (RuntimeException ex) {
			logger.info("Error occurred while committing transaction in try-phase.", ex);
			errorExists = true;
		} finally {
			compensable.setTransactionalExtra(null);
		}

		boolean failure = true;
		try {
			if (errorExists) {
				this.fireCompensableRollback(compensable);
				failure = false;
			} else if (commitExists) {
				this.fireCompensableCommit(compensable);
				failure = false;
			} else if (rollbackExists) {
				this.fireCompensableRollback(compensable);
				failure = false;
				throw new HeuristicRollbackException();
			} else {
				failure = false;
			}
		} finally {
			TransactionXid xid = compensableContext.getXid();
			if (failure) {
				compensableRepository.putErrorTransaction(xid, compensable);
			}
		}

	}

	protected void invokeCompensableCommitIfLocalTransaction(CompensableTransaction compensable)
			throws HeuristicRollbackException, SystemException {

		TransactionParticipant transactionCoordinator = this.beanFactory.getTransactionNativeParticipant();
		Transaction transaction = compensable.getTransaction();
		org.bytesoft.transaction.TransactionContext transactionContext = transaction.getTransactionContext();

		TransactionXid transactionXid = transactionContext.getXid();
		try {
			transactionCoordinator.end(transactionContext, XAResource.TMSUCCESS);
			transactionCoordinator.commit(transactionXid, true);
		} catch (XAException xaex) {
			switch (xaex.errorCode) {
			case XAException.XA_HEURCOM:
				transactionCoordinator.forgetQuietly(transactionXid);
				break;
			case XAException.XA_HEURRB:
				transactionCoordinator.forgetQuietly(transactionXid);
				HeuristicRollbackException hrex = new HeuristicRollbackException();
				hrex.initCause(xaex);
				throw hrex;
			default:
				transactionCoordinator.forgetQuietly(transactionXid); // TODO
				SystemException sysEx = new SystemException(xaex.errorCode);
				sysEx.initCause(xaex);
				throw sysEx;
			}
		}
	}

	protected void invokeCompensableCommitIfNotLocalTransaction(CompensableTransaction compensable)
			throws HeuristicRollbackException, SystemException {

		TransactionParticipant transactionCoordinator = this.beanFactory.getTransactionNativeParticipant();
		Transaction transaction = compensable.getTransaction();
		org.bytesoft.transaction.TransactionContext transactionContext = transaction.getTransactionContext();

		TransactionXid transactionXid = transactionContext.getXid();
		try {
			transactionCoordinator.end(transactionContext, XAResource.TMSUCCESS);
			TransactionContext compensableContext = compensable.getTransactionContext();
			logger.error("{}| jta-transaction in compensating-phase cannot be xa transaction.",
					ByteUtils.byteArrayToString(compensableContext.getXid().getGlobalTransactionId()));

			transactionCoordinator.rollback(transactionXid);
			throw new HeuristicRollbackException();
		} catch (XAException xaex) {
			transactionCoordinator.forgetQuietly(transactionXid);
			SystemException sysEx = new SystemException(xaex.errorCode);
			sysEx.initCause(xaex);
			throw sysEx;
		}
	}

	public void compensableRollback() throws IllegalStateException, SecurityException, SystemException {
		CompensableTransaction transaction = this.getCompensableTransactionQuietly();
		if (transaction == null) {
			throw new IllegalStateException();
		}

		TransactionContext transactionContext = transaction.getTransactionContext();
		boolean coordinator = transactionContext.isCoordinator();
		boolean compensable = transactionContext.isCompensable();
		boolean compensating = transactionContext.isCompensating();

		if (compensable == false) {
			throw new IllegalStateException();
		} else if (coordinator == false) {
			throw new IllegalStateException();
		} else if (compensating) {
			throw new IllegalStateException();
		}

		TransactionLock compensableLock = this.beanFactory.getCompensableLock();
		TransactionXid xid = transactionContext.getXid();
		boolean success = false;
		try {
			this.desociateThread();
			this.invokeCompensableRollback(transaction);
			success = true;
		} finally {
			compensableLock.unlockTransaction(xid, this.endpoint);
			if (success) {
				transaction.forgetQuietly(); // forget transaction
			} // end-if (success)
		}

	}

	protected void invokeCompensableRollback(CompensableTransaction compensable)
			throws IllegalStateException, SecurityException, SystemException {

		TransactionRepository compensableRepository = this.beanFactory.getCompensableRepository();
		TransactionParticipant transactionCoordinator = this.beanFactory.getTransactionNativeParticipant();

		Transaction transaction = compensable.getTransaction();
		org.bytesoft.compensable.TransactionContext compensableContext = compensable.getTransactionContext();
		org.bytesoft.transaction.TransactionContext transactionContext = transaction.getTransactionContext();

		TransactionXid transactionXid = transactionContext.getXid();
		try {
			transactionCoordinator.end(transactionContext, XAResource.TMSUCCESS);
			transactionCoordinator.rollback(transactionXid);
		} catch (XAException ex) {
			transactionCoordinator.forgetQuietly(transactionXid);
			logger.error("Error occurred while rolling back transaction in try phase!", ex);
		} finally {
			compensable.setTransactionalExtra(null);
		}

		boolean failure = true;
		try {
			this.fireCompensableRollback(compensable);
			failure = false;
		} finally {
			TransactionXid xid = compensableContext.getXid();
			if (failure) {
				compensableRepository.putErrorTransaction(xid, compensable);
			}
		}

	}

	public void setRollbackOnlyQuietly() {
		CompensableTransaction transaction = this.getCompensableTransactionQuietly();
		if (transaction != null) {
			transaction.setRollbackOnlyQuietly();
		}
	}

	public void setRollbackOnly() throws IllegalStateException, SystemException {
		CompensableTransaction transaction = this.getCompensableTransactionQuietly();
		if (transaction == null) {
			throw new IllegalStateException();
		}
		transaction.setRollbackOnlyQuietly();
	}

	public void setTransactionTimeout(int seconds) throws SystemException {
	}

	public int getTimeoutSeconds() {
		return 0;
	}

	public void setTimeoutSeconds(int timeoutSeconds) {
	}

	public boolean isStatefully() {
		return statefully;
	}

	public void setStatefully(boolean statefully) {
		this.statefully = statefully;
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
