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
import org.bytesoft.compensable.archive.TransactionArchive;
import org.bytesoft.compensable.aware.CompensableBeanFactoryAware;
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

public class CompensableCoordinator implements RemoteCoordinator, CompensableBeanFactoryAware {
	static final Logger logger = LoggerFactory.getLogger(CompensableCoordinator.class);

	private CompensableBeanFactory beanFactory;

	public String getIdentifier() {
		throw new IllegalStateException();
	}

	public Transaction getTransactionQuietly() {
		CompensableManager transactionManager = this.beanFactory.getCompensableManager();
		return transactionManager.getTransactionQuietly();
	}

	public Transaction start(TransactionContext transactionContext, int flags) throws TransactionException {
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();
		CompensableLogger compensableLogger = this.beanFactory.getCompensableLogger();

		if (compensableManager.getTransactionQuietly() != null) {
			throw new TransactionException(XAException.XAER_PROTO);
		}
		TransactionRepository compensableRepository = this.beanFactory.getCompensableRepository();
		TransactionXid globalXid = transactionContext.getXid();
		CompensableTransactionImpl transaction = new CompensableTransactionImpl(transactionContext);
		transaction = new CompensableTransactionImpl(transactionContext);
		transaction.setBeanFactory(this.beanFactory);

		compensableLogger.createTransaction(transaction.getTransactionArchive());
		logger.info("{}| compensable transaction begin!", ByteUtils.byteArrayToString(globalXid.getGlobalTransactionId()));

		compensableManager.associateThread(transaction);
		compensableRepository.putTransaction(globalXid, transaction);

		return transaction;
	}

	public Transaction end(TransactionContext transactionContext, int flags) throws TransactionException {
		CompensableManager transactionManager = this.beanFactory.getCompensableManager();
		CompensableTransaction transaction = transactionManager.getCompensableTransactionQuietly();
		((CompensableTransactionImpl) transaction).participantComplete();
		return transaction == null ? null : transaction.getTransaction();
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

			compensableRepository.removeErrorTransaction(globalXid);
			compensableRepository.removeTransaction(globalXid);
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
		CompensableLogger transactionLogger = this.beanFactory.getCompensableLogger();
		XidFactory xidFactory = this.beanFactory.getCompensableXidFactory();
		TransactionXid globalXid = xidFactory.createGlobalXid(xid.getGlobalTransactionId());
		CompensableTransaction transaction = (CompensableTransaction) compensableRepository.getTransaction(globalXid);
		if (transaction == null) {
			throw new XAException(XAException.XAER_NOTA);
		}

		boolean success = true;
		try {
			transaction.forget();
			TransactionArchive archive = transaction.getTransactionArchive();
			transactionLogger.deleteTransaction(archive);

			logger.info("{}| compensable transaction forgot!", ByteUtils.byteArrayToString(xid.getGlobalTransactionId()));
		} catch (SystemException ex) {
			success = false;
			throw new XAException(XAException.XAER_RMERR);
		} catch (RuntimeException rex) {
			success = false;
			throw new XAException(XAException.XAER_RMERR);
		} finally {
			if (success) {
				compensableRepository.removeErrorTransaction(globalXid);
			}
		}
	}

	public void recoveryForget(Xid xid) throws XAException {
		if (xid == null) {
			throw new XAException(XAException.XAER_INVAL);
		}
		TransactionRepository compensableRepository = this.beanFactory.getCompensableRepository();
		CompensableLogger transactionLogger = this.beanFactory.getCompensableLogger();
		XidFactory xidFactory = this.beanFactory.getCompensableXidFactory();
		TransactionXid globalXid = xidFactory.createGlobalXid(xid.getGlobalTransactionId());
		CompensableTransaction transaction = (CompensableTransaction) compensableRepository.getTransaction(globalXid);
		if (transaction == null) {
			throw new XAException(XAException.XAER_NOTA);
		}

		boolean success = true;
		try {
			transaction.recoveryForget();
			TransactionArchive archive = transaction.getTransactionArchive();
			transactionLogger.deleteTransaction(archive);

			logger.info("{}| compensable transaction forgot!", ByteUtils.byteArrayToString(xid.getGlobalTransactionId()));
		} catch (SystemException ex) {
			success = false;
			throw new XAException(XAException.XAER_RMERR);
		} catch (RuntimeException rex) {
			success = false;
			throw new XAException(XAException.XAER_RMERR);
		} finally {
			if (success) {
				compensableRepository.removeErrorTransaction(globalXid);
			}
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

			compensableRepository.removeErrorTransaction(globalXid);
			compensableRepository.removeTransaction(globalXid);
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
		TransactionContext transactionContext = transaction.getTransactionContext();
		if (transactionContext.isRecoveried()) {
			this.recoveryCommitRecoveredTransaction(globalXid, onePhase);
		} else {
			this.recoveryCommitActiveTransaction(globalXid, onePhase);
		}
	}

	public void recoveryCommitRecoveredTransaction(TransactionXid xid, boolean onePhase) throws XAException {
		TransactionRepository transactionRepository = beanFactory.getCompensableRepository();
		Transaction transaction = transactionRepository.getTransaction(xid);
		try {
			transaction.recoveryRollback();
		} catch (RollbackRequiredException rrex) {
			logger.error("Error occurred while rolling back recovered transaction.", rrex);

			XAException xaex = new XAException(XAException.XAER_RMERR);
			xaex.initCause(rrex);
			throw xaex;
		} catch (SystemException ex) {
			logger.error("Error occurred while rolling back recovered transaction.", ex);

			XAException xaex = new XAException(XAException.XAER_RMERR);
			xaex.initCause(ex);
			throw xaex;
		} catch (RuntimeException rrex) {
			logger.error("Error occurred while rolling back recovered transaction.", rrex);

			XAException xaex = new XAException(XAException.XAER_RMERR);
			xaex.initCause(rrex);
			throw xaex;
		}
	}

	public void recoveryCommitActiveTransaction(TransactionXid xid, boolean onePhase) throws XAException {
		TransactionRepository transactionRepository = beanFactory.getCompensableRepository();
		Transaction transaction = transactionRepository.getTransaction(xid);
		try {
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
		}
	}

	public void recoveryRollback(Xid xid) throws XAException {
		TransactionRepository transactionRepository = beanFactory.getCompensableRepository();
		XidFactory xidFactory = this.beanFactory.getCompensableXidFactory();
		TransactionXid branchXid = (TransactionXid) xid;
		TransactionXid globalXid = xidFactory.createGlobalXid(branchXid.getGlobalTransactionId());

		Transaction transaction = transactionRepository.getTransaction(globalXid);
		TransactionContext transactionContext = transaction.getTransactionContext();
		if (transactionContext.isRecoveried()) {
			this.recoveryRollbackRecoveredTransaction(globalXid);
		} else {
			this.recoveryRollbackActiveTransaction(globalXid);
		}
	}

	public void recoveryRollbackRecoveredTransaction(TransactionXid xid) throws XAException {
		TransactionRepository transactionRepository = beanFactory.getCompensableRepository();
		Transaction transaction = transactionRepository.getTransaction(xid);
		try {
			transaction.recoveryRollback();
		} catch (RollbackRequiredException rrex) {
			logger.error("Error occurred while rolling back recovered transaction.", rrex);

			XAException xaex = new XAException(XAException.XAER_RMERR);
			xaex.initCause(rrex);
			throw xaex;
		} catch (SystemException ex) {
			logger.error("Error occurred while rolling back recovered transaction.", ex);

			XAException xaex = new XAException(XAException.XAER_RMERR);
			xaex.initCause(ex);
			throw xaex;
		} catch (RuntimeException rrex) {
			logger.error("Error occurred while rolling back recovered transaction.", rrex);

			XAException xaex = new XAException(XAException.XAER_RMERR);
			xaex.initCause(rrex);
			throw xaex;
		}
	}

	public void recoveryRollbackActiveTransaction(TransactionXid xid) throws XAException {
		TransactionRepository transactionRepository = beanFactory.getCompensableRepository();
		Transaction transaction = transactionRepository.getTransaction(xid);
		try {
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
		}
	}

	public boolean setTransactionTimeout(int seconds) throws XAException {
		return false;
	}

	public void setBeanFactory(CompensableBeanFactory tbf) {
		this.beanFactory = tbf;
	}

}
