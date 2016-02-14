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

import org.bytesoft.bytetcc.aware.CompensableBeanFactoryAware;
import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.CompensableManager;
import org.bytesoft.transaction.Transaction;
import org.bytesoft.transaction.TransactionManager;

public class CompensableTransactionManager implements TransactionManager, CompensableBeanFactoryAware {

	private CompensableBeanFactory beanFactory;

	public void begin() throws NotSupportedException, SystemException {
		TransactionManager transactionManager = this.beanFactory.getTransactionManager();
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();
		boolean compensable = compensableManager.isCurrentCompensable();
		if (compensable) {
			compensableManager.begin();
		} else {
			transactionManager.begin();
		}
	}

	public void commit() throws RollbackException, HeuristicMixedException, HeuristicRollbackException,
			SecurityException, IllegalStateException, SystemException {
		TransactionManager transactionManager = this.beanFactory.getTransactionManager();
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();
		boolean compensable = compensableManager.isCurrentCompensable();
		if (compensable) {
			compensableManager.commit();
		} else {
			transactionManager.commit();
		}
	}

	public int getStatus() throws SystemException {
		// TODO Auto-generated method stub
		return 0;
	}

	public void resume(javax.transaction.Transaction tobj) throws InvalidTransactionException, IllegalStateException,
			SystemException {
		// TODO Auto-generated method stub

	}

	public void rollback() throws IllegalStateException, SecurityException, SystemException {
		TransactionManager transactionManager = this.beanFactory.getTransactionManager();
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();
		boolean compensable = compensableManager.isCurrentCompensable();
		if (compensable) {
			compensableManager.rollback();
		} else {
			transactionManager.rollback();
		}
	}

	public void setRollbackOnly() throws IllegalStateException, SystemException {
		// TODO Auto-generated method stub

	}

	public void setTransactionTimeout(int seconds) throws SystemException {
		// TODO Auto-generated method stub

	}

	public int getTimeoutSeconds() {
		// TODO Auto-generated method stub
		return 0;
	}

	public void setTimeoutSeconds(int timeoutSeconds) {
		// TODO Auto-generated method stub

	}

	public void associateThread(Transaction transaction) {
		// TODO Auto-generated method stub

	}

	public Transaction desociateThread() {
		// TODO Auto-generated method stub
		return null;
	}

	public Transaction getTransactionQuietly() {
		// TODO Auto-generated method stub
		return null;
	}

	public Transaction getTransaction() throws SystemException {
		// TODO Auto-generated method stub
		return null;
	}

	public Transaction suspend() throws SystemException {
		// TODO Auto-generated method stub
		return null;
	}

	public void setBeanFactory(CompensableBeanFactory tbf) {
		this.beanFactory = tbf;
	}

}
