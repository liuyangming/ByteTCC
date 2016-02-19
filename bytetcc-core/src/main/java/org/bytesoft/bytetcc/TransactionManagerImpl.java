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
import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.CompensableInvocation;
import org.bytesoft.compensable.CompensableInvocationRegistry;
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

public class TransactionManagerImpl implements TransactionManager, CompensableBeanFactoryAware {
	static final Logger logger = Logger.getLogger(TransactionManagerImpl.class.getSimpleName());

	private CompensableBeanFactory beanFactory;
	private final Map<Thread, CompensableTransaction> transactionMap = new ConcurrentHashMap<Thread, CompensableTransaction>();

	public void begin() throws NotSupportedException, SystemException {

		RemoteCoordinator jtaTransactionCoordinator = this.beanFactory.getTransactionCoordinator();
		XidFactory jtaXidFactory = this.beanFactory.getTransactionXidFactory();
		XidFactory tccXidFactory = this.beanFactory.getCompensableXidFactory();

		TransactionXid tccTransactionXid = tccXidFactory.createGlobalXid();
		TransactionXid jtaTransactionXid = jtaXidFactory.createGlobalXid(tccTransactionXid.getGlobalTransactionId());

		TransactionContext transactionContext = new TransactionContext();
		transactionContext.setXid(tccTransactionXid);
		CompensableTransaction transaction = new CompensableTransactionImpl(transactionContext);

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
			// TODO
			throw new SystemException();
		}

		CompensableInvocationRegistry registry = CompensableInvocationRegistry.getInstance();
		CompensableInvocation invocation = registry.getCurrent();
		if (invocation != null && invocation.isAvailable()) {
			invocation.markUnavailable();
			transactionContext.setCompensable(true);

			// TODO logger
		}

		TransactionRepository transactionRepository = this.beanFactory.getTransactionRepository();
		this.associateThread(transaction);
		transactionRepository.putTransaction(tccTransactionXid, transaction);

	}

	public void commit() throws RollbackException, HeuristicMixedException, HeuristicRollbackException,
			SecurityException, IllegalStateException, SystemException {
		RemoteCoordinator jtaTransactionCoordinator = this.beanFactory.getTransactionCoordinator();

		CompensableTransaction transaction = this.desociateThread();
		TransactionContext transactionContext = transaction.getTransactionContext();

		Transaction jtaTransaction = transaction.getTransaction();
		TransactionContext jtaTransactionContext = jtaTransaction.getTransactionContext();
		TransactionXid jtaTransactionXid = jtaTransactionContext.getXid();
		boolean commitExists = false;
		boolean rollbackExists = false;
		boolean errorExists = false;
		try {
			jtaTransactionCoordinator.commit(jtaTransactionXid, false);
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
			if (transactionContext.isCompensable()) {
				if (commitExists && rollbackExists) {
					this.notifyCompensableCommit(transaction); // TODO
				} else if (errorExists) {
					this.notifyCompensableCommit(transaction); // TODO
				} else if (rollbackExists) {
					this.notifyCompensableRollback(transaction);
				}
			}
		}

	}

	public void notifyCompensableCommit(CompensableTransaction transaction) throws RollbackException,
			HeuristicMixedException, HeuristicRollbackException, SecurityException, IllegalStateException,
			SystemException {
		TransactionContext transactionContext = transaction.getTransactionContext();
		TransactionXid xid = transactionContext.getXid();

		CompensableManager compensableManager = this.beanFactory.getCompensableManager();
		TransactionRepository transactionRepository = this.beanFactory.getTransactionRepository();
		try {
			compensableManager.compensableBegin(transaction);
		} catch (SystemException ex) {
			transactionRepository.putErrorTransaction(xid, transaction);
			throw ex;
		} catch (RuntimeException ex) {
			transactionRepository.putErrorTransaction(xid, transaction);
			throw ex;
		}

		compensableManager.compensableCommit();
	}

	public void rollback() throws IllegalStateException, SecurityException, SystemException {
		RemoteCoordinator jtaTransactionCoordinator = this.beanFactory.getTransactionCoordinator();

		CompensableTransaction transaction = this.desociateThread();
		TransactionContext transactionContext = transaction.getTransactionContext();

		Transaction jtaTransaction = transaction.getTransaction();
		TransactionContext jtaTransactionContext = jtaTransaction.getTransactionContext();
		TransactionXid jtaTransactionXid = jtaTransactionContext.getXid();
		// boolean failure = true;
		try {
			jtaTransactionCoordinator.rollback(jtaTransactionXid);
			// failure = false;
		} catch (XAException ex) {
			// TODO logger
			// failure = true;
		} finally {
			if (transactionContext.isCompensable()) {
				this.notifyCompensableRollback(transaction);
			}
		}

	}

	public void notifyCompensableRollback(CompensableTransaction transaction) throws IllegalStateException,
			SecurityException, SystemException {
		TransactionContext transactionContext = transaction.getTransactionContext();
		TransactionXid xid = transactionContext.getXid();

		CompensableManager compensableManager = this.beanFactory.getCompensableManager();
		TransactionRepository transactionRepository = this.beanFactory.getTransactionRepository();
		try {
			compensableManager.compensableBegin(transaction);
		} catch (SystemException ex) {
			transactionRepository.putErrorTransaction(xid, transaction);
			throw ex;
		} catch (RuntimeException ex) {
			transactionRepository.putErrorTransaction(xid, transaction);
			throw ex;
		}

		compensableManager.compensableRollback();
	}

	public int getStatus() throws SystemException {
		CompensableTransaction transaction = this.transactionMap.get(Thread.currentThread());
		Transaction jtaTransaction = transaction == null ? null : transaction.getTransaction();
		return jtaTransaction == null ? Status.STATUS_NO_TRANSACTION : jtaTransaction.getStatus();
	}

	public Transaction suspend() throws SystemException {
		Transaction transaction = this.desociateThread();
		transaction.suspend();
		return transaction;
	}

	public void resume(javax.transaction.Transaction tobj) throws InvalidTransactionException, IllegalStateException,
			SystemException {

		if (CompensableTransaction.class.isInstance(tobj) == false) {
			throw new InvalidTransactionException();
		} else if (this.getTransaction() != null) {
			throw new IllegalStateException();
		}

		CompensableTransaction transaction = (CompensableTransaction) tobj;
		transaction.resume();
		this.associateThread(transaction);
	}

	public void associateThread(Transaction transaction) {
		this.transactionMap.put(Thread.currentThread(), (CompensableTransaction) transaction);
	}

	public CompensableTransaction desociateThread() {
		return this.transactionMap.remove(Thread.currentThread());
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
		return this.transactionMap.get(Thread.currentThread());
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
