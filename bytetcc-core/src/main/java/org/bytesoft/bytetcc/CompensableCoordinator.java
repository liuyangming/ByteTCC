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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import javax.transaction.HeuristicMixedException;
import javax.transaction.HeuristicRollbackException;
import javax.transaction.RollbackException;
import javax.transaction.Status;
import javax.transaction.SystemException;
import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;

import org.bytesoft.bytejta.supports.wire.RemoteCoordinator;
import org.bytesoft.common.utils.ByteUtils;
import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.CompensableManager;
import org.bytesoft.compensable.CompensableTransaction;
import org.bytesoft.compensable.aware.CompensableBeanFactoryAware;
import org.bytesoft.compensable.aware.CompensableEndpointAware;
import org.bytesoft.compensable.logging.CompensableLogger;
import org.bytesoft.transaction.Transaction;
import org.bytesoft.transaction.TransactionContext;
import org.bytesoft.transaction.TransactionRepository;
import org.bytesoft.transaction.xa.TransactionXid;
import org.bytesoft.transaction.xa.XidFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CompensableCoordinator implements RemoteCoordinator, CompensableBeanFactoryAware, CompensableEndpointAware {
	static final Logger logger = LoggerFactory.getLogger(CompensableCoordinator.class);

	private CompensableBeanFactory beanFactory;
	private String endpoint;

	private transient boolean inited = false;
	private final Lock lock = new ReentrantLock();

	public Transaction getTransactionQuietly() {
		CompensableManager transactionManager = this.beanFactory.getCompensableManager();
		return transactionManager.getTransactionQuietly();
	}

	public Transaction start(TransactionContext transactionContext, int flags) throws XAException {
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();
		CompensableLogger compensableLogger = this.beanFactory.getCompensableLogger();
		TransactionRepository compensableRepository = this.beanFactory.getCompensableRepository();

		if (compensableManager.getTransactionQuietly() != null) {
			throw new XAException(XAException.XAER_PROTO);
		}
		TransactionXid globalXid = transactionContext.getXid();
		Transaction transaction = compensableRepository.getTransaction(globalXid);
		if (transaction == null) {
			transaction = new CompensableTransactionImpl((org.bytesoft.compensable.TransactionContext) transactionContext);
			((CompensableTransactionImpl) transaction).setBeanFactory(this.beanFactory);

			compensableRepository.putTransaction(globalXid, transaction);

			compensableLogger.createTransaction(((CompensableTransactionImpl) transaction).getTransactionArchive());
			logger.info("{}| compensable transaction begin!", ByteUtils.byteArrayToString(globalXid.getGlobalTransactionId()));
		}

		compensableManager.associateThread(transaction);

		return transaction;
	}

	public Transaction end(TransactionContext transactionContext, int flags) throws XAException {
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();
		CompensableTransaction transaction = compensableManager.getCompensableTransactionQuietly();
		if (transaction == null) {
			throw new XAException(XAException.XAER_PROTO);
		}

		// clear CompensableTransactionImpl.transientArchiveList in CompensableTransactionImpl.onCommitSuccess().
		// ((CompensableTransactionImpl) transaction).participantComplete();
		compensableManager.desociateThread();

		return transaction;
	}

	public void start(Xid xid, int flags) throws XAException {
		throw new XAException(XAException.XAER_RMERR);
	}

	public void end(Xid xid, int flags) throws XAException {
		throw new XAException(XAException.XAER_RMERR);
	}

	public void commit(Xid xid, boolean onePhase) throws XAException {
		this.checkAvailableIfNecessary();

		if (xid == null) {
			throw new XAException(XAException.XAER_INVAL);
		} else if (onePhase == false) {
			throw new XAException(XAException.XAER_RMERR);
		}
		TransactionRepository compensableRepository = this.beanFactory.getCompensableRepository();
		XidFactory xidFactory = this.beanFactory.getCompensableXidFactory();
		TransactionXid globalXid = xidFactory.createGlobalXid(xid.getGlobalTransactionId());
		CompensableTransaction transaction = (CompensableTransaction) compensableRepository.getTransaction(globalXid);
		if (transaction == null) {
			throw new XAException(XAException.XAER_NOTA);
		}

		CompensableManager compensableManager = this.beanFactory.getCompensableManager();
		try {
			compensableManager.associateThread(transaction);
			transaction.participantCommit(onePhase);
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
		} finally {
			compensableManager.desociateThread();
		}
	}

	public void forget(Xid xid) throws XAException {
		this.checkAvailableIfNecessary();

		if (xid == null) {
			throw new XAException(XAException.XAER_INVAL);
		}
		TransactionRepository compensableRepository = this.beanFactory.getCompensableRepository();
		XidFactory xidFactory = this.beanFactory.getCompensableXidFactory();
		TransactionXid globalXid = xidFactory.createGlobalXid(xid.getGlobalTransactionId());
		CompensableTransaction transaction = (CompensableTransaction) compensableRepository.getTransaction(globalXid);
		if (transaction == null) {
			throw new XAException(XAException.XAER_NOTA);
		}

		try {
			transaction.forget();
		} catch (SystemException ex) {
			throw new XAException(XAException.XAER_RMERR);
		} catch (RuntimeException rex) {
			throw new XAException(XAException.XAER_RMERR);
		}
	}

	public int getTransactionTimeout() throws XAException {
		return 0;
	}

	public boolean isSameRM(XAResource xares) throws XAException {
		throw new XAException(XAException.XAER_RMERR);
	}

	public int prepare(Xid xid) throws XAException {
		throw new XAException(XAException.XAER_RMERR);
	}

	public Xid[] recover(int flag) throws XAException {
		this.checkAvailableIfNecessary();

		TransactionRepository repository = beanFactory.getTransactionRepository();
		List<Transaction> allTransactionList = repository.getActiveTransactionList();

		List<Transaction> transactions = new ArrayList<Transaction>();
		for (int i = 0; i < allTransactionList.size(); i++) {
			Transaction transaction = allTransactionList.get(i);
			int transactionStatus = transaction.getTransactionStatus();
			if (transactionStatus == Status.STATUS_PREPARED || transactionStatus == Status.STATUS_COMMITTING
					|| transactionStatus == Status.STATUS_ROLLING_BACK || transactionStatus == Status.STATUS_COMMITTED
					|| transactionStatus == Status.STATUS_ROLLEDBACK) {
				transactions.add(transaction);
			} else if (transaction.getTransactionContext().isRecoveried()) {
				transactions.add(transaction);
			}
		}

		TransactionXid[] xidArray = new TransactionXid[transactions.size()];
		for (int i = 0; i < transactions.size(); i++) {
			Transaction transaction = transactions.get(i);
			xidArray[i] = transaction.getTransactionContext().getXid();
		}

		return xidArray;
	}

	public void rollback(Xid xid) throws XAException {
		this.checkAvailableIfNecessary();

		if (xid == null) {
			throw new XAException(XAException.XAER_INVAL);
		}
		TransactionRepository compensableRepository = this.beanFactory.getCompensableRepository();
		XidFactory xidFactory = this.beanFactory.getCompensableXidFactory();
		TransactionXid globalXid = xidFactory.createGlobalXid(xid.getGlobalTransactionId());
		CompensableTransaction transaction = (CompensableTransaction) compensableRepository.getTransaction(globalXid);
		if (transaction == null) {
			throw new XAException(XAException.XAER_NOTA);
		}

		CompensableManager compensableManager = this.beanFactory.getCompensableManager();
		try {
			compensableManager.associateThread(transaction);
			transaction.participantRollback();
		} catch (IllegalStateException ex) {
			throw new XAException(XAException.XAER_RMERR);
		} catch (SystemException ex) {
			throw new XAException(XAException.XAER_RMERR);
		} catch (RuntimeException ex) {
			throw new XAException(XAException.XAER_RMERR);
		} finally {
			compensableManager.desociateThread();
		}
	}

	public void markAvailable() {
		try {
			this.lock.lock();
			this.inited = true;
		} finally {
			this.lock.unlock();
		}
	}

	private void checkAvailableIfNecessary() throws XAException {
		if (this.inited == false) {
			this.checkAvailable();
		}
	}

	private void checkAvailable() throws XAException {
		try {
			this.lock.lock();
			if (this.inited == false) {
				throw new XAException(XAException.XAER_RMFAIL);
			}
		} finally {
			this.lock.unlock();
		}
	}

	public boolean setTransactionTimeout(int seconds) throws XAException {
		return false;
	}

	public void setEndpoint(String identifier) {
		this.endpoint = identifier;
	}

	public String getIdentifier() {
		return this.endpoint;
	}

	public void setBeanFactory(CompensableBeanFactory tbf) {
		this.beanFactory = tbf;
	}

}
