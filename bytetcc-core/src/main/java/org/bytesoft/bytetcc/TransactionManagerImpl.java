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
import javax.transaction.InvalidTransactionException;
import javax.transaction.NotSupportedException;
import javax.transaction.RollbackException;
import javax.transaction.Status;
import javax.transaction.SystemException;
import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;

import org.bytesoft.bytejta.supports.wire.RemoteCoordinator;
import org.bytesoft.common.utils.ByteUtils;
import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.CompensableInvocation;
import org.bytesoft.compensable.CompensableInvocationRegistry;
import org.bytesoft.compensable.CompensableManager;
import org.bytesoft.compensable.CompensableTransaction;
import org.bytesoft.compensable.aware.CompensableBeanFactoryAware;
import org.bytesoft.compensable.logging.CompensableLogger;
import org.bytesoft.transaction.Transaction;
import org.bytesoft.transaction.TransactionContext;
import org.bytesoft.transaction.TransactionManager;
import org.bytesoft.transaction.internal.TransactionException;
import org.bytesoft.transaction.xa.TransactionXid;
import org.bytesoft.transaction.xa.XidFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TransactionManagerImpl implements TransactionManager, CompensableBeanFactoryAware {
	static final Logger logger = LoggerFactory.getLogger(TransactionManagerImpl.class);

	private CompensableBeanFactory beanFactory;

	public void begin() throws NotSupportedException, SystemException {
		TransactionManager transactionManager = this.beanFactory.getTransactionManager();
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();

		CompensableTransaction transaction = compensableManager.getCompensableTransactionQuietly();

		CompensableInvocationRegistry registry = CompensableInvocationRegistry.getInstance();
		CompensableInvocation invocation = registry.getCurrent();

		if (invocation != null) {
			if (transaction == null) {
				this.beginInTryingPhaseForCoordinator(invocation);
			} else {
				TransactionContext transactionContext = transaction.getTransactionContext();
				if (transactionContext.isCompensating()) {
					this.beginInCompensatingPhaseForCoordinator(transaction);
				} else {
					this.beginInTryingPhaseForParticipant(transaction);
				}
			}
		} else if (transaction == null) {
			transactionManager.begin();
		} else if (transaction.getTransactionContext().isRecoveried()) {
			this.beginInCompensatingPhaseForRecovery(transaction); // recovery
		} else {
			this.beginInCompensatingPhaseForParticipant(transaction);
		}

	}

	protected void beginInTryingPhaseForCoordinator(CompensableInvocation invocation) throws NotSupportedException,
			SystemException {
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();
		CompensableLogger compensableLogger = this.beanFactory.getCompensableLogger();

		compensableManager.begin();
		CompensableTransaction transaction = compensableManager.getCompensableTransactionQuietly();
		TransactionContext transactionContext = transaction.getTransactionContext();
		transactionContext.setCompensable(true);

		compensableLogger.createTransaction(transaction.getTransactionArchive());
		logger.info("{}| compensable transaction begin!",
				ByteUtils.byteArrayToString(transactionContext.getXid().getGlobalTransactionId()));

		transaction.registerCompensable(invocation);
	}

	protected void beginInTryingPhaseForParticipant(CompensableTransaction compensable) throws NotSupportedException,
			SystemException {
		RemoteCoordinator transactionCoordinator = this.beanFactory.getTransactionCoordinator();

		XidFactory transactionXidFactory = this.beanFactory.getTransactionXidFactory();
		TransactionContext compensableContext = compensable.getTransactionContext();
		TransactionXid compensableXid = compensableContext.getXid();
		TransactionXid transactionXid = transactionXidFactory.createGlobalXid(compensableXid.getGlobalTransactionId());
		TransactionContext jtaTransactionContext = compensableContext.clone();
		jtaTransactionContext.setXid(transactionXid);
		try {
			Transaction transaction = transactionCoordinator.start(jtaTransactionContext, XAResource.TMNOFLAGS);
			transaction.setTransactionalExtra(compensable);
			compensable.setTransactionalExtra(transaction);

			transaction.registerTransactionResourceListener(compensable);
			transaction.registerTransactionListener(compensable);
		} catch (TransactionException ex) {
			logger.info("[{}] begin-transaction: error occurred while starting jta-transaction: {}",
					ByteUtils.byteArrayToString(compensableXid.getGlobalTransactionId()),
					ByteUtils.byteArrayToString(transactionXid.getGlobalTransactionId()));
			throw new SystemException("Error occurred while beginning a jta-transaction!");
		}

		// CompensableLogger compensableLogger = this.beanFactory.getCompensableLogger();
		// compensableLogger.createCompensable(compensable.getCompensableArchive()); // lazy
	}

	private void beginInCompensatingPhase(CompensableTransaction compensable) throws NotSupportedException,
			SystemException {
		XidFactory transactionXidFactory = this.beanFactory.getTransactionXidFactory();
		RemoteCoordinator transactionCoordinator = this.beanFactory.getTransactionCoordinator();

		TransactionContext compensableContext = compensable.getTransactionContext();
		TransactionXid transactionXid = transactionXidFactory.createGlobalXid();
		TransactionContext transactionContext = compensableContext.clone();
		transactionContext.setXid(transactionXid);
		try {
			Transaction transaction = transactionCoordinator.start(transactionContext, XAResource.TMNOFLAGS);
			transaction.setTransactionalExtra(compensable);
			compensable.setTransactionalExtra(transaction);

			transaction.registerTransactionResourceListener(compensable);
			transaction.registerTransactionListener(compensable);
		} catch (TransactionException ex) {
			TransactionXid compensableXid = compensableContext.getXid();
			logger.info("[{}] begin-transaction: error occurred while starting jta-transaction: {}",
					ByteUtils.byteArrayToString(compensableXid.getGlobalTransactionId()),
					ByteUtils.byteArrayToString(transactionXid.getGlobalTransactionId()));
			throw new SystemException("Error occurred while beginning a jta-transaction!");
		}
	}

	protected void beginInCompensatingPhaseForCoordinator(CompensableTransaction compensable)
			throws NotSupportedException, SystemException {
		this.beginInCompensatingPhase(compensable);
	}

	protected void beginInCompensatingPhaseForParticipant(CompensableTransaction compensable)
			throws NotSupportedException, SystemException {
		this.beginInCompensatingPhase(compensable);
	}

	protected void beginInCompensatingPhaseForRecovery(CompensableTransaction compensable)
			throws NotSupportedException, SystemException {
		this.beginInCompensatingPhase(compensable);
	}

	public void commit() throws RollbackException, HeuristicMixedException, HeuristicRollbackException,
			SecurityException, IllegalStateException, SystemException {
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();
		Transaction compensable = compensableManager.getCompensableTransactionQuietly();

		if (compensable != null && compensable.getTransactionContext().isRecoveried()) {
			this.invokeCommitForRecovery();
		} else {
			this.invokeCommit();
		}
	}

	public void invokeCommitForRecovery() throws RollbackException, HeuristicMixedException,
			HeuristicRollbackException, SecurityException, IllegalStateException, SystemException {
		TransactionManager transactionManager = this.beanFactory.getTransactionManager();
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();
		Transaction transaction = transactionManager.getTransactionQuietly();
		CompensableTransaction compensable = compensableManager.getCompensableTransactionQuietly();

		RemoteCoordinator transactionCoordinator = this.beanFactory.getTransactionCoordinator();
		TransactionContext transactionContext = transaction.getTransactionContext();
		TransactionXid transactionXid = transactionContext.getXid();
		try {
			transactionCoordinator.end(transactionContext, XAResource.TMSUCCESS);
			transactionCoordinator.recoveryCommit(transactionXid, true);
		} catch (XAException xaex) {
			switch (xaex.errorCode) {
			case XAException.XA_HEURCOM:
				break;
			case XAException.XA_HEURRB:
				throw new HeuristicRollbackException();
			case XAException.XA_HEURMIX:
				throw new HeuristicMixedException();
			case XAException.XAER_RMERR:
			default:
				throw new SystemException();
			}
		} finally {
			compensable.setTransactionalExtra(null);
		}
	}

	public void invokeCommit() throws RollbackException, HeuristicMixedException, HeuristicRollbackException,
			SecurityException, IllegalStateException, SystemException {
		TransactionManager transactionManager = this.beanFactory.getTransactionManager();
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();

		TransactionContext compensableContext = null;
		Transaction transaction = transactionManager.getTransactionQuietly();
		Transaction compensable = compensableManager.getCompensableTransactionQuietly();

		if (transaction == null && compensable == null) {
			throw new IllegalStateException();
		} else if (compensable == null) {
			compensableContext = transaction.getTransactionContext();
		} else {
			compensableContext = compensable.getTransactionContext();
		}
		if (compensableContext.isCompensable() == false) {
			transactionManager.commit();
		} else if (compensableContext.isCompensating()) {
			compensableManager.commit();
		} else if (compensableContext.isCoordinator()) {
			compensableManager.compensableCommit();
		} else {
			compensableManager.commit();
		}
	}

	public void rollback() throws IllegalStateException, SecurityException, SystemException {
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();
		Transaction compensable = compensableManager.getCompensableTransactionQuietly();

		if (compensable != null && compensable.getTransactionContext().isRecoveried()) {
			this.invokeRollbackForRecovery();
		} else {
			this.invokeRollback();
		}
	}

	public void invokeRollbackForRecovery() throws IllegalStateException, SecurityException, SystemException {
		TransactionManager transactionManager = this.beanFactory.getTransactionManager();
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();
		Transaction transaction = transactionManager.getTransactionQuietly();
		CompensableTransaction compensable = compensableManager.getCompensableTransactionQuietly();

		RemoteCoordinator transactionCoordinator = this.beanFactory.getTransactionCoordinator();
		TransactionContext transactionContext = transaction.getTransactionContext();
		TransactionXid transactionXid = transactionContext.getXid();
		try {
			transactionCoordinator.end(transactionContext, XAResource.TMSUCCESS);
			transactionCoordinator.recoveryRollback(transactionXid);
		} catch (XAException xaex) {
			throw new SystemException();
		} finally {
			compensable.setTransactionalExtra(null);
		}
	}

	public void invokeRollback() throws IllegalStateException, SecurityException, SystemException {
		TransactionManager transactionManager = this.beanFactory.getTransactionManager();
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();

		TransactionContext transactionContext = null;
		Transaction jtaTransaction = transactionManager.getTransactionQuietly();
		Transaction tccTransaction = compensableManager.getCompensableTransactionQuietly();
		if (jtaTransaction == null && tccTransaction == null) {
			throw new IllegalStateException();
		} else if (tccTransaction == null) {
			transactionContext = jtaTransaction.getTransactionContext();
		} else {
			transactionContext = tccTransaction.getTransactionContext();
		}

		if (transactionContext.isCompensable() == false) {
			transactionManager.rollback();
		} else if (transactionContext.isCoordinator()) {
			compensableManager.compensableRollback();
		} else {
			compensableManager.rollback();
		}

	}

	public Transaction suspend() throws SystemException {
		TransactionManager transactionManager = this.beanFactory.getTransactionManager();
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();

		TransactionContext transactionContext = null;
		Transaction jtaTransaction = transactionManager.getTransactionQuietly();
		Transaction tccTransaction = compensableManager.getCompensableTransactionQuietly();
		if (jtaTransaction == null && tccTransaction == null) {
			throw new SystemException();
		} else if (tccTransaction == null) {
			transactionContext = jtaTransaction.getTransactionContext();
		} else {
			transactionContext = tccTransaction.getTransactionContext();
		}
		boolean isCompensableTransaction = transactionContext.isCompensable();
		return (isCompensableTransaction ? compensableManager : transactionManager).suspend();
	}

	public void resume(javax.transaction.Transaction tobj) throws InvalidTransactionException, IllegalStateException,
			SystemException {
		TransactionManager transactionManager = this.beanFactory.getTransactionManager();
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();

		TransactionContext transactionContext = ((Transaction) tobj).getTransactionContext();
		boolean isCompensableTransaction = transactionContext.isCompensable();
		(isCompensableTransaction ? compensableManager : transactionManager).resume(tobj);
	}

	public void setRollbackOnly() throws IllegalStateException, SystemException {
		TransactionManager transactionManager = this.beanFactory.getTransactionManager();
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();

		TransactionContext transactionContext = null;
		Transaction jtaTransaction = transactionManager.getTransactionQuietly();
		Transaction tccTransaction = compensableManager.getCompensableTransactionQuietly();
		if (jtaTransaction == null && tccTransaction == null) {
			throw new IllegalStateException();
		} else if (tccTransaction == null) {
			transactionContext = jtaTransaction.getTransactionContext();
		} else {
			transactionContext = tccTransaction.getTransactionContext();
		}
		boolean isCompensableTransaction = transactionContext.isCompensable();
		(isCompensableTransaction ? compensableManager : transactionManager).setRollbackOnly();
	}

	public void setTransactionTimeout(int seconds) throws SystemException {
		TransactionManager transactionManager = this.beanFactory.getTransactionManager();
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();

		TransactionContext transactionContext = null;
		Transaction jtaTransaction = transactionManager.getTransactionQuietly();
		Transaction tccTransaction = compensableManager.getCompensableTransactionQuietly();
		if (jtaTransaction == null && tccTransaction == null) {
			throw new IllegalStateException();
		} else if (tccTransaction == null) {
			transactionContext = jtaTransaction.getTransactionContext();
		} else {
			transactionContext = tccTransaction.getTransactionContext();
		}
		boolean isCompensableTransaction = transactionContext.isCompensable();
		(isCompensableTransaction ? compensableManager : transactionManager).setTransactionTimeout(seconds);
	}

	public Transaction getTransaction() throws SystemException {
		TransactionManager transactionManager = this.beanFactory.getTransactionManager();
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();
		Transaction jtaTransaction = transactionManager.getTransactionQuietly();
		Transaction tccTransaction = compensableManager.getCompensableTransactionQuietly();
		if (jtaTransaction != null) {
			return jtaTransaction;
		} else if (tccTransaction != null) {
			return ((CompensableTransaction) tccTransaction).getTransaction();
		} else {
			return null;
		}
	}

	public Transaction getTransactionQuietly() {
		try {
			return this.getTransaction();
		} catch (Exception ex) {
			return null;
		}
	}

	public int getStatus() throws SystemException {
		Transaction transaction = this.getTransaction();
		return transaction == null ? Status.STATUS_NO_TRANSACTION : transaction.getTransactionStatus();
	}

	public void setBeanFactory(CompensableBeanFactory tbf) {
		this.beanFactory = tbf;
	}

	public void associateThread(Transaction transaction) {
		throw new IllegalStateException();
	}

	public Transaction desociateThread() {
		throw new IllegalStateException();
	}

	public int getTimeoutSeconds() {
		throw new IllegalStateException();
	}

	public void setTimeoutSeconds(int timeoutSeconds) {
		throw new IllegalStateException();
	}
}
