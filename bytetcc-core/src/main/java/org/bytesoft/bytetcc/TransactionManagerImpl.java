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

import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.CompensableInvocation;
import org.bytesoft.compensable.CompensableInvocationRegistry;
import org.bytesoft.compensable.CompensableManager;
import org.bytesoft.compensable.CompensableTransaction;
import org.bytesoft.compensable.aware.CompensableBeanFactoryAware;
import org.bytesoft.transaction.Transaction;
import org.bytesoft.transaction.TransactionContext;
import org.bytesoft.transaction.TransactionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TransactionManagerImpl implements TransactionManager, CompensableBeanFactoryAware {
	static final Logger logger = LoggerFactory.getLogger(TransactionManagerImpl.class);

	private CompensableBeanFactory beanFactory;

	public void begin() throws NotSupportedException, SystemException {
		TransactionManager transactionManager = this.beanFactory.getTransactionManager();
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();

		CompensableTransaction transaction = compensableManager.getCompensableTransactionQuietly();

		CompensableInvocationRegistry registry = CompensableInvocationRegistry.getInstance();
		CompensableInvocation invocation = registry.getCurrent();

		if (transaction != null) {
			compensableManager.begin();
		} else if (invocation != null) {
			compensableManager.compensableBegin();
		} else {
			transactionManager.begin();
		}

	}

	public void commit() throws RollbackException, HeuristicMixedException, HeuristicRollbackException,
			SecurityException, IllegalStateException, SystemException {
		TransactionManager transactionManager = this.beanFactory.getTransactionManager();
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();

		Transaction transaction = transactionManager.getTransactionQuietly();
		Transaction compensable = compensableManager.getCompensableTransactionQuietly();

		TransactionContext transactionContext = null;
		if (transaction == null && compensable == null) {
			throw new IllegalStateException();
		} else if (compensable == null) {
			transactionContext = transaction.getTransactionContext();
		} else {
			transactionContext = compensable.getTransactionContext();
		}

		if (org.bytesoft.compensable.TransactionContext.class.isInstance(transactionContext)) {
			org.bytesoft.compensable.TransactionContext compensableContext = //
			(org.bytesoft.compensable.TransactionContext) transactionContext;
			if (compensableContext.isRecoveried()) {
				if (compensableContext.isCompensable() == false) {
					throw new IllegalStateException();
				}
				compensableManager.commit();
			} else if (compensableContext.isCompensable() == false) {
				transactionManager.commit();
			} else if (compensableContext.isCompensating()) {
				compensableManager.commit();
			} else if (transactionContext.isCoordinator()) {
				if (transactionContext.isPropagated()) {
					compensableManager.commit();
				} else if (compensableContext.getPropagationLevel() > 0) {
					compensableManager.commit();
				} else {
					compensableManager.compensableCommit();
				}
			} else {
				compensableManager.commit();
			}
		} else {
			transactionManager.commit();
		}

	}

	public void rollback() throws IllegalStateException, SecurityException, SystemException {
		TransactionManager transactionManager = this.beanFactory.getTransactionManager();
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();

		Transaction transaction = transactionManager.getTransactionQuietly();
		Transaction compensable = compensableManager.getCompensableTransactionQuietly();

		TransactionContext transactionContext = null;
		if (transaction == null && compensable == null) {
			throw new IllegalStateException();
		} else if (compensable == null) {
			transactionContext = transaction.getTransactionContext();
		} else {
			transactionContext = compensable.getTransactionContext();
		}

		if (org.bytesoft.compensable.TransactionContext.class.isInstance(transactionContext)) {
			org.bytesoft.compensable.TransactionContext compensableContext = //
			(org.bytesoft.compensable.TransactionContext) transactionContext;
			if (compensableContext.isRecoveried()) {
				if (compensableContext.isCompensable() == false) {
					throw new IllegalStateException();
				}
				compensableManager.rollback();
			} else if (compensableContext.isCompensable() == false) {
				transactionManager.rollback();
			} else if (compensableContext.isCoordinator()) {
				if (compensableContext.isPropagated()) {
					compensableManager.rollback();
				} else if (compensableContext.getPropagationLevel() > 0) {
					compensableManager.rollback();
				} else {
					compensableManager.compensableRollback();
				}
			} else {
				compensableManager.rollback();
			}
		} else {
			transactionManager.rollback();
		}

	}

	public Transaction suspend() throws SystemException {
		TransactionManager transactionManager = this.beanFactory.getTransactionManager();
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();

		TransactionContext transactionContext = null;
		Transaction transaction = transactionManager.getTransactionQuietly();
		Transaction compensable = compensableManager.getCompensableTransactionQuietly();
		if (transaction == null && compensable == null) {
			throw new SystemException();
		} else if (compensable == null) {
			transactionContext = transaction.getTransactionContext();
		} else {
			transactionContext = compensable.getTransactionContext();
		}

		boolean isCompensableTransaction = false;
		if (org.bytesoft.compensable.TransactionContext.class.isInstance(transactionContext)) {
			org.bytesoft.compensable.TransactionContext compensableContext = //
			(org.bytesoft.compensable.TransactionContext) transactionContext;
			isCompensableTransaction = compensableContext.isCompensable();
		}
		return (isCompensableTransaction ? compensableManager : transactionManager).suspend();
	}

	public void resume(javax.transaction.Transaction tobj) throws InvalidTransactionException, IllegalStateException,
			SystemException {
		TransactionManager transactionManager = this.beanFactory.getTransactionManager();
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();

		TransactionContext transactionContext = null;
		Transaction transaction = transactionManager.getTransactionQuietly();
		Transaction compensable = compensableManager.getCompensableTransactionQuietly();
		if (transaction == null && compensable == null) {
			throw new SystemException();
		} else if (compensable == null) {
			transactionContext = transaction.getTransactionContext();
		} else {
			transactionContext = compensable.getTransactionContext();
		}

		boolean isCompensableTransaction = false;
		if (org.bytesoft.compensable.TransactionContext.class.isInstance(transactionContext)) {
			org.bytesoft.compensable.TransactionContext compensableContext = //
			(org.bytesoft.compensable.TransactionContext) transactionContext;
			isCompensableTransaction = compensableContext.isCompensable();
		}
		(isCompensableTransaction ? compensableManager : transactionManager).resume(tobj);
	}

	public void setRollbackOnly() throws IllegalStateException, SystemException {
		TransactionManager transactionManager = this.beanFactory.getTransactionManager();
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();

		TransactionContext transactionContext = null;
		Transaction transaction = transactionManager.getTransactionQuietly();
		Transaction compensable = compensableManager.getCompensableTransactionQuietly();
		if (transaction == null && compensable == null) {
			throw new IllegalStateException();
		} else if (compensable == null) {
			transactionContext = transaction.getTransactionContext();
		} else {
			transactionContext = compensable.getTransactionContext();
		}

		boolean isCompensableTransaction = false;
		if (org.bytesoft.compensable.TransactionContext.class.isInstance(transactionContext)) {
			org.bytesoft.compensable.TransactionContext compensableContext = //
			(org.bytesoft.compensable.TransactionContext) transactionContext;
			isCompensableTransaction = compensableContext.isCompensable();
		}
		(isCompensableTransaction ? compensableManager : transactionManager).setRollbackOnly();
	}

	public void setTransactionTimeout(int seconds) throws SystemException {
		TransactionManager transactionManager = this.beanFactory.getTransactionManager();
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();

		TransactionContext transactionContext = null;
		Transaction transaction = transactionManager.getTransactionQuietly();
		Transaction compensable = compensableManager.getCompensableTransactionQuietly();
		if (transaction == null && compensable == null) {
			throw new IllegalStateException();
		} else if (compensable == null) {
			transactionContext = transaction.getTransactionContext();
		} else {
			transactionContext = compensable.getTransactionContext();
		}

		boolean isCompensableTransaction = false;
		if (org.bytesoft.compensable.TransactionContext.class.isInstance(transactionContext)) {
			org.bytesoft.compensable.TransactionContext compensableContext = //
			(org.bytesoft.compensable.TransactionContext) transactionContext;
			isCompensableTransaction = compensableContext.isCompensable();
		}
		(isCompensableTransaction ? compensableManager : transactionManager).setTransactionTimeout(seconds);
	}

	public Transaction getTransaction() throws SystemException {
		TransactionManager transactionManager = this.beanFactory.getTransactionManager();
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();
		Transaction transaction = transactionManager.getTransactionQuietly();
		Transaction compensable = compensableManager.getCompensableTransactionQuietly();
		if (transaction != null) {
			return transaction;
		} else if (compensable != null) {
			return ((CompensableTransaction) compensable).getTransaction();
		} else {
			return null;
		}
	}

	public Transaction getTransactionQuietly() {
		try {
			return this.getTransaction();
		} catch (Exception ex) {
			return null;
		}
	}

	public int getStatus() throws SystemException {
		Transaction transaction = this.getTransaction();
		return transaction == null ? Status.STATUS_NO_TRANSACTION : transaction.getTransactionStatus();
	}

	public void setBeanFactory(CompensableBeanFactory tbf) {
		this.beanFactory = tbf;
	}

	public void associateThread(Transaction transaction) {
		throw new IllegalStateException();
	}

	public Transaction desociateThread() {
		throw new IllegalStateException();
	}

	public int getTimeoutSeconds() {
		throw new IllegalStateException();
	}

	public void setTimeoutSeconds(int timeoutSeconds) {
		throw new IllegalStateException();
	}
}
