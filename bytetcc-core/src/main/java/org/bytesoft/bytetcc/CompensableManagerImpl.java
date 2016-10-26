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
	static final Logger logger = LoggerFactory.getLogger(CompensableManagerImpl.class.getSimpleName());

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
		RemoteCoordinator jtaTransactionCoordinator = this.beanFactory.getTransactionCoordinator();
		XidFactory jtaXidFactory = this.beanFactory.getTransactionXidFactory();
		XidFactory tccXidFactory = this.beanFactory.getCompensableXidFactory();

		TransactionXid tccTransactionXid = tccXidFactory.createGlobalXid();
		TransactionXid jtaTransactionXid = jtaXidFactory.createGlobalXid(tccTransactionXid.getGlobalTransactionId());

		TransactionContext transactionContext = new TransactionContext();
		CompensableTransactionImpl transaction = (CompensableTransactionImpl) this.getCompensableTransactionQuietly();
		if (transaction == null) {
			transactionContext.setCoordinator(true);
			transactionContext.setXid(tccTransactionXid);
			transaction = new CompensableTransactionImpl(transactionContext);
			transaction.setBeanFactory(this.beanFactory);

			// compensableLogger.createTransaction(transaction.getTransactionArchive()); // lazy.
		}

		TransactionContext jtaTransactionContext = new TransactionContext();
		long current = System.currentTimeMillis();
		jtaTransactionContext.setCreatedTime(current);
		jtaTransactionContext.setExpiredTime(current + 1000L * 60 * 5);
		jtaTransactionContext.setXid(jtaTransactionXid);
		try {
			Transaction jtaTransaction = jtaTransactionCoordinator.start(jtaTransactionContext, XAResource.TMNOFLAGS);
			jtaTransaction.setTransactionalExtra(transaction);
			transaction.setTransactionalExtra(jtaTransaction);

			jtaTransaction.registerTransactionResourceListener(transaction);
			jtaTransaction.registerTransactionListener(transaction);
		} catch (TransactionException tex) {
			logger.info("[{}] begin-transaction: error occurred while starting jta-transaction: {}",
					ByteUtils.byteArrayToString(tccTransactionXid.getGlobalTransactionId()),
					ByteUtils.byteArrayToString(jtaTransactionXid.getGlobalTransactionId()));

			try {
				jtaTransactionCoordinator.end(jtaTransactionContext, XAResource.TMFAIL);
				throw new SystemException("Error occurred while beginning a compensable-transaction!");
			} catch (TransactionException ignore) {
				throw new SystemException("Error occurred while beginning a compensable-transaction!");
			}

		}

		TransactionRepository compensableRepository = this.beanFactory.getCompensableRepository();
		this.associateThread(transaction);
		compensableRepository.putTransaction(tccTransactionXid, transaction);
	}

	protected void beginInCompensePhase() throws NotSupportedException, SystemException {
		CompensableTransaction transaction = this.getCompensableTransactionQuietly();
		if (transaction.getTransaction() != null) {
			throw new SystemException();
		}

		RemoteCoordinator jtaTransactionCoordinator = this.beanFactory.getTransactionCoordinator();
		XidFactory jtaXidFactory = this.beanFactory.getTransactionXidFactory();
		XidFactory tccXidFactory = this.beanFactory.getCompensableXidFactory();

		TransactionXid tccTransactionXid = tccXidFactory.createGlobalXid();
		TransactionXid jtaTransactionXid = jtaXidFactory.createGlobalXid();

		TransactionContext jtaTransactionContext = new TransactionContext();
		long current = System.currentTimeMillis();
		jtaTransactionContext.setCreatedTime(current);
		jtaTransactionContext.setExpiredTime(current + 1000L * 60 * 5);
		jtaTransactionContext.setXid(jtaTransactionXid);
		try {
			Transaction jtaTransaction = jtaTransactionCoordinator.start(jtaTransactionContext, XAResource.TMNOFLAGS);
			jtaTransaction.setTransactionalExtra(transaction);
			transaction.setTransactionalExtra(jtaTransaction);

			jtaTransaction.registerTransactionResourceListener(transaction);
			jtaTransaction.registerTransactionListener(transaction);
		} catch (TransactionException tex) {
			transaction.setTransactionalExtra(null);

			logger.info("[{}] begin-transaction: error occurred while starting jta-transaction: {}",
					ByteUtils.byteArrayToString(tccTransactionXid.getGlobalTransactionId()),
					ByteUtils.byteArrayToString(jtaTransactionXid.getGlobalTransactionId()));
			try {
				jtaTransactionCoordinator.end(jtaTransactionContext, XAResource.TMFAIL);
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

	protected void invokeTransactionCommit(CompensableTransaction transaction) throws RollbackException,
			HeuristicMixedException, HeuristicRollbackException, SecurityException, IllegalStateException, SystemException {

		Transaction jtaTransaction = transaction.getTransaction();
		boolean isLocalTransaction = jtaTransaction.isLocalTransaction();
		try {
			if (isLocalTransaction) {
				this.invokeTransactionCommitIfLocalTransaction(transaction);
			} else {
				this.invokeTransactionCommitIfNotLocalTransaction(transaction);
			}
		} finally {
			transaction.setTransactionalExtra(null);
		}
	}

	protected void invokeTransactionCommitIfLocalTransaction(CompensableTransaction transaction) throws RollbackException,
			HeuristicMixedException, HeuristicRollbackException, SecurityException, IllegalStateException, SystemException {

		Transaction jtaTransaction = transaction.getTransaction();
		TransactionContext jtaTransactionContext = jtaTransaction.getTransactionContext();
		TransactionXid xid = jtaTransactionContext.getXid();
		RemoteCoordinator transactionCoordinator = this.beanFactory.getTransactionCoordinator();

		try {
			transactionCoordinator.end(jtaTransactionContext, XAResource.TMSUCCESS);
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

	protected void invokeTransactionCommitIfNotLocalTransaction(CompensableTransaction transaction) throws RollbackException,
			HeuristicMixedException, HeuristicRollbackException, SecurityException, IllegalStateException, SystemException {

		Transaction jtaTransaction = transaction.getTransaction();
		TransactionContext jtaTransactionContext = jtaTransaction.getTransactionContext();
		TransactionXid xid = jtaTransactionContext.getXid();
		RemoteCoordinator transactionCoordinator = this.beanFactory.getTransactionCoordinator();

		try {
			transactionCoordinator.end(jtaTransactionContext, XAResource.TMSUCCESS);

			TransactionContext transactionContext = transaction.getTransactionContext();
			logger.error("[{}] jta-transaction in try-phase cannot be xa transaction.",
					ByteUtils.byteArrayToString(transactionContext.getXid().getGlobalTransactionId()));

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

	protected void invokeTransactionRollback(CompensableTransaction transaction)
			throws IllegalStateException, SecurityException, SystemException {

		Transaction jtaTransaction = transaction.getTransaction();
		TransactionContext jtaTransactionContext = jtaTransaction.getTransactionContext();
		TransactionXid xid = jtaTransactionContext.getXid();
		RemoteCoordinator transactionCoordinator = this.beanFactory.getTransactionCoordinator();
		try {
			transactionCoordinator.end(jtaTransactionContext, XAResource.TMSUCCESS);
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

	protected void invokeCompensableCommit(CompensableTransaction transaction) throws RollbackException,
			HeuristicMixedException, HeuristicRollbackException, SecurityException, IllegalStateException, SystemException {

		TransactionRepository compensableRepository = this.beanFactory.getCompensableRepository();
		Transaction jtaTransaction = transaction.getTransaction();
		TransactionContext tccTransactionContext = transaction.getTransactionContext();

		boolean commitExists = false;
		boolean rollbackExists = false;
		boolean errorExists = false;

		boolean isLocalTransaction = jtaTransaction.isLocalTransaction();
		try {
			if (isLocalTransaction) /* jta-transaction in try-phase cannot be xa transaction. */ {
				this.invokeCompensableCommitIfLocalTransaction(transaction);
				commitExists = true;
			} else {
				this.invokeCompensableCommitIfNotLocalTransaction(transaction);
			}
		} catch (HeuristicRollbackException ex) {
			rollbackExists = true;
		} catch (SystemException ex) {
			errorExists = true;
		} catch (RuntimeException ex) {
			errorExists = true;
		} finally {
			transaction.setTransactionalExtra(null);

			boolean success = false;
			try {
				if (errorExists) {
					this.fireCompensableRollback(transaction);
				} else if (commitExists) {
					this.fireCompensableCommit(transaction);
				} else if (rollbackExists) {
					this.fireCompensableRollback(transaction);
					throw new HeuristicRollbackException();
				}
				success = true;
			} finally {
				TransactionXid xid = tccTransactionContext.getXid();
				if (success) {
					compensableRepository.removeTransaction(xid);
				} else {
					compensableRepository.putErrorTransaction(xid, transaction);
				}
			}

		}

	}

	protected void invokeCompensableCommitIfLocalTransaction(CompensableTransaction transaction)
			throws HeuristicRollbackException, SystemException {

		RemoteCoordinator jtaTransactionCoordinator = this.beanFactory.getTransactionCoordinator();
		Transaction jtaTransaction = transaction.getTransaction();
		TransactionContext jtaTransactionContext = jtaTransaction.getTransactionContext();

		TransactionXid jtaTransactionXid = jtaTransactionContext.getXid();
		try {
			jtaTransactionCoordinator.end(jtaTransactionContext, XAResource.TMSUCCESS);
			jtaTransactionCoordinator.commit(jtaTransactionXid, true);
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

	protected void invokeCompensableCommitIfNotLocalTransaction(CompensableTransaction transaction)
			throws HeuristicRollbackException, SystemException {

		RemoteCoordinator jtaTransactionCoordinator = this.beanFactory.getTransactionCoordinator();
		Transaction jtaTransaction = transaction.getTransaction();
		TransactionContext jtaTransactionContext = jtaTransaction.getTransactionContext();

		TransactionXid jtaTransactionXid = jtaTransactionContext.getXid();
		try {
			jtaTransactionCoordinator.end(jtaTransactionContext, XAResource.TMSUCCESS);
			TransactionContext transactionContext = transaction.getTransactionContext();
			logger.error("[{}] jta-transaction in compensating-phase cannot be xa transaction.",
					ByteUtils.byteArrayToString(transactionContext.getXid().getGlobalTransactionId()));

			jtaTransactionCoordinator.rollback(jtaTransactionXid);
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

	protected void invokeCompensableRollback(CompensableTransaction transaction)
			throws IllegalStateException, SecurityException, SystemException {

		TransactionRepository compensableRepository = this.beanFactory.getCompensableRepository();
		RemoteCoordinator jtaTransactionCoordinator = this.beanFactory.getTransactionCoordinator();

		Transaction jtaTransaction = transaction.getTransaction();
		TransactionContext tccTransactionContext = transaction.getTransactionContext();
		TransactionContext jtaTransactionContext = jtaTransaction.getTransactionContext();

		boolean success = false;
		try {
			TransactionXid jtaTransactionXid = jtaTransactionContext.getXid();
			jtaTransactionCoordinator.end(jtaTransactionContext, XAResource.TMSUCCESS);
			jtaTransactionCoordinator.rollback(jtaTransactionXid);
			success = true;
		} catch (XAException ex) {
			this.fireCompensableRollback(transaction);
			success = true;
		} finally {
			transaction.setTransactionalExtra(null);

			this.fireCompensableRollback(transaction);

			TransactionXid xid = tccTransactionContext.getXid();
			if (success) {
				compensableRepository.removeTransaction(xid);
			} else {
				compensableRepository.putErrorTransaction(xid, transaction);
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
