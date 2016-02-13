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
package org.bytesoft.bytetcc.transaction;

import javax.transaction.HeuristicMixedException;
import javax.transaction.HeuristicRollbackException;
import javax.transaction.RollbackException;
import javax.transaction.Synchronization;
import javax.transaction.SystemException;
import javax.transaction.xa.XAResource;

import org.bytesoft.compensable.AbstractTransaction;
import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.transaction.Transaction;
import org.bytesoft.transaction.TransactionBeanFactory;
import org.bytesoft.transaction.TransactionContext;
import org.bytesoft.transaction.supports.TransactionListener;

public class JtaTransactionImpl extends AbstractTransaction {

	private CompensableBeanFactory beanFactory;
	private TccTransactionImpl compensableTransaction;

	public JtaTransactionImpl(TransactionContext transactionContext) {
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
		if (this.compensableTransaction != null) {
			this.compensableTransaction.prepareStart();
		}
	}

	public void prepareSuccess() {
		if (this.compensableTransaction != null) {
			this.compensableTransaction.prepareSuccess();
		}
	}

	public void prepareFailure() {
		if (this.compensableTransaction != null) {
			this.compensableTransaction.prepareFailure();
		}
	}

	public void commitStart() {
		if (this.compensableTransaction != null) {
			this.compensableTransaction.commitStart();
		}
	}

	public void commitSuccess() {
		if (this.compensableTransaction != null) {
			this.compensableTransaction.commitSuccess();
		}
	}

	public void commitFailure() {
		if (this.compensableTransaction != null) {
			this.compensableTransaction.commitFailure();
		}
	}

	public void commitHeuristicMixed() {
		if (this.compensableTransaction != null) {
			this.compensableTransaction.commitHeuristicMixed();
		}
	}

	public void commitHeuristicRolledback() {
		if (this.compensableTransaction != null) {
			this.compensableTransaction.commitHeuristicRolledback();
		}
	}

	public void rollbackStart() {
		if (this.compensableTransaction != null) {
			this.compensableTransaction.rollbackStart();
		}
	}

	public void rollbackSuccess() {
		if (this.compensableTransaction != null) {
			this.compensableTransaction.rollbackSuccess();
		}
	}

	public void rollbackFailure() {
		if (this.compensableTransaction != null) {
			this.compensableTransaction.rollbackFailure();
		}
	}

	public void setRollbackOnly() throws IllegalStateException, SystemException {
		this.jtaTransaction.setRollbackOnly();
	}

	public void setBeanFactory(TransactionBeanFactory beanFactory) {
		this.beanFactory = (CompensableBeanFactory) beanFactory;
	}

	public void registerTransactionListener(TransactionListener listener) {
	}

	public Transaction getJtaTransaction() {
		return jtaTransaction;
	}

	public void setJtaTransaction(Transaction jtaTransaction) {
		this.jtaTransaction = jtaTransaction;
	}

	public TccTransactionImpl getCompensableTccTransaction() {
		return compensableTransaction;
	}

	public void setCompensableTccTransaction(TccTransactionImpl compensableTransaction) {
		this.compensableTransaction = compensableTransaction;
	}

	public CompensableBeanFactory getBeanFactory() {
		return beanFactory;
	}

	public void setBeanFactory(CompensableBeanFactory beanFactory) {
		this.beanFactory = beanFactory;
	}

}
