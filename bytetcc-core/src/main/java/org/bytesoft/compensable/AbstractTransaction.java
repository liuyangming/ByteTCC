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
package org.bytesoft.compensable;

import javax.transaction.HeuristicMixedException;
import javax.transaction.HeuristicRollbackException;
import javax.transaction.RollbackException;
import javax.transaction.SystemException;

import org.bytesoft.common.utils.CommonUtils;
import org.bytesoft.compensable.CompensableInvocation;
import org.bytesoft.transaction.CommitRequiredException;
import org.bytesoft.transaction.RollbackRequiredException;
import org.bytesoft.transaction.Transaction;
import org.bytesoft.transaction.TransactionContext;
import org.bytesoft.transaction.archive.TransactionArchive;
import org.bytesoft.transaction.supports.TransactionListener;
import org.bytesoft.transaction.xa.TransactionXid;

public abstract class AbstractTransaction implements Transaction, TransactionListener {
	protected Transaction jtaTransaction;
	protected TransactionContext transactionContext;
	private CompensableInvocation compensableObject;

	public AbstractTransaction() {
	}

	public AbstractTransaction(TransactionContext txContext) {
		this.transactionContext = txContext;
	}

	public void setRollbackOnlyQuietly() {
		try {
			this.setRollbackOnly();
		} catch (Exception ignore) {
			// ignore
		}
	}

	public int getTransactionStatus() {
		return 0;
	}

	public void setTransactionStatus(int status) {
	}

	public void suspend() throws SystemException {
	}

	public boolean isTiming() {
		return false;
	}

	public void setTransactionTimeout(int seconds) {
	}

	public TransactionArchive getTransactionArchive() {
		return null;
	}

	public void participantPrepare() throws RollbackRequiredException, CommitRequiredException {
	}

	public void participantCommit() throws RollbackException, HeuristicMixedException, HeuristicRollbackException,
			SecurityException, IllegalStateException, CommitRequiredException, SystemException {
	}

	public void recoveryForgetQuietly() {
	}

	public void recoveryRollback() throws RollbackRequiredException, SystemException {
	}

	public void recoveryCommit() throws CommitRequiredException, SystemException {
	}

	public int hashCode() {
		TransactionXid transactionXid = this.transactionContext == null ? null : this.transactionContext.getXid();
		int hash = transactionXid == null ? 0 : transactionXid.hashCode();
		return hash;
	}

	public boolean equals(Object obj) {
		if (obj == null) {
			return false;
		} else if (AbstractTransaction.class.isAssignableFrom(obj.getClass()) == false) {
			return false;
		}
		AbstractTransaction that = (AbstractTransaction) obj;
		TransactionContext thisContext = this.transactionContext;
		TransactionContext thatContext = that.transactionContext;
		TransactionXid thisXid = thisContext == null ? null : thisContext.getXid();
		TransactionXid thatXid = thatContext == null ? null : thatContext.getXid();
		return CommonUtils.equals(thisXid, thatXid);
	}

	public Transaction getJtaTransaction() {
		return jtaTransaction;
	}

	public void setJtaTransaction(Transaction jtaTransaction) {
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
