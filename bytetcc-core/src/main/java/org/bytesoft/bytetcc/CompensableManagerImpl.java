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

import org.apache.log4j.Logger;
import org.bytesoft.bytetcc.aware.CompensableBeanFactoryAware;
import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.CompensableManager;
import org.bytesoft.compensable.CompensableTransaction;
import org.bytesoft.transaction.Transaction;

public class CompensableManagerImpl implements CompensableManager, CompensableBeanFactoryAware {
	static final Logger logger = Logger.getLogger(CompensableManagerImpl.class.getSimpleName());

	private CompensableBeanFactory beanFactory;
	private final Map<Thread, CompensableTransaction> transactionMap = new ConcurrentHashMap<Thread, CompensableTransaction>();

	public void associateThread(Transaction transaction) {
		this.transactionMap.put(Thread.currentThread(), (CompensableTransaction) transaction);
	}

	public Transaction desociateThread() {
		return this.transactionMap.get(Thread.currentThread());
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

	public void resume(javax.transaction.Transaction tobj) throws InvalidTransactionException, IllegalStateException,
			SystemException {
		CompensableTransaction transaction = this.transactionMap.get(Thread.currentThread());
		if (transaction != null && transaction.getTransaction() == null) {
			Transaction jtaTransaction = (Transaction) tobj;
			jtaTransaction.resume();
			transaction.setTransaction(jtaTransaction);
		}
	}

	public Transaction suspend() throws SystemException {
		CompensableTransaction transaction = this.transactionMap.get(Thread.currentThread());
		Transaction jtaTransaction = transaction == null ? null : transaction.getTransaction();
		if (jtaTransaction != null) {
			transaction.setTransaction(null);
			jtaTransaction.suspend();
		}
		return jtaTransaction;
	}

	public void begin() throws NotSupportedException, SystemException {
		// TODO Auto-generated method stub

	}

	public void commit() throws RollbackException, HeuristicMixedException, HeuristicRollbackException,
			SecurityException, IllegalStateException, SystemException {
		// TODO Auto-generated method stub

	}

	public void rollback() throws IllegalStateException, SecurityException, SystemException {
		// TODO Auto-generated method stub

	}

	public boolean isCurrentCompensable() {
		// TODO Auto-generated method stub
		return false;
	}

	public void compensableBegin(CompensableTransaction transaction) throws SystemException {
		// TODO Auto-generated method stub

	}

	public void compensableCommit(CompensableTransaction transaction) throws RollbackException,
			HeuristicMixedException, HeuristicRollbackException, SecurityException, IllegalStateException,
			SystemException {
		// TODO Auto-generated method stub

	}

	public void compensableRollback(CompensableTransaction transaction) throws IllegalStateException,
			SecurityException, SystemException {
		// TODO Auto-generated method stub

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
