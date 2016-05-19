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

import javax.transaction.HeuristicMixedException;
import javax.transaction.HeuristicRollbackException;
import javax.transaction.InvalidTransactionException;
import javax.transaction.NotSupportedException;
import javax.transaction.RollbackException;
import javax.transaction.Status;
import javax.transaction.SystemException;
import javax.transaction.xa.XAResource;

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
import org.bytesoft.transaction.internal.TransactionException;
import org.bytesoft.transaction.xa.TransactionXid;
import org.bytesoft.transaction.xa.XidFactory;

public class TransactionManagerCore implements TransactionManager, CompensableBeanFactoryAware {

	private CompensableBeanFactory beanFactory;

	public void begin() throws NotSupportedException, SystemException {
		TransactionManager transactionManager = this.beanFactory.getTransactionManager();
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();
		RemoteCoordinator transactionCoordinator = this.beanFactory.getTransactionCoordinator();

		CompensableInvocationRegistry registry = CompensableInvocationRegistry.getInstance();
		CompensableInvocation invocation = registry.getCurrent();

		CompensableTransaction tccTransaction = compensableManager.getCompensableTransactionQuietly();
		if (invocation != null && invocation.isAvailable()) {
			invocation.markUnavailable();

			if (tccTransaction == null) {
				compensableManager.begin();
				tccTransaction = compensableManager.getCompensableTransactionQuietly();
				TransactionContext transactionContext = tccTransaction.getTransactionContext();
				transactionContext.setCompensable(true);
			} else {
				XidFactory jtaXidFactory = this.beanFactory.getTransactionXidFactory();
				TransactionContext tccTransactionContext = tccTransaction.getTransactionContext();
				TransactionXid tccTransactionXid = tccTransactionContext.getXid();
				TransactionXid jtaTransactionXid = jtaXidFactory.createGlobalXid(tccTransactionXid.getGlobalTransactionId());
				TransactionContext jtaTransactionContext = tccTransactionContext.clone();
				jtaTransactionContext.setXid(jtaTransactionXid);
				try {
					Transaction jtaTransaction = transactionCoordinator.start(jtaTransactionContext, XAResource.TMNOFLAGS);
					jtaTransaction.setTransactionalExtra(tccTransaction);
					tccTransaction.setTransactionalExtra(jtaTransaction);
				} catch (TransactionException ex) {
					// TODO Auto-generated catch block
					ex.printStackTrace();
				}
			}

			tccTransaction.registerCompensableInvocation(invocation);
		} else if (tccTransaction == null) {
			transactionManager.begin(); // TODO
		} else {
			XidFactory jtaXidFactory = this.beanFactory.getTransactionXidFactory();
			TransactionContext tccTransactionContext = tccTransaction.getTransactionContext();
			TransactionXid tccTransactionXid = tccTransactionContext.getXid();
			TransactionXid jtaTransactionXid = jtaXidFactory.createGlobalXid(tccTransactionXid.getGlobalTransactionId());
			TransactionContext jtaTransactionContext = tccTransactionContext.clone();
			jtaTransactionContext.setXid(jtaTransactionXid);

			try {
				Transaction transaction = transactionCoordinator.start(jtaTransactionContext, XAResource.TMNOFLAGS);
				transaction.setTransactionalExtra(tccTransaction);
				tccTransaction.setTransactionalExtra(transaction);
			} catch (TransactionException ex) {
				// TODO Auto-generated catch block
				ex.printStackTrace();
			}

		}

	}

	public void commit() throws RollbackException, HeuristicMixedException, HeuristicRollbackException, SecurityException,
			IllegalStateException, SystemException {
		TransactionManager transactionManager = this.beanFactory.getTransactionManager();
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();

		Transaction transaction = this.getTransaction();
		if (transaction == null) {
			throw new IllegalStateException();
		}
		TransactionContext transactionContext = transaction.getTransactionContext();
		boolean isCompensableTransaction = transactionContext.isCompensable();
		(isCompensableTransaction ? compensableManager : transactionManager).commit();
	}

	public int getStatus() throws SystemException {
		Transaction transaction = this.getTransaction();
		return transaction == null ? Status.STATUS_NO_TRANSACTION : transaction.getTransactionStatus();
	}

	public void resume(javax.transaction.Transaction tobj) throws InvalidTransactionException, IllegalStateException,
			SystemException {
		TransactionManager transactionManager = this.beanFactory.getTransactionManager();
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();

		Transaction transaction = this.getTransaction();
		if (transaction == null) {
			throw new IllegalStateException();
		}
		TransactionContext transactionContext = transaction.getTransactionContext();
		boolean isCompensableTransaction = transactionContext.isCompensable();
		(isCompensableTransaction ? compensableManager : transactionManager).resume(tobj);
	}

	public void rollback() throws IllegalStateException, SecurityException, SystemException {
		TransactionManager transactionManager = this.beanFactory.getTransactionManager();
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();

		Transaction transaction = this.getTransaction();
		if (transaction == null) {
			throw new IllegalStateException();
		}
		TransactionContext transactionContext = transaction.getTransactionContext();
		boolean isCompensableTransaction = transactionContext.isCompensable();
		(isCompensableTransaction ? compensableManager : transactionManager).rollback();
	}

	public void setRollbackOnly() throws IllegalStateException, SystemException {
		TransactionManager transactionManager = this.beanFactory.getTransactionManager();
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();

		Transaction transaction = this.getTransaction();
		if (transaction == null) {
			throw new IllegalStateException();
		}
		TransactionContext transactionContext = transaction.getTransactionContext();
		boolean isCompensableTransaction = transactionContext.isCompensable();
		(isCompensableTransaction ? compensableManager : transactionManager).setRollbackOnly();
	}

	public void setTransactionTimeout(int seconds) throws SystemException {
		TransactionManager transactionManager = this.beanFactory.getTransactionManager();
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();

		Transaction transaction = this.getTransaction();
		if (transaction == null) {
			throw new SystemException();
		}
		TransactionContext transactionContext = transaction.getTransactionContext();
		boolean isCompensableTransaction = transactionContext.isCompensable();
		(isCompensableTransaction ? compensableManager : transactionManager).setTransactionTimeout(seconds);
	}

	public int getTimeoutSeconds() {
		throw new IllegalStateException();
	}

	public void setTimeoutSeconds(int timeoutSeconds) {
		throw new IllegalStateException();
	}

	public void associateThread(Transaction transaction) {
		throw new IllegalStateException();
	}

	public Transaction desociateThread() {
		throw new IllegalStateException();
	}

	public Transaction getTransactionQuietly() {
		try {
			return this.getTransaction();
		} catch (Exception ex) {
			return null;
		}
	}

	public Transaction getTransaction() throws SystemException {
		TransactionManager transactionManager = this.beanFactory.getTransactionManager();
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();
		Transaction jtaTransaction = transactionManager.getTransactionQuietly();
		Transaction tccTransaction = compensableManager.getCompensableTransactionQuietly();
		if (jtaTransaction != null) {
			return jtaTransaction;
		} else if (tccTransaction != null) {
			return ((CompensableTransaction) tccTransaction).getTransaction();
		} else {
			return null;
		}
	}

	public Transaction suspend() throws SystemException {
		TransactionManager transactionManager = this.beanFactory.getTransactionManager();
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();

		Transaction transaction = this.getTransaction();
		if (transaction == null) {
			throw new IllegalStateException();
		}
		TransactionContext transactionContext = transaction.getTransactionContext();
		boolean isCompensableTransaction = transactionContext.isCompensable();
		return (isCompensableTransaction ? compensableManager : transactionManager).suspend();
	}

	public void setBeanFactory(CompensableBeanFactory tbf) {
		this.beanFactory = tbf;
	}

}
