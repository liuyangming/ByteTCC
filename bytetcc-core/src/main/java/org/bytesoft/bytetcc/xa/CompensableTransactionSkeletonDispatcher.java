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

import org.bytesoft.bytetcc.CompensableTransaction;
import org.bytesoft.bytetcc.common.TransactionConfigurator;
import org.bytesoft.bytetcc.common.TransactionRepository;
import org.bytesoft.transaction.TransactionContext;
import org.bytesoft.transaction.xa.TransactionXid;

public class CompensableTransactionSkeletonDispatcher implements XAResource {

	private XAResource jtaTransactionSkeleton;
	private XAResource tccTransactionSkeleton;

	public void commit(Xid xid, boolean opc) throws XAException {
		TransactionXid branchXid = (TransactionXid) xid;
		TransactionXid globalXid = branchXid.getGlobalXid();
		TransactionConfigurator configurator = TransactionConfigurator.getInstance();
		TransactionRepository repository = configurator.getTransactionRepository();
		CompensableTransaction transaction = repository.getTransaction(globalXid);
		if (transaction == null) {
			throw new XAException(XAException.XAER_NOTA);
		}
		TransactionContext transactionContext = transaction.getTransactionContext();
		if (transactionContext.isCompensable()) {
			this.tccTransactionSkeleton.commit(globalXid, opc);
		} else {
			this.jtaTransactionSkeleton.commit(globalXid, opc);
		}
	}

	public void rollback(Xid xid) throws XAException {
		TransactionXid branchXid = (TransactionXid) xid;
		TransactionXid globalXid = branchXid.getGlobalXid();
		TransactionConfigurator configurator = TransactionConfigurator.getInstance();
		TransactionRepository repository = configurator.getTransactionRepository();
		CompensableTransaction transaction = repository.getTransaction(globalXid);
		if (transaction == null) {
			throw new XAException(XAException.XAER_NOTA);
		}
		TransactionContext transactionContext = transaction.getTransactionContext();
		if (transactionContext.isCompensable()) {
			this.tccTransactionSkeleton.rollback(globalXid);
		} else {
			this.jtaTransactionSkeleton.rollback(globalXid);
		}
	}

	public Xid[] recover(int flags) throws XAException {
		Xid[] jtaXidArray = this.jtaTransactionSkeleton.recover(flags);
		Xid[] tccXidArray = this.tccTransactionSkeleton.recover(flags);
		Xid[] xidArray = new Xid[jtaXidArray.length + tccXidArray.length];
		System.arraycopy(jtaXidArray, 0, xidArray, 0, jtaXidArray.length);
		System.arraycopy(tccXidArray, 0, xidArray, jtaXidArray.length, tccXidArray.length);
		return xidArray;
	}

	public void forget(Xid xid) throws XAException {
		TransactionXid branchXid = (TransactionXid) xid;
		TransactionXid globalXid = branchXid.getGlobalXid();
		TransactionConfigurator configurator = TransactionConfigurator.getInstance();
		TransactionRepository repository = configurator.getTransactionRepository();
		CompensableTransaction transaction = repository.getTransaction(globalXid);
		// if (transaction == null) {
		// throw new XAException(XAException.XAER_NOTA);
		// }
		TransactionContext transactionContext = transaction.getTransactionContext();
		if (transactionContext.isCompensable()) {
			this.tccTransactionSkeleton.forget(globalXid);
		} else {
			this.jtaTransactionSkeleton.forget(globalXid);
		}
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

	public XAResource getJtaTransactionSkeleton() {
		return jtaTransactionSkeleton;
	}

	public void setJtaTransactionSkeleton(XAResource jtaTransactionSkeleton) {
		this.jtaTransactionSkeleton = jtaTransactionSkeleton;
	}

	public XAResource getTccTransactionSkeleton() {
		return tccTransactionSkeleton;
	}

	public void setTccTransactionSkeleton(XAResource tccTransactionSkeleton) {
		this.tccTransactionSkeleton = tccTransactionSkeleton;
	}

}
