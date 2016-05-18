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

import org.apache.log4j.Logger;
import org.bytesoft.bytejta.supports.wire.RemoteCoordinator;
import org.bytesoft.common.utils.ByteUtils;
import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.CompensableManager;
import org.bytesoft.compensable.CompensableTransaction;
import org.bytesoft.compensable.aware.CompensableBeanFactoryAware;
import org.bytesoft.transaction.Transaction;
import org.bytesoft.transaction.TransactionContext;
import org.bytesoft.transaction.TransactionRepository;
import org.bytesoft.transaction.internal.TransactionException;
import org.bytesoft.transaction.xa.TransactionXid;
import org.bytesoft.transaction.xa.XidFactory;

public class CompensableManagerImpl implements CompensableManager, CompensableBeanFactoryAware {
	static final Logger logger = Logger.getLogger(CompensableManagerImpl.class.getSimpleName());

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

	public void resume(javax.transaction.Transaction tobj) throws InvalidTransactionException, IllegalStateException,
			SystemException {
		CompensableTransaction transaction = (CompensableTransaction) this.compensableMap.get(Thread.currentThread());
		if (transaction == null || transaction.getTransaction() != null) {
			throw new IllegalStateException();
		}
		Transaction jtaTransaction = (Transaction) tobj;
		Object transactionalExtra = jtaTransaction.getTransactionalExtra();
		if (transactionalExtra == null || transactionalExtra.equals(transaction) == false) {
			throw new IllegalStateException();
		}
		jtaTransaction.resume();
		transaction.setTransactionalExtra(jtaTransaction);
	}

	public Transaction suspend() throws SystemException {
		CompensableTransaction transaction = (CompensableTransaction) this.compensableMap.get(Thread.currentThread());
		Transaction jtaTransaction = transaction == null ? null : transaction.getTransaction();
		if (jtaTransaction == null) {
			throw new SystemException();
		}
		transaction.setTransactionalExtra(null);
		jtaTransaction.suspend();
		return jtaTransaction;
	}

	public void begin() throws NotSupportedException, SystemException {
		CompensableTransaction transaction = this.getCompensableTransactionQuietly();
		if (transaction == null || transaction.getTransaction() == null) {
			this.beginTxInTryingPhase();
		} else {
			this.beginTxInCompensePhase();
		}
	}

	protected void beginTxInTryingPhase() throws NotSupportedException, SystemException {
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
		} catch (TransactionException tex) {
			try {
				jtaTransactionCoordinator.end(jtaTransactionContext, XAResource.TMFAIL);
			} catch (TransactionException ignore) {
				// ignore
			}
			logger.info(String.format("[%s] begin-transaction: error occurred while starting jta-transaction: %s",
					ByteUtils.byteArrayToString(tccTransactionXid.getGlobalTransactionId()),
					ByteUtils.byteArrayToString(jtaTransactionXid.getGlobalTransactionId())));

			throw new SystemException("Error occurred while beginning a compensable-transaction!");
		}

		TransactionRepository transactionRepository = this.beanFactory.getTransactionRepository();
		this.associateThread(transaction);
		transactionRepository.putTransaction(tccTransactionXid, transaction);
	}

	protected void beginTxInCompensePhase() throws NotSupportedException, SystemException {
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

			jtaTransaction.registerTransactionListener(transaction);
		} catch (TransactionException tex) {
			transaction.setTransactionalExtra(null);
			try {
				jtaTransactionCoordinator.end(jtaTransactionContext, XAResource.TMFAIL);
			} catch (TransactionException ignore) {
				logger.debug(String.format(
						"[%s] begin-transaction<compense>: error occurred while unbinding jta-transaction: %s",
						ByteUtils.byteArrayToString(tccTransactionXid.getGlobalTransactionId()),
						ByteUtils.byteArrayToString(jtaTransactionXid.getGlobalTransactionId())));
			}
			logger.info(String.format("[%s] begin-transaction<compense>: error occurred while starting jta-transaction: %s",
					ByteUtils.byteArrayToString(tccTransactionXid.getGlobalTransactionId()),
					ByteUtils.byteArrayToString(jtaTransactionXid.getGlobalTransactionId())));

			throw new SystemException("Error occurred while beginning a compensable-transaction!");
		}
	}

	public void commit() throws RollbackException, HeuristicMixedException, HeuristicRollbackException, SecurityException,
			IllegalStateException, SystemException {
		boolean compensating = this.isCompensePhaseCurrently();
		if (compensating) {
			this.commitTxInCompensePhase();
		} else {
			this.commitTxInTryingPhase();
		}
	}

	protected void commitTxInTryingPhase() throws RollbackException, HeuristicMixedException, HeuristicRollbackException,
			SecurityException, IllegalStateException, SystemException {
		CompensableTransaction transaction = (CompensableTransaction) this.compensableMap.get(Thread.currentThread());
		if (transaction == null) {
			throw new IllegalStateException();
		} else if (transaction.getTransaction() != null) {
			// TODO commit jta transaction
		}

		TransactionRepository transactionRepository = this.beanFactory.getTransactionRepository();
		RemoteCoordinator jtaTransactionCoordinator = this.beanFactory.getTransactionCoordinator();

		Transaction jtaTransaction = transaction.getTransaction();
		TransactionContext tccTransactionContext = transaction.getTransactionContext();
		TransactionContext jtaTransactionContext = jtaTransaction.getTransactionContext();

		boolean coordinator = tccTransactionContext.isCoordinator();
		boolean compensable = tccTransactionContext.isCompensable();
		if (coordinator) {
			this.desociateThread();
		}

		TransactionXid jtaTransactionXid = jtaTransactionContext.getXid();
		boolean commitExists = false;
		boolean rollbackExists = false;
		boolean errorExists = false;
		try {
			jtaTransactionCoordinator.end(jtaTransactionContext, XAResource.TMSUCCESS);
			jtaTransactionCoordinator.commit(jtaTransactionXid, true);
			commitExists = true;
		} catch (XAException xaex) {
			switch (xaex.errorCode) {
			case XAException.XA_HEURRB:
				rollbackExists = true;
				break;
			case XAException.XA_HEURMIX:
				commitExists = true;
				rollbackExists = true;
				break;
			default:
				errorExists = true;
				break;
			}
		} finally {

			transaction.setTransactionalExtra(null);
			tccTransactionContext.setCompensating(true);

			if (compensable && coordinator) {
				boolean success = false;
				try {
					if (commitExists && rollbackExists) {
						// this.fireCompensableCommit(transaction);
						this.fireCompensableRollback(transaction);
						success = true;
					} else if (errorExists) {
						// this.fireCompensableCommit(transaction);
						this.fireCompensableRollback(transaction);
						success = true;
					} else if (commitExists) {
						this.fireCompensableCommit(transaction);
						success = true;
					} else if (rollbackExists) {
						this.fireCompensableRollback(transaction);
						success = true;
					} else {
						success = true;
					}
				} finally {
					TransactionXid xid = tccTransactionContext.getXid();
					if (success) {
						transactionRepository.removeTransaction(xid);
					} else {
						transactionRepository.putErrorTransaction(xid, transaction);
					}
				}
			}

		}

	}

	protected void commitTxInCompensePhase() throws RollbackException, HeuristicMixedException, HeuristicRollbackException,
			SecurityException, IllegalStateException, SystemException {
		// TODO
	}

	public void fireCompensableCommit(CompensableTransaction transaction) throws RollbackException, HeuristicMixedException,
			HeuristicRollbackException, SecurityException, IllegalStateException, SystemException {
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();
		try {
			compensableManager.associateThread(transaction);
			compensableManager.compensableCommit();
		} finally {
			compensableManager.desociateThread();
		}
	}

	public void rollback() throws IllegalStateException, SecurityException, SystemException {
		boolean compensating = this.isCompensePhaseCurrently();
		if (compensating) {
			this.rollbackTxInCompensePhase();
		} else {
			this.rollbackTxInTryingPhase();
		}
	}

	protected void rollbackTxInTryingPhase() throws IllegalStateException, SecurityException, SystemException {

		TransactionRepository transactionRepository = this.beanFactory.getTransactionRepository();
		RemoteCoordinator jtaTransactionCoordinator = this.beanFactory.getTransactionCoordinator();

		CompensableTransaction transaction = this.getCompensableTransactionQuietly();
		Transaction jtaTransaction = transaction.getTransaction();
		TransactionContext tccTransactionContext = transaction.getTransactionContext();
		TransactionContext jtaTransactionContext = jtaTransaction.getTransactionContext();

		TransactionXid jtaTransactionXid = jtaTransactionContext.getXid();

		boolean coordinator = tccTransactionContext.isCoordinator();
		boolean compensable = tccTransactionContext.isCompensable();
		if (coordinator) {
			this.desociateThread();
		}

		boolean success = false;
		try {
			jtaTransactionCoordinator.end(jtaTransactionContext, XAResource.TMSUCCESS);
			jtaTransactionCoordinator.rollback(jtaTransactionXid);
			success = true;
		} catch (XAException ex) {
			if (compensable && coordinator) {
				this.fireCompensableRollback(transaction);
				success = true;
			} else {
				success = false;
			}
		} finally {
			transaction.setTransactionalExtra(null);

			TransactionXid xid = tccTransactionContext.getXid();
			if (success) {
				transactionRepository.removeTransaction(xid);
			} else {
				transactionRepository.putErrorTransaction(xid, transaction);
			}
		}

	}

	protected void rollbackTxInCompensePhase() throws IllegalStateException, SecurityException, SystemException {
		// TODO
	}

	public void fireCompensableRollback(CompensableTransaction transaction) throws IllegalStateException, SecurityException,
			SystemException {
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();
		try {
			compensableManager.associateThread(transaction);
			compensableManager.compensableRollback();
		} finally {
			compensableManager.desociateThread();
		}
	}

	public boolean isCompensableTransaction() {
		CompensableTransaction transaction = (CompensableTransaction) this.compensableMap.get(Thread.currentThread());
		if (transaction == null) {
			return false;
		}
		TransactionContext transactionContext = transaction.getTransactionContext();
		return transactionContext.isCompensable();
	}

	public boolean isCompensePhaseCurrently() {
		CompensableTransaction transaction = (CompensableTransaction) this.compensableMap.get(Thread.currentThread());
		if (transaction == null) {
			return false;
		}
		return transaction.getTransactionContext().isCompensating();
	}

	public void compensableCommit() throws RollbackException, HeuristicMixedException, HeuristicRollbackException,
			SecurityException, IllegalStateException, SystemException {
		CompensableTransaction transaction = this.getCompensableTransactionQuietly();
		if (transaction == null) {
			throw new IllegalStateException();
		}
		transaction.commit();
	}

	public void compensableRollback() throws IllegalStateException, SecurityException, SystemException {
		CompensableTransaction transaction = this.getCompensableTransactionQuietly();
		if (transaction == null) {
			throw new IllegalStateException();
		}
		transaction.rollback();
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
