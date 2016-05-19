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

import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;

import org.bytesoft.bytejta.supports.wire.RemoteCoordinator;
import org.bytesoft.transaction.Transaction;
import org.bytesoft.transaction.TransactionContext;
import org.bytesoft.transaction.internal.TransactionException;
import org.bytesoft.transaction.xa.XidFactory;

public class TransactionCoordinator implements RemoteCoordinator {

	private RemoteCoordinator compensableCoordinator;
	private RemoteCoordinator transactionCoordinator;

	public void commit(Xid xid, boolean onePhase) throws XAException {
		int formatId = xid.getFormatId();
		if (XidFactory.JTA_FORMAT_ID == formatId) {
			this.transactionCoordinator.commit(xid, onePhase);
		} else if (XidFactory.TCC_FORMAT_ID == formatId) {
			this.compensableCoordinator.commit(xid, onePhase);
		} else {
			throw new XAException(XAException.XAER_INVAL);
		}
	}

	public void end(Xid xid, int flags) throws XAException {
		int formatId = xid.getFormatId();
		if (XidFactory.JTA_FORMAT_ID == formatId) {
			this.transactionCoordinator.end(xid, flags);
		} else if (XidFactory.TCC_FORMAT_ID == formatId) {
			this.compensableCoordinator.end(xid, flags);
		} else {
			throw new XAException(XAException.XAER_INVAL);
		}
	}

	public void forget(Xid xid) throws XAException {
		int formatId = xid.getFormatId();
		if (XidFactory.JTA_FORMAT_ID == formatId) {
			this.transactionCoordinator.forget(xid);
		} else if (XidFactory.TCC_FORMAT_ID == formatId) {
			this.compensableCoordinator.forget(xid);
		} else {
			throw new XAException(XAException.XAER_INVAL);
		}
	}

	public int getTransactionTimeout() throws XAException {
		return 0;
	}

	public boolean isSameRM(XAResource xares) throws XAException {
		// TODO Auto-generated method stub
		return false;
	}

	public int prepare(Xid xid) throws XAException {
		int formatId = xid.getFormatId();
		if (XidFactory.JTA_FORMAT_ID == formatId) {
			return this.transactionCoordinator.prepare(xid);
		} else if (XidFactory.TCC_FORMAT_ID == formatId) {
			return this.compensableCoordinator.prepare(xid);
		} else {
			throw new XAException(XAException.XAER_INVAL);
		}
	}

	public Xid[] recover(int flags) throws XAException {
		// TODO Auto-generated method stub
		return null;
	}

	public void rollback(Xid xid) throws XAException {
		int formatId = xid.getFormatId();
		if (XidFactory.JTA_FORMAT_ID == formatId) {
			this.transactionCoordinator.rollback(xid);
		} else if (XidFactory.TCC_FORMAT_ID == formatId) {
			this.compensableCoordinator.rollback(xid);
		} else {
			throw new XAException(XAException.XAER_INVAL);
		}
	}

	public boolean setTransactionTimeout(int arg0) throws XAException {
		// TODO Auto-generated method stub
		return false;
	}

	public void start(Xid xid, int flags) throws XAException {
		int formatId = xid.getFormatId();
		if (XidFactory.JTA_FORMAT_ID == formatId) {
			this.transactionCoordinator.start(xid, flags);
		} else if (XidFactory.TCC_FORMAT_ID == formatId) {
			this.compensableCoordinator.start(xid, flags);
		} else {
			throw new XAException(XAException.XAER_INVAL);
		}
	}

	public String getIdentifier() {
		throw new IllegalStateException();
	}

	public Transaction start(TransactionContext transactionContext, int flags) throws TransactionException {
		if (transactionContext.isCompensable()) {
			return this.compensableCoordinator.start(transactionContext, flags);
		} else {
			return this.transactionCoordinator.start(transactionContext, flags);
		}
	}

	public Transaction end(TransactionContext transactionContext, int flags) throws TransactionException {
		if (transactionContext.isCompensable()) {
			return this.compensableCoordinator.end(transactionContext, flags);
		} else {
			return this.transactionCoordinator.end(transactionContext, flags);
		}
	}

	public RemoteCoordinator getCompensableCoordinator() {
		return compensableCoordinator;
	}

	public void setCompensableCoordinator(RemoteCoordinator compensableCoordinator) {
		this.compensableCoordinator = compensableCoordinator;
	}

	public RemoteCoordinator getTransactionCoordinator() {
		return transactionCoordinator;
	}

	public void setTransactionCoordinator(RemoteCoordinator transactionCoordinator) {
		this.transactionCoordinator = transactionCoordinator;
	}

}
