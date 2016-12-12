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

import java.util.List;

import javax.transaction.HeuristicMixedException;
import javax.transaction.HeuristicRollbackException;
import javax.transaction.RollbackException;
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
import org.bytesoft.transaction.CommitRequiredException;
import org.bytesoft.transaction.RollbackRequiredException;
import org.bytesoft.transaction.Transaction;
import org.bytesoft.transaction.TransactionContext;
import org.bytesoft.transaction.TransactionRepository;
import org.bytesoft.transaction.internal.TransactionException;
import org.bytesoft.transaction.xa.TransactionXid;
import org.bytesoft.transaction.xa.XidFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CompensableCoordinator implements RemoteCoordinator, CompensableBeanFactoryAware, CompensableEndpointAware {
	static final Logger logger = LoggerFactory.getLogger(CompensableCoordinator.class);

	private String endpoint;
	private CompensableBeanFactory beanFactory;

	public Transaction getTransactionQuietly() {
		CompensableManager transactionManager = this.beanFactory.getCompensableManager();
		return transactionManager.getTransactionQuietly();
	}

	public Transaction start(TransactionContext transactionContext, int flags) throws TransactionException {
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();
		CompensableLogger compensableLogger = this.beanFactory.getCompensableLogger();
		TransactionRepository compensableRepository = this.beanFactory.getCompensableRepository();

		if (compensableManager.getTransactionQuietly() != null) {
			throw new TransactionException(XAException.XAER_PROTO);
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

	public Transaction end(TransactionContext transactionContext, int flags) throws TransactionException {
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();
		CompensableTransaction transaction = compensableManager.getCompensableTransactionQuietly();
		if (transaction == null) {
			throw new TransactionException(XAException.XAER_PROTO);
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
			transaction.commit();
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

	public void recoveryForget(Xid xid) throws XAException {
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
			transaction.recoveryForget();
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
		TransactionRepository compensableRepository = this.beanFactory.getCompensableRepository();
		List<Transaction> transactionList = compensableRepository.getErrorTransactionList();
		Xid[] xidArray = new Xid[transactionList == null ? 0 : transactionList.size()];
		for (int i = 0; i < xidArray.length; i++) {
			Transaction transaction = transactionList.get(i);
			TransactionContext transactionContext = transaction.getTransactionContext();
			xidArray[i] = transactionContext.getXid();
		}
		return xidArray;
	}

	public void rollback(Xid xid) throws XAException {
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
			transaction.rollback();
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

	public void recoveryCommit(Xid xid, boolean onePhase) throws XAException {
		TransactionRepository transactionRepository = beanFactory.getCompensableRepository();
		XidFactory xidFactory = this.beanFactory.getCompensableXidFactory();
		TransactionXid branchXid = (TransactionXid) xid;
		TransactionXid globalXid = xidFactory.createGlobalXid(branchXid.getGlobalTransactionId());
		Transaction transaction = transactionRepository.getTransaction(globalXid);
		if (transaction == null) {
			throw new XAException(XAException.XAER_NOTA);
		}

		TransactionContext transactionContext = transaction.getTransactionContext();
		if (transactionContext.isRecoveried()) {
			this.recoveryCommitRecoveredTransaction(globalXid, onePhase);
		} else {
			this.recoveryCommitActiveTransaction(globalXid, onePhase);
		}
	}

	private void recoveryCommitRecoveredTransaction(TransactionXid xid, boolean onePhase) throws XAException {
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();
		TransactionRepository transactionRepository = beanFactory.getCompensableRepository();
		Transaction transaction = transactionRepository.getTransaction(xid);

		try {
			compensableManager.associateThread(transaction);
			transaction.recoveryCommit();
		} catch (CommitRequiredException ex) {
			logger.error("Error occurred while committing(recovery) recovered transaction.", ex);
			transactionRepository.putErrorTransaction(xid, transaction);

			XAException xaex = new XAException(XAException.XAER_RMERR);
			xaex.initCause(ex);
			throw xaex;
		} catch (SystemException ex) {
			logger.error("Error occurred while committing(recovery) recovered transaction.", ex);
			transactionRepository.putErrorTransaction(xid, transaction);

			XAException xaex = new XAException(XAException.XAER_RMERR);
			xaex.initCause(ex);
			throw xaex;
		} catch (RuntimeException ex) {
			logger.error("Error occurred while committing(recovery) recovered transaction.", ex);
			transactionRepository.putErrorTransaction(xid, transaction);

			XAException xaex = new XAException(XAException.XAER_RMERR);
			xaex.initCause(ex);
			throw xaex;
		} finally {
			compensableManager.desociateThread();
		}
	}

	private void recoveryCommitActiveTransaction(TransactionXid xid, boolean onePhase) throws XAException {
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();
		TransactionRepository transactionRepository = beanFactory.getCompensableRepository();
		Transaction transaction = transactionRepository.getTransaction(xid);

		try {
			compensableManager.associateThread(transaction);
			transaction.recoveryCommit();
		} catch (CommitRequiredException ex) {
			logger.error("Error occurred while committing(recovery) active transaction.", ex);
			transactionRepository.putErrorTransaction(xid, transaction);

			XAException xaex = new XAException(XAException.XAER_RMERR);
			xaex.initCause(ex);
			throw xaex;
		} catch (SystemException ex) {
			logger.error("Error occurred while committing(recovery) active transaction.", ex);
			transactionRepository.putErrorTransaction(xid, transaction);

			XAException xaex = new XAException(XAException.XAER_RMERR);
			xaex.initCause(ex);
			throw xaex;
		} catch (RuntimeException ex) {
			logger.error("Error occurred while committing(recovery) active transaction.", ex);
			transactionRepository.putErrorTransaction(xid, transaction);

			XAException xaex = new XAException(XAException.XAER_RMERR);
			xaex.initCause(ex);
			throw xaex;
		} finally {
			compensableManager.desociateThread();
		}
	}

	public void recoveryRollback(Xid xid) throws XAException {
		TransactionRepository transactionRepository = beanFactory.getCompensableRepository();
		XidFactory xidFactory = this.beanFactory.getCompensableXidFactory();
		TransactionXid branchXid = (TransactionXid) xid;
		TransactionXid globalXid = xidFactory.createGlobalXid(branchXid.getGlobalTransactionId());

		Transaction transaction = transactionRepository.getTransaction(globalXid);
		if (transaction == null) {
			throw new XAException(XAException.XAER_NOTA);
		}

		TransactionContext transactionContext = transaction.getTransactionContext();
		if (transactionContext.isRecoveried()) {
			this.recoveryRollbackRecoveredTransaction(globalXid);
		} else {
			this.recoveryRollbackActiveTransaction(globalXid);
		}
	}

	private void recoveryRollbackRecoveredTransaction(TransactionXid xid) throws XAException {
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();
		TransactionRepository transactionRepository = this.beanFactory.getCompensableRepository();
		Transaction transaction = transactionRepository.getTransaction(xid);

		try {
			compensableManager.associateThread(transaction);
			transaction.recoveryRollback();
		} catch (RollbackRequiredException rrex) {
			logger.error("Error occurred while rolling back(recovery) recovered transaction.", rrex);

			XAException xaex = new XAException(XAException.XAER_RMERR);
			xaex.initCause(rrex);
			throw xaex;
		} catch (SystemException ex) {
			logger.error("Error occurred while rolling back(recovery) recovered transaction.", ex);

			XAException xaex = new XAException(XAException.XAER_RMERR);
			xaex.initCause(ex);
			throw xaex;
		} catch (RuntimeException rrex) {
			logger.error("Error occurred while rolling back(recovery) recovered transaction.", rrex);

			XAException xaex = new XAException(XAException.XAER_RMERR);
			xaex.initCause(rrex);
			throw xaex;
		} finally {
			compensableManager.desociateThread();
		}
	}

	private void recoveryRollbackActiveTransaction(TransactionXid xid) throws XAException {
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();
		TransactionRepository transactionRepository = beanFactory.getCompensableRepository();
		Transaction transaction = transactionRepository.getTransaction(xid);

		try {
			compensableManager.associateThread(transaction);
			transaction.recoveryRollback();
		} catch (RollbackRequiredException rrex) {
			logger.error("Error occurred while rolling back(recovery) active transaction.", rrex);
			transactionRepository.putErrorTransaction(xid, transaction);

			XAException xaex = new XAException(XAException.XAER_RMERR);
			xaex.initCause(rrex);
			throw xaex;
		} catch (SystemException ex) {
			logger.error("Error occurred while rolling back(recovery) active transaction.", ex);
			transactionRepository.putErrorTransaction(xid, transaction);

			XAException xaex = new XAException(XAException.XAER_RMERR);
			xaex.initCause(ex);
			throw xaex;
		} catch (RuntimeException rrex) {
			logger.error("Error occurred while rolling back(recovery) active transaction.", rrex);
			transactionRepository.putErrorTransaction(xid, transaction);

			XAException xaex = new XAException(XAException.XAER_RMERR);
			xaex.initCause(rrex);
			throw xaex;
		} finally {
			compensableManager.desociateThread();
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
