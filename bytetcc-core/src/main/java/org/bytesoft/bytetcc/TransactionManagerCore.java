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
import javax.transaction.SystemException;

import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.CompensableInvocation;
import org.bytesoft.compensable.CompensableInvocationRegistry;
import org.bytesoft.compensable.CompensableManager;
import org.bytesoft.compensable.CompensableTransaction;
import org.bytesoft.compensable.aware.CompensableBeanFactoryAware;
import org.bytesoft.transaction.Transaction;
import org.bytesoft.transaction.TransactionContext;
import org.bytesoft.transaction.TransactionManager;

public class TransactionManagerCore implements TransactionManager, CompensableBeanFactoryAware {

	private CompensableBeanFactory beanFactory;

	public void begin() throws NotSupportedException, SystemException {
		TransactionManager transactionManager = this.beanFactory.getTransactionManager();
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();

		CompensableInvocationRegistry registry = CompensableInvocationRegistry.getInstance();
		CompensableInvocation invocation = registry.getCurrent();

		CompensableTransaction compensableTransaction = compensableManager.getCompensableTransactionQuietly();

		if (invocation != null && invocation.isAvailable()) {
			invocation.markUnavailable();

			compensableManager.begin();
			Transaction transaction = compensableManager.getTransactionQuietly();
			TransactionContext transactionContext = transaction.getTransactionContext();

			transactionContext.setCompensable(true);
			((CompensableTransaction) transaction).registerCompensableInvocation(invocation);
		} else {
			transactionManager.begin();
			Transaction transaction = compensableManager.getTransactionQuietly();
			transaction.setTransactionalExtra(compensableTransaction);
			compensableTransaction.setTransactionalExtra(transaction);
		}

	}

	public void commit() throws RollbackException, HeuristicMixedException, HeuristicRollbackException, SecurityException,
			IllegalStateException, SystemException {
		TransactionManager transactionManager = this.beanFactory.getTransactionManager();
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();
		boolean isCompensableTransaction = compensableManager.isCompensableTransaction();
		(isCompensableTransaction ? compensableManager : transactionManager).commit();
	}

	public int getStatus() throws SystemException {
		TransactionManager transactionManager = this.beanFactory.getTransactionManager();
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();
		boolean isCompensableTransaction = compensableManager.isCompensableTransaction();
		return (isCompensableTransaction ? compensableManager : transactionManager).getStatus();
	}

	public void resume(javax.transaction.Transaction tobj) throws InvalidTransactionException, IllegalStateException,
			SystemException {
		TransactionManager transactionManager = this.beanFactory.getTransactionManager();
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();
		boolean isCompensableTransaction = compensableManager.isCompensableTransaction();
		(isCompensableTransaction ? compensableManager : transactionManager).resume(tobj);
	}

	public void rollback() throws IllegalStateException, SecurityException, SystemException {
		TransactionManager transactionManager = this.beanFactory.getTransactionManager();
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();
		boolean isCompensableTransaction = compensableManager.isCompensableTransaction();
		(isCompensableTransaction ? compensableManager : transactionManager).rollback();
	}

	public void setRollbackOnly() throws IllegalStateException, SystemException {
		TransactionManager transactionManager = this.beanFactory.getTransactionManager();
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();
		boolean isCompensableTransaction = compensableManager.isCompensableTransaction();
		(isCompensableTransaction ? compensableManager : transactionManager).setRollbackOnly();
	}

	public void setTransactionTimeout(int seconds) throws SystemException {
		TransactionManager transactionManager = this.beanFactory.getTransactionManager();
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();
		boolean isCompensableTransaction = compensableManager.isCompensableTransaction();
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
		TransactionManager transactionManager = this.beanFactory.getTransactionManager();
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();
		boolean isCompensableTransaction = compensableManager.isCompensableTransaction();
		return (isCompensableTransaction ? compensableManager : transactionManager).getTransactionQuietly();
	}

	public Transaction getTransaction() throws SystemException {
		TransactionManager transactionManager = this.beanFactory.getTransactionManager();
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();
		boolean isCompensableTransaction = compensableManager.isCompensableTransaction();
		return (isCompensableTransaction ? compensableManager : transactionManager).getTransaction();
	}

	public Transaction suspend() throws SystemException {
		TransactionManager transactionManager = this.beanFactory.getTransactionManager();
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();
		boolean isCompensableTransaction = compensableManager.isCompensableTransaction();
		return (isCompensableTransaction ? compensableManager : transactionManager).suspend();
	}

	public void setBeanFactory(CompensableBeanFactory tbf) {
		this.beanFactory = tbf;
	}

}
