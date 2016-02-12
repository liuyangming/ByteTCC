/**
 * Copyright 2014-2015 yangming.liu<liuyangming@gmail.com>.
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
import javax.transaction.RollbackException;
import javax.transaction.Synchronization;
import javax.transaction.SystemException;
import javax.transaction.xa.XAResource;

import org.bytesoft.transaction.Transaction;
import org.bytesoft.transaction.TransactionBeanFactory;
import org.bytesoft.transaction.supports.TransactionListener;

public class CompensableJtaTransaction extends CompensableTransaction {

	private CompensableTransactionBeanFactory beanFactory;
	private CompensableTccTransaction compensableTccTransaction;

	public CompensableJtaTransaction(TransactionContext transactionContext) {
		super(transactionContext);
	}

	public void commit() throws RollbackException, HeuristicMixedException, HeuristicRollbackException,
			SecurityException, IllegalStateException, SystemException {
		this.jtaTransaction.commit();
	}

	public boolean delistResource(XAResource xaRes, int flag) throws IllegalStateException, SystemException {
		return this.jtaTransaction.delistResource(xaRes, flag);
	}

	public boolean enlistResource(XAResource xaRes) throws RollbackException, IllegalStateException, SystemException {
		return this.jtaTransaction.enlistResource(xaRes);
	}

	public int getStatus() throws SystemException {
		return this.jtaTransaction.getStatus();
	}

	public void registerSynchronization(Synchronization sync) throws RollbackException, IllegalStateException,
			SystemException {
		this.jtaTransaction.registerSynchronization(sync);
	}

	public void rollback() throws IllegalStateException, SystemException {
		this.jtaTransaction.rollback();
	}

	public void prepareStart() {
		if (this.compensableTccTransaction != null) {
			this.compensableTccTransaction.prepareStart();
		}
	}

	public void prepareSuccess() {
		if (this.compensableTccTransaction != null) {
			this.compensableTccTransaction.prepareSuccess();
		}
	}

	public void prepareFailure() {
		if (this.compensableTccTransaction != null) {
			this.compensableTccTransaction.prepareFailure();
		}
	}

	public void commitStart() {
		if (this.compensableTccTransaction != null) {
			this.compensableTccTransaction.commitStart();
		}
	}

	public void commitSuccess() {
		if (this.compensableTccTransaction != null) {
			this.compensableTccTransaction.commitSuccess();
		}
	}

	public void commitFailure() {
		if (this.compensableTccTransaction != null) {
			this.compensableTccTransaction.commitFailure();
		}
	}

	public void commitHeuristicMixed() {
		if (this.compensableTccTransaction != null) {
			this.compensableTccTransaction.commitHeuristicMixed();
		}
	}

	public void commitHeuristicRolledback() {
		if (this.compensableTccTransaction != null) {
			this.compensableTccTransaction.commitHeuristicRolledback();
		}
	}

	public void rollbackStart() {
		if (this.compensableTccTransaction != null) {
			this.compensableTccTransaction.rollbackStart();
		}
	}

	public void rollbackSuccess() {
		if (this.compensableTccTransaction != null) {
			this.compensableTccTransaction.rollbackSuccess();
		}
	}

	public void rollbackFailure() {
		if (this.compensableTccTransaction != null) {
			this.compensableTccTransaction.rollbackFailure();
		}
	}

	public void setRollbackOnly() throws IllegalStateException, SystemException {
		this.jtaTransaction.setRollbackOnly();
	}

	public void setBeanFactory(TransactionBeanFactory beanFactory) {
		this.beanFactory = (CompensableTransactionBeanFactory) beanFactory;
	}

	public void registerTransactionListener(TransactionListener listener) {
	}

	public Transaction getJtaTransaction() {
		return jtaTransaction;
	}

	public void setJtaTransaction(Transaction jtaTransaction) {
		this.jtaTransaction = jtaTransaction;
	}

	public CompensableTccTransaction getCompensableTccTransaction() {
		return compensableTccTransaction;
	}

	public void setCompensableTccTransaction(CompensableTccTransaction compensableTccTransaction) {
		this.compensableTccTransaction = compensableTccTransaction;
	}

	public CompensableTransactionBeanFactory getBeanFactory() {
		return beanFactory;
	}

	public void setBeanFactory(CompensableTransactionBeanFactory beanFactory) {
		this.beanFactory = beanFactory;
	}

}
