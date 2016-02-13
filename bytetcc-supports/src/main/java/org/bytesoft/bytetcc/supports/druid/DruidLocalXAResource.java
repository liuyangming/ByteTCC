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
package org.bytesoft.bytetcc.supports.druid;

import java.sql.Connection;
import java.sql.SQLException;

import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;

public class DruidLocalXAResource implements XAResource {
	private Connection localTransaction;
	private Xid currentXid;
	private Xid suspendXid;
	private boolean suspendAutoCommit;
	private boolean originalAutoCommit;

	public DruidLocalXAResource() {
	}

	public DruidLocalXAResource(Connection localTransaction) {
		this.localTransaction = localTransaction;
	}

	public synchronized void start(Xid xid, int flags) throws XAException {
		if (xid == null) {
			throw new XAException();
		} else if (flags == XAResource.TMRESUME && this.suspendXid != null) {
			if (this.suspendXid.equals(xid)) {
				this.suspendXid = null;
				this.currentXid = xid;
				this.originalAutoCommit = this.suspendAutoCommit;
				this.suspendAutoCommit = true;
				return;
			} else {
				throw new XAException();
			}
		} else if (flags != XAResource.TMNOFLAGS) {
			throw new XAException();
		} else if (this.currentXid != null) {
			throw new XAException();
		} else {
			try {
				originalAutoCommit = localTransaction.getAutoCommit();
			} catch (SQLException ignored) {
				originalAutoCommit = true;
			}

			try {
				localTransaction.setAutoCommit(false);
			} catch (SQLException ex) {
				XAException xae = new XAException();
				xae.initCause(ex);
				throw xae;
			}

			this.currentXid = xid;
		}
	}

	public synchronized void end(Xid xid, int flags) throws XAException {
		if (xid == null) {
			throw new XAException();
		} else if (this.currentXid == null) {
			throw new XAException();
		} else if (!this.currentXid.equals(xid)) {
			throw new XAException();
		} else if (flags == XAResource.TMSUSPEND) {
			this.suspendXid = xid;
			this.suspendAutoCommit = this.originalAutoCommit;
			this.currentXid = null;
			this.originalAutoCommit = true;
		} else if (flags == XAResource.TMSUCCESS || flags == XAResource.TMFAIL) {
			// ignore
		} else {
			throw new XAException();
		}
	}

	public synchronized int prepare(Xid xid) {
		try {
			if (localTransaction.isReadOnly()) {
				localTransaction.setAutoCommit(originalAutoCommit);
				return XAResource.XA_RDONLY;
			}
		} catch (SQLException ex) {
			// ignore
		}
		return XAResource.XA_OK;
	}

	public synchronized void commit(Xid xid, boolean onePhase) throws XAException {
		if (xid == null) {
			throw new XAException();
		} else if (this.currentXid == null) {
			throw new XAException();
		} else if (!this.currentXid.equals(xid)) {
			throw new XAException();
		}

		try {
			if (localTransaction.isClosed()) {
				throw new XAException();
			} else if (!localTransaction.isReadOnly()) {
				localTransaction.commit();
			}
		} catch (SQLException ex) {
			XAException xae = new XAException();
			xae.initCause(ex);
			throw xae;
		} finally {
			this.resetXAResource();
		}
	}

	public synchronized void rollback(Xid xid) throws XAException {
		if (xid == null) {
			throw new XAException();
		} else if (this.currentXid == null) {
			throw new XAException();
		} else if (!this.currentXid.equals(xid)) {
			throw new XAException();
		}

		try {
			localTransaction.rollback();
		} catch (SQLException ex) {
			XAException xae = new XAException();
			xae.initCause(ex);
			throw xae;
		} finally {
			this.resetXAResource();
		}
	}

	private void resetXAResource() {
		try {
			localTransaction.setAutoCommit(originalAutoCommit);
		} catch (SQLException ex) {
		} finally {
			this.forget(this.currentXid);
		}
	}

	public boolean isSameRM(XAResource xares) {
		return this == xares;
	}

	public synchronized void forget(Xid xid) {
		if (xid == null || this.currentXid == null) {
			// ignore
		} else {
			this.currentXid = null;
			this.originalAutoCommit = true;
			this.localTransaction = null;
		}
	}

	public Xid[] recover(int flags) {
		return new Xid[0];
	}

	public int getTransactionTimeout() {
		return 0;
	}

	public boolean setTransactionTimeout(int transactionTimeout) {
		return false;
	}

	public void setLocalTransaction(Connection localTransaction) {
		this.localTransaction = localTransaction;
	}

}
