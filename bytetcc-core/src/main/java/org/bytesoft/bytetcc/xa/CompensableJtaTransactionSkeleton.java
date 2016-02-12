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
package org.bytesoft.bytetcc.xa;

import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;

public class CompensableJtaTransactionSkeleton implements XAResource {

	public void commit(Xid xid, boolean opc) throws XAException {
	}

	public void rollback(Xid xid) throws XAException {
	}

	public Xid[] recover(int arg0) throws XAException {
		return new Xid[0];
	}

	public void forget(Xid arg0) throws XAException {
	}

	public int getTransactionTimeout() throws XAException {
		throw new XAException(XAException.XAER_PROTO);
	}

	public boolean isSameRM(XAResource arg0) throws XAException {
		throw new XAException(XAException.XAER_PROTO);
	}

	public int prepare(Xid arg0) throws XAException {
		throw new XAException(XAException.XAER_PROTO);
	}

	public boolean setTransactionTimeout(int arg0) throws XAException {
		throw new XAException(XAException.XAER_PROTO);
	}

	public void start(Xid arg0, int arg1) throws XAException {
		throw new XAException(XAException.XAER_PROTO);
	}

	public void end(Xid arg0, int arg1) throws XAException {
		throw new XAException(XAException.XAER_PROTO);
	}

}
