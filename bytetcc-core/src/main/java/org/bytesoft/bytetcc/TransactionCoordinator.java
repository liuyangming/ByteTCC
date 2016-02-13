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

import org.apache.log4j.Logger;
import org.bytesoft.bytejta.supports.wire.RemoteCoordinator;
import org.bytesoft.bytetcc.aware.CompensableBeanFactoryAware;
import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.transaction.Transaction;
import org.bytesoft.transaction.TransactionManager;
import org.bytesoft.transaction.internal.TransactionException;

public class TransactionCoordinator implements RemoteCoordinator, CompensableBeanFactoryAware {
	static final Logger logger = Logger.getLogger(TransactionCoordinator.class.getSimpleName());

	private CompensableBeanFactory beanFactory;

	public String getIdentifier() {
		throw new IllegalStateException();
	}

	public Transaction getTransactionQuietly() {
		TransactionManager transactionManager = this.beanFactory.getTransactionManager();
		return transactionManager.getTransactionQuietly();
	}

	public void start(org.bytesoft.transaction.TransactionContext transactionContext, int flags)
			throws TransactionException {
	}

	public void end(org.bytesoft.transaction.TransactionContext transactionContext, int flags)
			throws TransactionException {
	}

	public void start(Xid xid, int flags) throws XAException {
	}

	public void end(Xid xid, int flags) throws XAException {
	}

	public void commit(Xid xid, boolean onePhase) throws XAException {
	}

	public void forget(Xid xid) throws XAException {
	}

	public int getTransactionTimeout() throws XAException {
		return 0;
	}

	public boolean isSameRM(XAResource xares) throws XAException {
		throw new XAException(XAException.XAER_RMERR);
	}

	public int prepare(Xid xid) throws XAException {
		return XAResource.XA_RDONLY;
	}

	public Xid[] recover(int flag) throws XAException {
		return new Xid[0];
	}

	public void rollback(Xid xid) throws XAException {
	}

	public boolean setTransactionTimeout(int seconds) throws XAException {
		return false;
	}

	public void setBeanFactory(CompensableBeanFactory tbf) {
		this.beanFactory = tbf;
	}

}
