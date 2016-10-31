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
import org.bytesoft.common.utils.ByteUtils;
import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.CompensableManager;
import org.bytesoft.compensable.CompensableTransaction;
import org.bytesoft.compensable.aware.CompensableBeanFactoryAware;
import org.bytesoft.transaction.Transaction;
import org.bytesoft.transaction.TransactionContext;
import org.bytesoft.transaction.TransactionManager;
import org.bytesoft.transaction.TransactionRepository;
import org.bytesoft.transaction.internal.TransactionException;
import org.bytesoft.transaction.xa.TransactionXid;
import org.bytesoft.transaction.xa.XidFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CompensableManagerImpl implements CompensableManager, CompensableBeanFactoryAware {
	static final Logger logger = LoggerFactory.getLogger(CompensableManagerImpl.class);

	private CompensableBeanFactory beanFactory;
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
		if (CompensableTransaction.class.isInstance(tobj)) {
			TransactionManager transactionManager = this.beanFactory.getTransactionManager();
			CompensableTransaction compensable = (CompensableTransaction) tobj;

			this.associateThread(compensable);
			transactionManager.resume((Transaction) compensable.getTransactionalExtra());
		} else if (Transaction.class.isInstance(tobj)) {
			TransactionManager transactionManager = this.beanFactory.getTransactionManager();
			Transaction transaction = (Transaction) tobj;
			CompensableTransaction compensable = (CompensableTransaction) transaction.getTransactionalExtra();

			compensable.setTransactionalExtra(transaction);
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

		TransactionContext transactionContext = compensable.getTransactionContext();
		if (transactionContext.isCompensating()) {
			TransactionManager transactionManager = this.beanFactory.getTransactionManager();
			Transaction transaction = transactionManager.suspend();

			compensable.setTransactionalExtra(null);
			return transaction;
		} else {
			TransactionManager transactionManager = this.beanFactory.getTransactionManager();
			this.desociateThread();
			transactionManager.suspend();

			return compensable;
		}

	}

	public void begin() throws NotSupportedException, SystemException {
		CompensableTransaction transaction = this.getCompensableTransactionQuietly();
		if (transaction == null || transaction.getTransaction() == null) {
			this.beginInTryingPhase();
		} else {
			this.beginInCompensePhase();
		}
	}

	protected void beginInTryingPhase() throws NotSupportedException, SystemException {
		RemoteCoordinator transactionCoordinator = this.beanFactory.getTransactionCoordinator();
		XidFactory transactionXidFactory = this.beanFactory.getTransactionXidFactory();
		XidFactory compensableXidFactory = this.beanFactory.getCompensableXidFactory();

		TransactionXid compensableXid = compensableXidFactory.createGlobalXid();
		TransactionXid transactionXid = transactionXidFactory.createGlobalXid(compensableXid.getGlobalTransactionId());

		TransactionContext compensableContext = new TransactionContext();
		CompensableTransactionImpl compensable = (CompensableTransactionImpl) this.getCompensableTransactionQuietly();
		if (compensable == null) {
			compensableContext.setCoordinator(true);
			compensableContext.setXid(compensableXid);
			compensable = new CompensableTransactionImpl(compensableContext);
			compensable.setBeanFactory(this.beanFactory);

			// compensableLogger.createTransaction(transaction.getTransactionArchive()); // lazy.
		}

		TransactionContext transactionContext = new TransactionContext();
		long current = System.currentTimeMillis();
		transactionContext.setCreatedTime(current);
		transactionContext.setExpiredTime(current + 1000L * 60 * 5);
		transactionContext.setXid(transactionXid);
		try {
			Transaction transaction = transactionCoordinator.start(transactionContext, XAResource.TMNOFLAGS);
			transaction.setTransactionalExtra(compensable);
			compensable.setTransactionalExtra(transaction);

			transaction.registerTransactionResourceListener(compensable);
			transaction.registerTransactionListener(compensable);
		} catch (TransactionException tex) {
			logger.info("[{}] begin-transaction: error occurred while starting jta-transaction: {}",
					ByteUtils.byteArrayToString(compensableXid.getGlobalTransactionId()),
					ByteUtils.byteArrayToString(transactionXid.getGlobalTransactionId()));

			try {
				transactionCoordinator.end(transactionContext, XAResource.TMFAIL);
				throw new SystemException("Error occurred while beginning a compensable-transaction!");
			} catch (TransactionException ignore) {
				throw new SystemException("Error occurred while beginning a compensable-transaction!");
			}

		}

		TransactionRepository compensableRepository = this.beanFactory.getCompensableRepository();
		this.associateThread(compensable);
		compensableRepository.putTransaction(compensableXid, compensable);
	}

	protected void beginInCompensePhase() throws NotSupportedException, SystemException {
		CompensableTransaction compensable = this.getCompensableTransactionQuietly();
		if (compensable.getTransaction() != null) {
			throw new SystemException();
		}

		RemoteCoordinator transactionCoordinator = this.beanFactory.getTransactionCoordinator();
		XidFactory transactionXidFactory = this.beanFactory.getTransactionXidFactory();
		XidFactory compensableXidFactory = this.beanFactory.getCompensableXidFactory();

		TransactionXid compensableXid = compensableXidFactory.createGlobalXid();
		TransactionXid transactionXid = transactionXidFactory.createGlobalXid();

		TransactionContext transactionContext = new TransactionContext();
		long current = System.currentTimeMillis();
		transactionContext.setCreatedTime(current);
		transactionContext.setExpiredTime(current + 1000L * 60 * 5);
		transactionContext.setXid(transactionXid);
		try {
			Transaction transaction = transactionCoordinator.start(transactionContext, XAResource.TMNOFLAGS);
			transaction.setTransactionalExtra(compensable);
			compensable.setTransactionalExtra(transaction);

			transaction.registerTransactionResourceListener(compensable);
			transaction.registerTransactionListener(compensable);
		} catch (TransactionException tex) {
			compensable.setTransactionalExtra(null);

			logger.info("[{}] begin-transaction: error occurred while starting jta-transaction: {}",
					ByteUtils.byteArrayToString(compensableXid.getGlobalTransactionId()),
					ByteUtils.byteArrayToString(transactionXid.getGlobalTransactionId()));
			try {
				transactionCoordinator.end(transactionContext, XAResource.TMFAIL);
				throw new SystemException("Error occurred while beginning a compensable-transaction!");
			} catch (TransactionException ignore) {
				throw new SystemException("Error occurred while beginning a compensable-transaction!");
			}

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
		boolean compensable = transactionContext.isCompensable();
		boolean compensating = transactionContext.isCompensating();

		if (compensable == false) {
			throw new IllegalStateException();
		} else if (compensating) {
			this.invokeTransactionCommit(transaction);
		} else if (coordinator) {
			throw new IllegalStateException();
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
		TransactionContext transactionContext = transaction.getTransactionContext();
		TransactionXid xid = transactionContext.getXid();
		RemoteCoordinator transactionCoordinator = this.beanFactory.getTransactionCoordinator();

		try {
			transactionCoordinator.end(transactionContext, XAResource.TMSUCCESS);
			transactionCoordinator.commit(xid, true);
		} catch (XAException xae) {
			switch (xae.errorCode) {
			case XAException.XA_HEURCOM:
				break;
			case XAException.XA_HEURRB:
				throw new HeuristicRollbackException();
			case XAException.XA_HEURMIX:
				throw new HeuristicMixedException();
			case XAException.XAER_RMERR:
			default:
				throw new SystemException();
			}
		}
	}

	protected void invokeTransactionCommitIfNotLocalTransaction(CompensableTransaction compensable) throws RollbackException,
			HeuristicMixedException, HeuristicRollbackException, SecurityException, IllegalStateException, SystemException {

		Transaction transaction = compensable.getTransaction();
		TransactionContext transactionContext = transaction.getTransactionContext();
		TransactionXid xid = transactionContext.getXid();
		RemoteCoordinator transactionCoordinator = this.beanFactory.getTransactionCoordinator();

		try {
			transactionCoordinator.end(transactionContext, XAResource.TMSUCCESS);

			TransactionContext compensableContext = compensable.getTransactionContext();
			logger.error("[{}] jta-transaction in try-phase cannot be xa transaction.",
					ByteUtils.byteArrayToString(compensableContext.getXid().getGlobalTransactionId()));

			transactionCoordinator.rollback(xid);
			throw new HeuristicRollbackException();
		} catch (XAException xae) {
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
		boolean compensable = transactionContext.isCompensable();

		if (compensable == false) {
			throw new IllegalStateException();
		} else if (coordinator) {
			throw new IllegalStateException();
		} else {
			this.invokeTransactionRollback(transaction);
		}

	}

	protected void invokeTransactionRollback(CompensableTransaction compensable)
			throws IllegalStateException, SecurityException, SystemException {

		Transaction transaction = compensable.getTransaction();
		TransactionContext transactionContext = transaction.getTransactionContext();
		TransactionXid xid = transactionContext.getXid();
		RemoteCoordinator transactionCoordinator = this.beanFactory.getTransactionCoordinator();
		try {
			transactionCoordinator.end(transactionContext, XAResource.TMSUCCESS);
			transactionCoordinator.rollback(xid);
		} catch (XAException xae) {
			throw new SystemException();
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

		this.desociateThread();
		this.invokeCompensableCommit(transaction);
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

			boolean success = false;
			try {
				if (errorExists) {
					this.fireCompensableRollback(compensable);
				} else if (commitExists) {
					this.fireCompensableCommit(compensable);
				} else if (rollbackExists) {
					this.fireCompensableRollback(compensable);
					throw new HeuristicRollbackException();
				}
				success = true;
			} finally {
				TransactionXid xid = compensableContext.getXid();
				if (success) {
					compensableRepository.removeTransaction(xid);
				} else {
					compensableRepository.putErrorTransaction(xid, compensable);
				}
			}

		}

	}

	protected void invokeCompensableCommitIfLocalTransaction(CompensableTransaction compensable)
			throws HeuristicRollbackException, SystemException {

		RemoteCoordinator transactionCoordinator = this.beanFactory.getTransactionCoordinator();
		Transaction transaction = compensable.getTransaction();
		TransactionContext transactionContext = transaction.getTransactionContext();

		TransactionXid transactionXid = transactionContext.getXid();
		try {
			transactionCoordinator.end(transactionContext, XAResource.TMSUCCESS);
			transactionCoordinator.commit(transactionXid, true);
		} catch (XAException xaex) {
			switch (xaex.errorCode) {
			case XAException.XA_HEURCOM:
				break;
			case XAException.XA_HEURRB:
				throw new HeuristicRollbackException();
			default:
				throw new SystemException();
			}
		}
	}

	protected void invokeCompensableCommitIfNotLocalTransaction(CompensableTransaction compensable)
			throws HeuristicRollbackException, SystemException {

		RemoteCoordinator transactionCoordinator = this.beanFactory.getTransactionCoordinator();
		Transaction transaction = compensable.getTransaction();
		TransactionContext transactionContext = transaction.getTransactionContext();

		TransactionXid transactionXid = transactionContext.getXid();
		try {
			transactionCoordinator.end(transactionContext, XAResource.TMSUCCESS);
			TransactionContext compensableContext = compensable.getTransactionContext();
			logger.error("[{}] jta-transaction in compensating-phase cannot be xa transaction.",
					ByteUtils.byteArrayToString(compensableContext.getXid().getGlobalTransactionId()));

			transactionCoordinator.rollback(transactionXid);
			throw new HeuristicRollbackException();
		} catch (XAException xaex) {
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

		this.desociateThread();
		this.invokeCompensableRollback(transaction);
	}

	protected void invokeCompensableRollback(CompensableTransaction compensable)
			throws IllegalStateException, SecurityException, SystemException {

		TransactionRepository compensableRepository = this.beanFactory.getCompensableRepository();
		RemoteCoordinator transactionCoordinator = this.beanFactory.getTransactionCoordinator();

		Transaction transaction = compensable.getTransaction();
		TransactionContext compensableContext = compensable.getTransactionContext();
		TransactionContext transactionContext = transaction.getTransactionContext();

		boolean success = false;
		try {
			TransactionXid transactionXid = transactionContext.getXid();
			transactionCoordinator.end(transactionContext, XAResource.TMSUCCESS);
			transactionCoordinator.rollback(transactionXid);
			success = true;
		} catch (XAException ex) {
			this.fireCompensableRollback(compensable);
			success = true;
		} finally {
			compensable.setTransactionalExtra(null);

			this.fireCompensableRollback(compensable);

			TransactionXid xid = compensableContext.getXid();
			if (success) {
				compensableRepository.removeTransaction(xid);
			} else {
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

}
