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
import org.bytesoft.transaction.internal.TransactionException;
import org.bytesoft.transaction.xa.TransactionXid;
import org.bytesoft.transaction.xa.XidFactory;

/**
 * The compensable transaction manager implementation in the confirm/cancel phase.
 * 
 * @author liuyangming
 */
public class CompensableManagerImpl implements CompensableManager, CompensableBeanFactoryAware {
	static final Logger logger = Logger.getLogger(CompensableManagerImpl.class.getSimpleName());

	private CompensableBeanFactory beanFactory;
	private final Map<Thread, CompensableTransaction> transactionMap = new ConcurrentHashMap<Thread, CompensableTransaction>();

	public void associateThread(Transaction transaction) {
		this.transactionMap.put(Thread.currentThread(), (CompensableTransaction) transaction);
	}

	public CompensableTransaction desociateThread() {
		return this.transactionMap.remove(Thread.currentThread());
	}

	public int getStatus() throws SystemException {
		Transaction transaction = this.getTransactionQuietly();
		return transaction == null ? Status.STATUS_NO_TRANSACTION : transaction.getStatus();
	}

	public Transaction getTransactionQuietly() {
		try {
			return this.getTransaction();
		} catch (SystemException ex) {
			return null;
		} catch (RuntimeException ex) {
			return null;
		}
	}

	public Transaction getTransaction() throws SystemException {
		CompensableTransaction transaction = this.transactionMap.get(Thread.currentThread());
		return transaction == null ? null : transaction.getTransaction();
	}

	public CompensableTransaction getCompensableTransactionQuietly() {
		return this.transactionMap.get(Thread.currentThread());
	}

	public void resume(javax.transaction.Transaction tobj) throws InvalidTransactionException, IllegalStateException,
			SystemException {
		CompensableTransaction transaction = this.transactionMap.get(Thread.currentThread());
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
		CompensableTransaction transaction = this.transactionMap.get(Thread.currentThread());
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
		if (transaction == null) {
			throw new NotSupportedException();
		} else if (transaction.getTransaction() != null) {
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
		CompensableTransaction transaction = this.transactionMap.get(Thread.currentThread());
		if (transaction == null) {
			throw new IllegalStateException();
		} else if (transaction.getTransaction() == null) {
			throw new SystemException();
		}

		RemoteCoordinator jtaTransactionCoordinator = this.beanFactory.getTransactionCoordinator();

		Transaction jtaTransaction = transaction.getTransaction();

		TransactionContext jtaTransactionContext = jtaTransaction.getTransactionContext();
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

			if (commitExists && rollbackExists) {
				// TODO
			} else if (errorExists) {
				// TODO
			} else if (rollbackExists) {
				// TODO
			}
		}

	}

	public void rollback() throws IllegalStateException, SecurityException, SystemException {
		CompensableTransaction transaction = this.transactionMap.get(Thread.currentThread());
		if (transaction == null) {
			throw new IllegalStateException();
		} else if (transaction.getTransaction() == null) {
			throw new SystemException();
		}

		RemoteCoordinator jtaTransactionCoordinator = this.beanFactory.getTransactionCoordinator();
		Transaction jtaTransaction = transaction.getTransaction();
		TransactionContext jtaTransactionContext = jtaTransaction.getTransactionContext();
		TransactionXid jtaTransactionXid = jtaTransactionContext.getXid();
		// boolean failure = true;
		try {
			jtaTransactionCoordinator.end(jtaTransactionContext, XAResource.TMSUCCESS);
			jtaTransactionCoordinator.rollback(jtaTransactionXid);
			// failure = false;
		} catch (XAException ex) {
			// TODO logger
			// failure = true;
		} finally {
			transaction.setTransactionalExtra(null);

			// TODO
		}

	}

	public boolean isCompensePhaseCurrently() {
		CompensableTransaction transaction = this.transactionMap.get(Thread.currentThread());
		return transaction != null;
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
