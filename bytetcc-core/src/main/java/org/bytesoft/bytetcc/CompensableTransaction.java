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

import javax.transaction.Transaction;

import org.bytesoft.bytejta.TransactionImpl;
import org.bytesoft.bytejta.utils.CommonUtils;
import org.bytesoft.transaction.TransactionContext;
import org.bytesoft.transaction.TransactionListener;
import org.bytesoft.transaction.xa.TransactionXid;

public abstract class CompensableTransaction implements Transaction, TransactionListener {
	protected TransactionImpl jtaTransaction;
	protected TransactionContext transactionContext;
	private CompensableInvocation compensableObject;

	public CompensableTransaction() {
	}

	public CompensableTransaction(TransactionContext txContext) {
		this.transactionContext = txContext;
	}

	public void setRollbackOnlyQuietly() {
		try {
			this.setRollbackOnly();
		} catch (Exception ignore) {
			// ignore
		}
	}

	public int hashCode() {
		TransactionXid transactionXid = this.transactionContext == null ? null : this.transactionContext.getGlobalXid();
		int hash = transactionXid == null ? 0 : transactionXid.hashCode();
		return hash;
	}

	public boolean equals(Object obj) {
		if (obj == null) {
			return false;
		} else if (CompensableTransaction.class.isAssignableFrom(obj.getClass()) == false) {
			return false;
		}
		CompensableTransaction that = (CompensableTransaction) obj;
		TransactionContext thisContext = this.transactionContext;
		TransactionContext thatContext = that.transactionContext;
		TransactionXid thisXid = thisContext == null ? null : thisContext.getGlobalXid();
		TransactionXid thatXid = thatContext == null ? null : thatContext.getGlobalXid();
		return CommonUtils.equals(thisXid, thatXid);
	}

	public TransactionImpl getJtaTransaction() {
		return jtaTransaction;
	}

	public void setJtaTransaction(TransactionImpl jtaTransaction) {
		this.jtaTransaction = jtaTransaction;
	}

	public CompensableInvocation getCompensableObject() {
		return compensableObject;
	}

	public void setCompensableObject(CompensableInvocation compensableObject) {
		this.compensableObject = compensableObject;
	}

	public TransactionContext getTransactionContext() {
		return transactionContext;
	}

	public void setTransactionContext(TransactionContext transactionContext) {
		this.transactionContext = transactionContext;
	}

}
