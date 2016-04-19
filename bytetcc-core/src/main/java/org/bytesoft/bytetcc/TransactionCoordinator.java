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

import javax.transaction.HeuristicMixedException;
import javax.transaction.HeuristicRollbackException;
import javax.transaction.RollbackException;
import javax.transaction.SystemException;
import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;

import org.apache.log4j.Logger;
import org.bytesoft.bytejta.supports.wire.RemoteCoordinator;
import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.CompensableTransaction;
import org.bytesoft.compensable.aware.CompensableBeanFactoryAware;
import org.bytesoft.transaction.Transaction;
import org.bytesoft.transaction.TransactionContext;
import org.bytesoft.transaction.TransactionManager;
import org.bytesoft.transaction.TransactionRepository;
import org.bytesoft.transaction.internal.TransactionException;
import org.bytesoft.transaction.xa.TransactionXid;
import org.bytesoft.transaction.xa.XidFactory;

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

	public Transaction start(TransactionContext transactionContext, int flags) throws TransactionException {
		TransactionRepository transactionRepository = this.beanFactory.getTransactionRepository();
		TransactionManager transactionManager = this.beanFactory.getTransactionManager();
		if (transactionManager.getTransactionQuietly() != null) {
			throw new TransactionException(XAException.XAER_PROTO);
		}

		TransactionXid globalXid = transactionContext.getXid();
		Transaction transaction = transactionRepository.getTransaction(globalXid);
		if (transaction == null) {
			transaction = new CompensableTransactionImpl(transactionContext);
			((CompensableTransactionImpl) transaction).setBeanFactory(this.beanFactory);
			transactionRepository.putTransaction(globalXid, transaction);
		}
		transactionManager.associateThread(transaction);
		return transaction;
	}

	public Transaction end(TransactionContext transactionContext, int flags) throws TransactionException {
		TransactionManager transactionManager = this.beanFactory.getTransactionManager();
		return transactionManager.getTransactionQuietly();
	}

	public void start(Xid xid, int flags) throws XAException {
	}

	public void end(Xid xid, int flags) throws XAException {
	}

	public void commit(Xid xid, boolean onePhase) throws XAException {
		TransactionRepository transactionRepository = this.beanFactory.getTransactionRepository();
		XidFactory xidFactory = this.beanFactory.getCompensableXidFactory();
		TransactionXid globalXid = xidFactory.createGlobalXid(xid.getGlobalTransactionId());
		CompensableTransaction transaction = (CompensableTransaction) transactionRepository.getTransaction(globalXid);
		if (transaction == null) {
			throw new XAException(XAException.XAER_NOTA);
		}
		try {
			transaction.commit();
			transactionRepository.removeErrorTransaction(globalXid);
			transactionRepository.removeTransaction(globalXid);
		} catch (SecurityException ex) {
			throw new XAException(XAException.XAER_RMERR);
		} catch (IllegalStateException ex) {
			throw new XAException(XAException.XAER_RMERR);
		} catch (RollbackException ex) {
			throw new XAException(XAException.XA_HEURRB);
		} catch (HeuristicMixedException ex) {
			throw new XAException(XAException.XA_HEURMIX);
		} catch (HeuristicRollbackException ex) {
			throw new XAException(XAException.XA_HEURRB);
		} catch (SystemException ex) {
			throw new XAException(XAException.XAER_RMERR);
		} catch (RuntimeException ex) {
			throw new XAException(XAException.XAER_RMERR);
		}
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
		TransactionRepository transactionRepository = this.beanFactory.getTransactionRepository();
		XidFactory xidFactory = this.beanFactory.getCompensableXidFactory();
		TransactionXid globalXid = xidFactory.createGlobalXid(xid.getGlobalTransactionId());
		CompensableTransaction transaction = (CompensableTransaction) transactionRepository.getTransaction(globalXid);
		if (transaction == null) {
			throw new XAException(XAException.XAER_NOTA);
		}
		try {
			transaction.rollback();
			transactionRepository.removeErrorTransaction(globalXid);
			transactionRepository.removeTransaction(globalXid);
		} catch (IllegalStateException ex) {
			throw new XAException(XAException.XAER_RMERR);
		} catch (SystemException ex) {
			throw new XAException(XAException.XAER_RMERR);
		} catch (RuntimeException ex) {
			throw new XAException(XAException.XAER_RMERR);
		}
	}

	public boolean setTransactionTimeout(int seconds) throws XAException {
		return false;
	}

	public void setBeanFactory(CompensableBeanFactory tbf) {
		this.beanFactory = tbf;
	}

}
