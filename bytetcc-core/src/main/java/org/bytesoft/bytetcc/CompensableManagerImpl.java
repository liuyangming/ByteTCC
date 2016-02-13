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

import java.rmi.RemoteException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.transaction.HeuristicMixedException;
import javax.transaction.HeuristicRollbackException;
import javax.transaction.InvalidTransactionException;
import javax.transaction.NotSupportedException;
import javax.transaction.RollbackException;
import javax.transaction.Status;
import javax.transaction.SystemException;
import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;

import org.apache.log4j.Logger;
import org.bytesoft.bytejta.TransactionImpl;
import org.bytesoft.bytejta.supports.wire.RemoteCoordinator;
import org.bytesoft.bytetcc.aware.CompensableBeanFactoryAware;
import org.bytesoft.common.utils.ByteUtils;
import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.CompensableManager;
import org.bytesoft.compensable.CompensableTransaction;
import org.bytesoft.compensable.TransactionContext;
import org.bytesoft.compensable.supports.logger.CompensableLogger;
import org.bytesoft.transaction.Transaction;
import org.bytesoft.transaction.TransactionRepository;
import org.bytesoft.transaction.internal.TransactionException;
import org.bytesoft.transaction.xa.TransactionXid;
import org.bytesoft.transaction.xa.XidFactory;

public class CompensableManagerImpl implements CompensableManager, CompensableBeanFactoryAware {
	static final Logger logger = Logger.getLogger(CompensableManagerImpl.class.getSimpleName());

	// private TransactionManager jtaTransactionManager;
	private CompensableBeanFactory beanFactory;

	/* it's unnecessary for compensable-transaction to do the timing, the jta-transaction will do it. */
	private int timeoutSeconds = 5 * 60;
	private final ThreadLocal<CompensableInvocation> invocations = new ThreadLocal<CompensableInvocation>();
	private final ThreadLocal<SampleCompensableImpl> transients = new ThreadLocal<SampleCompensableImpl>();
	private final Map<Thread, CompensableTransaction> associatedTxMap = new ConcurrentHashMap<Thread, CompensableTransaction>();

	public CompensableInvocation beforeCompensableExecution(CompensableInvocation lastest) {
		CompensableInvocation original = this.invocations.get();
		this.invocations.set(lastest);
		return original;
	}

	public void afterCompensableCompletion(CompensableInvocation original) {

		try {
			CompensableTransaction transaction = (CompensableTransaction) this.getTransactionQuietly();
			if (SampleCompensableImpl.class.isInstance(transaction)) {
				this.delistCompensableInvocationIfNecessary((SampleCompensableImpl) transaction);
			}
		} finally {
			this.invocations.set(original);
		}

	}

	private void delistCompensableInvocationIfNecessary(SampleCompensableImpl transaction) {
		CompensableInvocation compensable = this.invocations.get();
		this.invocations.remove();
		if (transaction != null) {
			transaction.delistCompensableInvocation(compensable);
		}
	}

	public void begin() throws NotSupportedException, SystemException {

		CompensableInvocation lastest = invocations.get();
		boolean compensable = (lastest != null);
		if (compensable) {
			this.beginCompensableTransaction();
		} else {
			this.beginJtaTransaction();
		}

	}

	private void beginJtaTransaction() throws NotSupportedException, SystemException {
		if (this.getTransaction() != null) {
			throw new NotSupportedException();
		}

		TransactionContext transactionContext = new TransactionContext();
		transactionContext.setCoordinator(true);
		transactionContext.setCompensable(false);
		long current = System.currentTimeMillis();
		transactionContext.setCreatedTime(current);
		transactionContext.setExpiredTime(current + this.timeoutSeconds);

		XidFactory xidFactory = this.beanFactory.getXidFactory();
		TransactionXid global = xidFactory.createGlobalXid();
		transactionContext.setXid(global);

		SampleTransactionImpl transaction = new SampleTransactionImpl(transactionContext);
		TransactionRepository transactionRepository = this.beanFactory.getTransactionRepository();

		RemoteCoordinator transactionCoordinator = this.beanFactory.getTransactionCoordinator();
		try {
			// this.jtaTransactionManager.begin(transactionContext);
			transactionCoordinator.start(transactionContext, XAResource.TMNOFLAGS);
			Transaction jtaTransaction = transactionCoordinator.getTransactionQuietly();
			transaction.setJtaTransaction(jtaTransaction);
			jtaTransaction.registerTransactionListener(transaction);
		} catch (TransactionException ex) {
			try {
				// this.jtaTransactionManager.rollback();
				transactionCoordinator.end(transactionContext, XAResource.TMFAIL);
			} catch (TransactionException ignore) {
				logger.warn("create jta transaction failed!");
			}
			SystemException systemEx = new SystemException();
			systemEx.initCause(ex);
			throw systemEx;
		}

		this.associateThread(transaction);
		transactionRepository.putTransaction(global, transaction);

		SampleCompensableImpl compensableTx = this.transients.get();
		if (compensableTx != null) {
			transaction.setCompensableTccTransaction(compensableTx);
			// TODO compensableTx.setCompensableJtaTransaction(transaction);
		}
	}

	private void beginCompensableTransaction() throws NotSupportedException, SystemException {
		if (this.getTransaction() != null) {
			throw new NotSupportedException();
		}

		TransactionContext transactionContext = new TransactionContext();
		transactionContext.setCoordinator(true);
		transactionContext.setCompensable(true);
		long current = System.currentTimeMillis();
		transactionContext.setCreatedTime(current);
		transactionContext.setExpiredTime(current + this.timeoutSeconds);

		SampleCompensableImpl transaction = new SampleCompensableImpl(transactionContext);

		XidFactory jtaXidFactory = this.beanFactory.getXidFactory();
		XidFactory tccXidFactory = this.beanFactory.getCompensableXidFactory();
		TransactionXid tccTransactionXid = tccXidFactory.createGlobalXid();
		TransactionXid jtaTransactionXid = jtaXidFactory.createGlobalXid(tccTransactionXid.getGlobalTransactionId());
		transactionContext.setXid(tccTransactionXid);

		TransactionContext jtaTransactionContext = transactionContext.clone();
		jtaTransactionContext.setXid(jtaTransactionXid);

		RemoteCoordinator transactionCoordinator = this.beanFactory.getTransactionCoordinator();
		try {
			// this.jtaTransactionManager.begin(jtaTransactionContext);
			transactionCoordinator.start(jtaTransactionContext, XAResource.TMNOFLAGS);
			Transaction jtaTransaction = transactionCoordinator.getTransactionQuietly();
			transaction.setJtaTransaction(jtaTransaction);
			jtaTransaction.registerTransactionListener(transaction);
		} catch (TransactionException ex) {
			try {
				// this.jtaTransactionManager.rollback();
				transactionCoordinator.end(jtaTransactionContext, XAResource.TMFAIL);
			} catch (TransactionException ignore) {
				logger.warn("create jta transaction failed!");
			}
			SystemException systemEx = new SystemException();
			systemEx.initCause(ex);
			throw systemEx;
		}
		this.associateThread(transaction);

		TransactionRepository transactionRepository = this.beanFactory.getTransactionRepository();
		transactionRepository.putTransaction(tccTransactionXid, transaction);

		CompensableLogger transactionLogger = this.beanFactory.getCompensableLogger();
		transactionLogger.createTransaction(transaction.getTransactionArchive());

		logger.info(String.format("<%s> begin transaction successfully.",
				ByteUtils.byteArrayToString(tccTransactionXid.getGlobalTransactionId())));
	}

	// public void propagationBegin(TransactionContext transactionContext) throws NotSupportedException, SystemException
	// {
	//
	// if (this.getCurrentTransaction() != null) {
	// throw new NotSupportedException();
	// }
	//
	// TransactionRepository transactionRepository = this.beanFactory.getTransactionRepository();
	//
	// TransactionXid propagationXid = transactionContext.getXid();
	// TransactionXid globalXid = propagationXid.getGlobalXid();
	// CompensableTccTransaction transaction = (CompensableTccTransaction) transactionRepository
	// .getTransaction(globalXid);
	//
	// TransactionContext jtaTransactionContext = transactionContext.clone();
	//
	// if (transaction == null) {
	// transaction = new CompensableTccTransaction(transactionContext);
	// this.processBeginJtaTransaction(transaction, jtaTransactionContext);
	// transactionRepository.putTransaction(globalXid, transaction);
	//
	// CompensableTransactionLogger transactionLogger = this.beanFactory.getCompensableLogger();
	// transactionLogger.createTransaction(transaction.getTransactionArchive());
	//
	// logger.info(String.format("<%s> propagate transaction branch successfully.",
	// ByteUtils.byteArrayToString(globalXid.getGlobalTransactionId())));
	// } else {
	// this.processBeginJtaTransaction(transaction, jtaTransactionContext);
	// transaction.propagationBegin(transactionContext);
	// }
	//
	// this.associateds.put(Thread.currentThread(), transaction);
	// // this.transactionStatistic.fireBeginTransaction(transaction);
	//
	// }
	//
	// private void processBeginJtaTransaction(CompensableTccTransaction transaction,
	// TransactionContext jtaTransactionContext) throws NotSupportedException, SystemException {
	//
	// RemoteCoordinator transactionCoordinator = this.beanFactory.getTransactionCoordinator();
	// try {
	// // this.jtaTransactionManager.begin(transactionContext);
	// transactionCoordinator.start(jtaTransactionContext, XAResource.TMNOFLAGS);
	// Transaction jtaTransaction = transactionCoordinator.getTransactionQuietly();
	// transaction.setJtaTransaction(jtaTransaction);
	// jtaTransaction.registerTransactionListener(transaction);
	// } catch (TransactionException ex) {
	// try {
	// // this.jtaTransactionManager.rollback();
	// transactionCoordinator.end(jtaTransactionContext, XAResource.TMFAIL);
	// } catch (TransactionException ignore) {
	// logger.warn("create jta transaction failed!");
	// }
	// SystemException systemEx = new SystemException();
	// systemEx.initCause(ex);
	// throw systemEx;
	// }
	//
	// }

	public void propagationFinish(TransactionContext transactionContext) throws SystemException,
			HeuristicMixedException, HeuristicRollbackException, RollbackException {

		// CompensableTccTransaction transaction = (CompensableTccTransaction) this.getCurrentTransaction();
		// transaction.propagationFinish(transactionContext);
		// this.associateds.remove(Thread.currentThread());
		//
		// CompensableTransactionLogger transactionLogger = this.beanFactory.getCompensableLogger();
		// transactionLogger.createTransaction(transaction.getTransactionArchive());
		//
		// // this.jtaTransactionManager.commit();
		//
		// RemoteCoordinator transactionCoordinator = this.beanFactory.getTransactionCoordinator();
		// try {
		// transactionCoordinator.commit(transactionContext.getXid(), false);
		// } catch (XAException xaex) {
		// // TODO
		// }

	}

	public void commit() throws RollbackException, HeuristicMixedException, HeuristicRollbackException,
			SecurityException, IllegalStateException, SystemException {
		CompensableTransaction transaction = this.desociateThread();
		if (transaction == null) {
			throw new IllegalStateException();
		}

		TransactionContext transactionContext = transaction.getTransactionContext();
		if (transactionContext.isCompensable()) {
			this.commitTccTransaction((SampleCompensableImpl) transaction);
		} else {
			this.commitJtaTransaction((SampleTransactionImpl) transaction);
		}
	}

	public void internalCommitJtaTransaction() throws RollbackException, HeuristicMixedException,
			HeuristicRollbackException, SecurityException, IllegalStateException, SystemException {
		CompensableTransaction transaction = this.desociateThread();
		if (transaction == null) {
			throw new IllegalStateException();
		} else if (TransactionImpl.class.isInstance(transaction) == false) {
			throw new IllegalStateException();
		}

		this.commitJtaTransaction((SampleTransactionImpl) transaction);
	}

	public void commitJtaTransaction(SampleTransactionImpl transaction) throws RollbackException,
			HeuristicMixedException, HeuristicRollbackException, SecurityException, IllegalStateException,
			SystemException {
		// this.jtaTransactionManager.commit();

		TransactionContext transactionContext = transaction.getTransactionContext();
		TransactionXid tccTransactionXid = transactionContext.getXid();
		XidFactory jtaXidFactory = this.beanFactory.getXidFactory();
		TransactionXid jtaTransactionXid = jtaXidFactory.createGlobalXid(tccTransactionXid.getGlobalTransactionId());

		RemoteCoordinator transactionCoordinator = this.beanFactory.getTransactionCoordinator();
		try {
			transactionCoordinator.commit(jtaTransactionXid, false);
		} catch (XAException xaex) {
			// TODO
		}
	}

	public void commitTccTransaction(SampleCompensableImpl transaction) throws RollbackException,
			HeuristicMixedException, HeuristicRollbackException, SecurityException, IllegalStateException,
			SystemException {

		if (transaction == null) {
			throw new IllegalStateException();
		} else if (transaction.getStatus() == Status.STATUS_ROLLEDBACK) {
			throw new RollbackException();
		} else if (transaction.getStatus() == Status.STATUS_COMMITTED) {
			return;
		} else if (transaction.getStatus() == Status.STATUS_MARKED_ROLLBACK) {
			this.rollback();
			throw new HeuristicRollbackException();
		} else if (transaction.getStatus() != Status.STATUS_ACTIVE) {
			throw new IllegalStateException();
		}

		this.delistCompensableInvocationIfNecessary(transaction);

		TransactionRepository transactionRepository = this.beanFactory.getTransactionRepository();
		CompensableLogger transactionLogger = this.beanFactory.getCompensableLogger();

		transaction.setTransactionStatus(Status.STATUS_PREPARING);
		transactionLogger.updateTransaction(transaction.getTransactionArchive());

		TransactionContext transactionContext = transaction.getTransactionContext();
		TransactionXid tccTransactionXid = transactionContext.getXid();
		XidFactory jtaXidFactory = this.beanFactory.getXidFactory();
		TransactionXid jtaTransactionXid = jtaXidFactory.createGlobalXid(tccTransactionXid.getGlobalTransactionId());

		// step1: commit try-phase-transaction
		RemoteCoordinator transactionCoordinator = this.beanFactory.getTransactionCoordinator();
		try {
			// this.jtaTransactionManager.commit();
			transactionCoordinator.commit(jtaTransactionXid, false);
		} catch (XAException ex) {
			// transactionRepository.putErrorTransaction(tccTransactionXid, transaction);
			// SystemException sysEx = new SystemException();
			// if (ex.getCause() != null) {
			// sysEx.initCause(ex.getCause());
			// }
			// throw sysEx;

			// TODO
		}

		// transaction.markCoordinatorTriedSuccessfully();
		transaction.setTransactionStatus(Status.STATUS_PREPARED);
		transaction.setCompensableStatus(SampleCompensableImpl.STATUS_TRIED);

		transaction.setTransactionStatus(Status.STATUS_COMMITTING);
		transaction.setCompensableStatus(SampleCompensableImpl.STATUS_CONFIRMING);

		transactionLogger.updateTransaction(transaction.getTransactionArchive());

		// step2: confirm
		try {
			this.processNativeConfirm(transaction);
		} catch (RuntimeException ex) {
			transactionRepository.putErrorTransaction(tccTransactionXid, transaction);
			throw new CommittingException(ex);
		}

		transaction.setCompensableStatus(SampleCompensableImpl.STATUS_CONFIRMED);
		transactionLogger.updateTransaction(transaction.getTransactionArchive());

		try {
			transaction.remoteConfirm();
		} catch (SystemException ex) {
			transactionRepository.putErrorTransaction(tccTransactionXid, transaction);
			throw new CommittingException(ex);
		} catch (RemoteException rex) {
			transactionRepository.putErrorTransaction(tccTransactionXid, transaction);
			throw new CommittingException(rex);
		} catch (RuntimeException rex) {
			transactionRepository.putErrorTransaction(tccTransactionXid, transaction);
			throw new CommittingException(rex);
		}

		transaction.setTransactionStatus(Status.STATUS_COMMITTED);
		transactionLogger.deleteTransaction(transaction.getTransactionArchive());

		logger.info(String.format("<%s> commit transaction successfully.",
				ByteUtils.byteArrayToString(tccTransactionXid.getGlobalTransactionId())));

	}

	public void processNativeConfirm(SampleCompensableImpl transaction) {
		try {
			this.transients.set(transaction);
			transaction.nativeConfirm();
		} finally {
			this.transients.remove();
		}
	}

	public int getStatus() throws SystemException {
		Transaction transaction = this.getTransaction();
		return transaction == null ? Status.STATUS_NO_TRANSACTION : transaction.getStatus();
	}

	public CompensableTransaction getTransaction() throws SystemException {
		return this.getTransactionQuietly();
	}

	public CompensableTransaction getTransactionQuietly() {
		return this.associatedTxMap.get(Thread.currentThread());
	}

	public void resume(javax.transaction.Transaction tobj) throws InvalidTransactionException, IllegalStateException,
			SystemException {
		if (CompensableTransaction.class.isInstance(tobj) == false) {
			throw new InvalidTransactionException();
		} else if (this.getTransaction() != null) {
			throw new IllegalStateException();
		}

		CompensableTransaction transaction = (CompensableTransaction) tobj;
		// Transaction jtaTransaction = transaction.getJtaTransaction();
		CompensableInvocation compensableObject = transaction.getCompensableObject();
		this.invocations.set(compensableObject);
		transaction.setCompensableObject(null);

		// this.jtaTransactionManager.resume(jtaTransaction);
		RemoteCoordinator transactionCoordinator = this.beanFactory.getTransactionCoordinator();
		TransactionContext transactionContext = transaction.getTransactionContext();
		TransactionXid tccTransactionXid = transactionContext.getXid();
		XidFactory jtaXidFactory = this.beanFactory.getXidFactory();
		TransactionXid jtaTransactionXid = jtaXidFactory.createGlobalXid(tccTransactionXid.getGlobalTransactionId());
		try {
			transactionCoordinator.start(jtaTransactionXid, XAResource.TMRESUME);
		} catch (XAException xaex) {
			// TODO
		}

		this.associateThread(transaction);
	}

	public void rollback() throws IllegalStateException, SecurityException, SystemException {
		CompensableTransaction transaction = this.desociateThread();
		if (transaction == null) {
			throw new IllegalStateException();
		}

		TransactionContext transactionContext = transaction.getTransactionContext();
		if (transactionContext.isCompensable()) {
			this.rollbackTccTransaction((SampleCompensableImpl) transaction);
		} else {
			this.rollbackJtaTransaction((SampleTransactionImpl) transaction);
		}
	}

	public void internalRollbackJtaTransaction() throws IllegalStateException, SecurityException, SystemException {
		CompensableTransaction transaction = this.desociateThread();
		if (transaction == null) {
			throw new IllegalStateException();
		} else if (TransactionImpl.class.isInstance(transaction) == false) {
			throw new IllegalStateException();
		}

		this.rollbackJtaTransaction((SampleTransactionImpl) transaction);
	}

	public void rollbackJtaTransaction(SampleTransactionImpl transaction) throws IllegalStateException,
			SecurityException, SystemException {

		TransactionContext transactionContext = transaction.getTransactionContext();
		TransactionXid tccTransactionXid = transactionContext.getXid();
		XidFactory jtaXidFactory = this.beanFactory.getXidFactory();
		TransactionXid jtaTransactionXid = jtaXidFactory.createGlobalXid(tccTransactionXid.getGlobalTransactionId());

		RemoteCoordinator transactionCoordinator = this.beanFactory.getTransactionCoordinator();
		try {
			// this.jtaTransactionManager.rollback();
			transactionCoordinator.rollback(jtaTransactionXid);
		} catch (XAException xaex) {
			// TODO
		}
	}

	public void rollbackTccTransaction(SampleCompensableImpl transaction) throws IllegalStateException,
			SecurityException, SystemException {

		if (transaction == null) {
			throw new IllegalStateException();
		} else if (transaction.getStatus() == Status.STATUS_ROLLEDBACK) {
			return;
		} else if (transaction.getStatus() == Status.STATUS_COMMITTED) {
			throw new SystemException();
		}

		this.delistCompensableInvocationIfNecessary(transaction);

		TransactionContext transactionContext = transaction.getTransactionContext();
		TransactionRepository transactionRepository = this.beanFactory.getTransactionRepository();
		CompensableLogger transactionLogger = this.beanFactory.getCompensableLogger();

		TransactionXid tccTransactionXid = transactionContext.getXid();

		RemoteCoordinator transactionCoordinator = this.beanFactory.getTransactionCoordinator();

		int transactionStatus = transaction.getStatus();
		if (transactionStatus == Status.STATUS_ACTIVE //
				|| transactionStatus == Status.STATUS_MARKED_ROLLBACK) {
			transaction.setTransactionStatus(Status.STATUS_PREPARING);
			transaction.setCompensableStatus(SampleCompensableImpl.STATUS_TRY_FAILURE);
			transactionLogger.updateTransaction(transaction.getTransactionArchive());

			XidFactory jtaXidFactory = this.beanFactory.getXidFactory();
			TransactionXid jtaTransactionXid = jtaXidFactory
					.createGlobalXid(tccTransactionXid.getGlobalTransactionId());

			// rollback try-phase-transaction
			try {
				// this.jtaTransactionManager.rollback();
				transactionCoordinator.rollback(jtaTransactionXid);
				transaction.setTransactionStatus(Status.STATUS_ROLLING_BACK);
				transaction.setCompensableStatus(SampleCompensableImpl.STATUS_TRY_FAILURE);
			} catch (XAException ex) {
				// transactionRepository.putErrorTransaction(globalXid, transaction);
				// SystemException sysEx = new SystemException();
				// sysEx.initCause(ex);
				// throw sysEx;

				// TODO
			}
		} else if (transactionStatus == Status.STATUS_PREPARING) {
			// transaction.setTransactionStatus(Status.STATUS_PREPARING);
			Transaction jtaTransaction = transactionCoordinator.getTransactionQuietly();
			if (jtaTransaction != null) {
				/* should has been committed/rolledback in this.commitTccTransaction() */
				throw new IllegalStateException();
			}
			transaction.setTransactionStatus(Status.STATUS_ROLLING_BACK);
			transaction.setCompensableStatus(SampleCompensableImpl.STATUS_TRIED);
		} else if (transaction.getStatus() == Status.STATUS_PREPARED) {
			transactionRepository.putErrorTransaction(tccTransactionXid, transaction);
			throw new CommittingException();/* never happen */
		}

		transaction.setTransactionStatus(Status.STATUS_ROLLING_BACK);

		if (transaction.getCompensableStatus() == SampleCompensableImpl.STATUS_TRIED) {
			transaction.setCompensableStatus(SampleCompensableImpl.STATUS_CANCELLING);
			transactionLogger.updateTransaction(transaction.getTransactionArchive());

			// step2: cancel
			try {
				this.processNativeCancel(transaction, true);
			} catch (RuntimeException ex) {
				transactionRepository.putErrorTransaction(tccTransactionXid, transaction);

				SystemException sysEx = new SystemException();
				sysEx.initCause(ex);
				throw sysEx;
			}

			transaction.setCompensableStatus(SampleCompensableImpl.STATUS_CANCELLED);
			transactionLogger.updateTransaction(transaction.getTransactionArchive());

		} // end-if (transaction.getCompensableStatus() == CompensableTccTransaction.STATUS_TRIED)

		try {
			transaction.remoteCancel();
		} catch (SystemException ex) {
			transactionRepository.putErrorTransaction(tccTransactionXid, transaction);
			throw ex;
		} catch (RemoteException rex) {
			transactionRepository.putErrorTransaction(tccTransactionXid, transaction);
			SystemException sysEx = new SystemException();
			sysEx.initCause(rex);
			throw sysEx;
		} catch (RuntimeException rex) {
			transactionRepository.putErrorTransaction(tccTransactionXid, transaction);
			SystemException sysEx = new SystemException();
			sysEx.initCause(rex);
			throw sysEx;
		}

		transaction.setTransactionStatus(Status.STATUS_ROLLEDBACK);
		transactionLogger.deleteTransaction(transaction.getTransactionArchive());

		logger.info(String.format("<%s> rollback transaction successfully.",
				ByteUtils.byteArrayToString(tccTransactionXid.getGlobalTransactionId())));
	}

	public void processNativeCancel(SampleCompensableImpl transaction) {
		this.processNativeCancel(transaction, false);
	}

	public void processNativeCancel(SampleCompensableImpl transaction, boolean coordinatorCancelRequired) {
		try {
			this.transients.set(transaction);
			transaction.nativeCancel(coordinatorCancelRequired);
		} finally {
			this.transients.remove();
		}
	}

	public void setRollbackOnly() throws IllegalStateException, SystemException {
		CompensableTransaction transaction = this.getTransaction();
		if (transaction == null) {
			throw new SystemException();
		}
		transaction.setRollbackOnly();
	}

	public void setTransactionTimeout(int seconds) throws SystemException {
		CompensableTransaction transaction = this.getTransaction();
		if (transaction == null) {
			throw new SystemException();
		} else if (seconds < 0) {
			throw new SystemException();
		} else if (seconds == 0) {
			// ignore
		} else {
			synchronized (transaction) {
				TransactionContext transactionContext = transaction.getTransactionContext();
				long createdTime = transactionContext.getCreatedTime();
				transactionContext.setExpiredTime(createdTime + seconds * 1000L);
			}
		}
	}

	public CompensableTransaction suspend() throws SystemException {
		CompensableTransaction transaction = this.desociateThread();
		TransactionContext transactionContext = transaction.getTransactionContext();
		TransactionXid tccTransactionXid = transactionContext.getXid();
		XidFactory jtaXidFactory = this.beanFactory.getXidFactory();
		TransactionXid jtaTransactionXid = jtaXidFactory.createGlobalXid(tccTransactionXid.getGlobalTransactionId());

		RemoteCoordinator transactionCoordinator = this.beanFactory.getTransactionCoordinator();
		// TransactionImpl jtaTransaction = this.jtaTransactionManager.suspend();
		Transaction jtaTransaction = transactionCoordinator.getTransactionQuietly();
		try {
			transactionCoordinator.end(jtaTransactionXid, XAResource.TMSUSPEND);
		} catch (Exception ex) {
			// TODO
		}
		transaction.setJtaTransaction(jtaTransaction);
		CompensableInvocation compensableObject = this.invocations.get();
		this.invocations.remove();
		transaction.setCompensableObject(compensableObject);
		return transaction;
	}

	/* it's unnecessary for compensable-transaction to do the timing. */
	// public void timingExecution() {}
	// public void stopTiming(Transaction tx) {}

	public void associateThread(Transaction transaction) {
		this.associatedTxMap.put(Thread.currentThread(), (CompensableTransaction) transaction);
	}

	public CompensableTransaction desociateThread() {
		return this.associatedTxMap.remove(Thread.currentThread());
	}

	public void setBeanFactory(CompensableBeanFactory tbf) {
		this.beanFactory = tbf;
	}

	public int getTimeoutSeconds() {
		return timeoutSeconds;
	}

	public void setTimeoutSeconds(int timeoutSeconds) {
		this.timeoutSeconds = timeoutSeconds;
	}

}
