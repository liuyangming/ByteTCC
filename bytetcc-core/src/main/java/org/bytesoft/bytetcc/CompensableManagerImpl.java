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

import org.bytesoft.bytejta.supports.wire.RemoteCoordinator;
import org.bytesoft.bytetcc.supports.CompensableSynchronization;
import org.bytesoft.common.utils.ByteUtils;
import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.CompensableManager;
import org.bytesoft.compensable.CompensableTransaction;
import org.bytesoft.compensable.TransactionContext;
import org.bytesoft.compensable.aware.CompensableBeanFactoryAware;
import org.bytesoft.compensable.aware.CompensableEndpointAware;
import org.bytesoft.compensable.logging.CompensableLogger;
import org.bytesoft.transaction.Transaction;
import org.bytesoft.transaction.TransactionLock;
import org.bytesoft.transaction.TransactionManager;
import org.bytesoft.transaction.TransactionRepository;
import org.bytesoft.transaction.xa.TransactionXid;
import org.bytesoft.transaction.xa.XidFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CompensableManagerImpl implements CompensableManager, CompensableBeanFactoryAware, CompensableEndpointAware {
	static final Logger logger = LoggerFactory.getLogger(CompensableManagerImpl.class);

	private CompensableBeanFactory beanFactory;
	private String endpoint;

	private final Map<Thread, Transaction> compensableMap = new ConcurrentHashMap<Thread, Transaction>();

	public void associateThread(Transaction transaction) {
		this.compensableMap.put(Thread.currentThread(), (CompensableTransaction) transaction);
	}

	public CompensableTransaction desociateThread() {
		return (CompensableTransaction) this.compensableMap.remove(Thread.currentThread());
	}

	public int getStatus() throws SystemException {
		Transaction transaction = this.getTransactionQuietly();
		return transaction == null ? Status.STATUS_NO_TRANSACTION : transaction.getStatus();
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
		return (CompensableTransaction) this.compensableMap.get(Thread.currentThread());
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
		CompensableTransaction compensable = (CompensableTransaction) this.compensableMap.get(Thread.currentThread());
		if (compensable == null) {
			throw new SystemException();
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
		CompensableTransaction compensable = this.getCompensableTransactionQuietly();
		if (compensable == null || compensable.getTransaction() != null) {
			throw new SystemException();
		}

		TransactionContext compensableContext = compensable.getTransactionContext();

		XidFactory transactionXidFactory = this.beanFactory.getTransactionXidFactory();
		TransactionXid transactionXid = transactionXidFactory.createGlobalXid();

		TransactionContext transactionContext = compensableContext.clone();
		transactionContext.setXid(transactionXid);

		this.invokeBegin(transactionContext, false);
	}

	protected void invokeBegin(TransactionContext transactionContext, boolean createFlag)
			throws NotSupportedException, SystemException {
		RemoteCoordinator transactionCoordinator = this.beanFactory.getTransactionCoordinator();

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
			logger.info("[{}] begin-transaction: error occurred while starting jta-transaction: {}",
					ByteUtils.byteArrayToString(compensableXid.getGlobalTransactionId()),
					ByteUtils.byteArrayToString(transactionXid.getGlobalTransactionId()));
			try {
				transactionCoordinator.end(transactionContext, XAResource.TMFAIL);
				throw new SystemException("Error occurred while beginning a compensable-transaction!");
			} catch (XAException ignore) {
				throw new SystemException("Error occurred while beginning a compensable-transaction!");
			}
		}
	}

	protected void invokeRollbackInBegin(TransactionContext transactionContext) throws NotSupportedException, SystemException {
		RemoteCoordinator transactionCoordinator = this.beanFactory.getTransactionCoordinator();

		CompensableTransaction compensable = this.getCompensableTransactionQuietly();
		TransactionContext compensableContext = compensable.getTransactionContext();

		TransactionXid compensableXid = compensableContext.getXid();
		TransactionXid transactionXid = transactionContext.getXid();
		try {
			transactionCoordinator.end(transactionContext, XAResource.TMFAIL);
			transactionCoordinator.rollback(transactionXid);
		} catch (XAException tex) {
			logger.info("[{}] begin-transaction: error occurred while starting jta-transaction: {}",
					ByteUtils.byteArrayToString(compensableXid.getGlobalTransactionId()),
					ByteUtils.byteArrayToString(transactionXid.getGlobalTransactionId()));
		}
	}

	public void compensableBegin() throws NotSupportedException, SystemException {
		if (this.getCompensableTransactionQuietly() != null) {
			throw new NotSupportedException();
		}

		CompensableLogger compensableLogger = this.beanFactory.getCompensableLogger();
		TransactionLock compensableLock = this.beanFactory.getCompensableLock();
		TransactionRepository compensableRepository = this.beanFactory.getCompensableRepository();
		RemoteCoordinator compensableCoordinator = this.beanFactory.getCompensableCoordinator();

		XidFactory transactionXidFactory = this.beanFactory.getTransactionXidFactory();
		XidFactory compensableXidFactory = this.beanFactory.getCompensableXidFactory();

		TransactionXid compensableXid = compensableXidFactory.createGlobalXid();
		TransactionXid transactionXid = transactionXidFactory.createGlobalXid(compensableXid.getGlobalTransactionId());

		TransactionContext compensableContext = new TransactionContext();
		compensableContext.setCoordinator(true);
		compensableContext.setCompensable(true);
		compensableContext.setXid(compensableXid);
		compensableContext.setPropagatedBy(compensableCoordinator.getIdentifier());
		CompensableTransactionImpl compensable = new CompensableTransactionImpl(compensableContext);
		compensable.setBeanFactory(this.beanFactory);

		this.associateThread(compensable);

		TransactionContext transactionContext = new TransactionContext();
		transactionContext.setXid(transactionXid);

		boolean failure = true;
		try {
			this.invokeBegin(transactionContext, true);
			failure = false;
		} finally {
			if (failure) {
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

			throw new SystemException(); // should never happen
		}

		logger.info("{}| compensable transaction begin!", ByteUtils.byteArrayToString(compensableXid.getGlobalTransactionId()));
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
			this.invokeTransactionCommit(transaction);
		} else if (coordinator) {
			if (propagated) {
				this.invokeTransactionCommit(transaction);
			} else if (propagatedLevel > 0) {
				this.invokeTransactionCommit(transaction);
			} else {
				throw new IllegalStateException();
			}
		} else {
			this.invokeTransactionCommit(transaction);
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
		RemoteCoordinator transactionCoordinator = this.beanFactory.getTransactionCoordinator();

		TransactionXid transactionXid = transactionContext.getXid();
		try {
			transactionCoordinator.end(transactionContext, XAResource.TMSUCCESS);
			transactionCoordinator.commit(transactionXid, true);
		} catch (XAException xae) {
			switch (xae.errorCode) {
			case XAException.XA_HEURCOM:
				transactionCoordinator.forgetQuietly(transactionXid);
				break;
			case XAException.XA_HEURRB:
				transactionCoordinator.forgetQuietly(transactionXid);
				throw new HeuristicRollbackException();
			case XAException.XA_HEURMIX:
				transactionCoordinator.forgetQuietly(transactionXid);
				throw new HeuristicMixedException();
			case XAException.XAER_RMERR:
			default:
				transactionCoordinator.forgetQuietly(transactionXid); // TODO
				throw new SystemException();
			}
		}

	}

	protected void invokeTransactionCommitIfNotLocalTransaction(CompensableTransaction compensable) throws RollbackException,
			HeuristicMixedException, HeuristicRollbackException, SecurityException, IllegalStateException, SystemException {

		Transaction transaction = compensable.getTransaction();
		org.bytesoft.transaction.TransactionContext transactionContext = transaction.getTransactionContext();
		RemoteCoordinator transactionCoordinator = this.beanFactory.getTransactionCoordinator();

		TransactionXid transactionXid = transactionContext.getXid();
		try {
			transactionCoordinator.end(transactionContext, XAResource.TMSUCCESS);

			TransactionContext compensableContext = compensable.getTransactionContext();
			logger.error("[{}] jta-transaction in try-phase cannot be xa transaction.",
					ByteUtils.byteArrayToString(compensableContext.getXid().getGlobalTransactionId()));

			transactionCoordinator.rollback(transactionXid);
			throw new HeuristicRollbackException();
		} catch (XAException xae) {
			transactionCoordinator.forgetQuietly(transactionXid);
			throw new SystemException();
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
		int propagatedLevel = transactionContext.getPropagationLevel();

		if (compensable == false) {
			throw new IllegalStateException();
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
		RemoteCoordinator transactionCoordinator = this.beanFactory.getTransactionCoordinator();

		TransactionXid transactionXid = transactionContext.getXid();
		try {
			transactionCoordinator.end(transactionContext, XAResource.TMSUCCESS);
			transactionCoordinator.rollback(transactionXid);
		} catch (XAException xae) {
			transactionCoordinator.forgetQuietly(transactionXid);
			throw new SystemException();
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
			if (isLocalTransaction) /* jta-transaction in try-phase cannot be xa transaction. */ {
				this.invokeCompensableCommitIfLocalTransaction(compensable);
				commitExists = true;
			} else {
				this.invokeCompensableCommitIfNotLocalTransaction(compensable);
			}
		} catch (HeuristicRollbackException ex) {
			rollbackExists = true;
		} catch (SystemException ex) {
			errorExists = true;
		} catch (RuntimeException ex) {
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

		RemoteCoordinator transactionCoordinator = this.beanFactory.getTransactionCoordinator();
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
				throw new HeuristicRollbackException();
			default:
				transactionCoordinator.forgetQuietly(transactionXid); // TODO
				throw new SystemException();
			}
		}
	}

	protected void invokeCompensableCommitIfNotLocalTransaction(CompensableTransaction compensable)
			throws HeuristicRollbackException, SystemException {

		RemoteCoordinator transactionCoordinator = this.beanFactory.getTransactionCoordinator();
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
			throw new SystemException();
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
		RemoteCoordinator transactionCoordinator = this.beanFactory.getTransactionCoordinator();

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

	public void setRollbackOnly() throws IllegalStateException, SystemException {
	}

	public void setTransactionTimeout(int seconds) throws SystemException {
	}

	public int getTimeoutSeconds() {
		return 0;
	}

	public void setTimeoutSeconds(int timeoutSeconds) {
	}

	public void setBeanFactory(CompensableBeanFactory tbf) {
		this.beanFactory = tbf;
	}

	public void setEndpoint(String identifier) {
		this.endpoint = identifier;
	}

}
