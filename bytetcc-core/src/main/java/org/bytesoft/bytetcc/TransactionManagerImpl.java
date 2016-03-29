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
import org.bytesoft.bytejta.supports.wire.RemoteCoordinator;
import org.bytesoft.common.utils.ByteUtils;
import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.CompensableInvocation;
import org.bytesoft.compensable.CompensableInvocationRegistry;
import org.bytesoft.compensable.CompensableManager;
import org.bytesoft.compensable.CompensableTransaction;
import org.bytesoft.compensable.aware.CompensableBeanFactoryAware;
import org.bytesoft.transaction.Transaction;
import org.bytesoft.transaction.TransactionContext;
import org.bytesoft.transaction.TransactionManager;
import org.bytesoft.transaction.TransactionRepository;
import org.bytesoft.transaction.internal.TransactionException;
import org.bytesoft.transaction.xa.TransactionXid;
import org.bytesoft.transaction.xa.XidFactory;

/**
 * The compensable transaction manager implementation in the try phase.
 * 
 * @author liuyangming
 */
public class TransactionManagerImpl implements TransactionManager, CompensableBeanFactoryAware {
	static final Logger logger = Logger.getLogger(TransactionManagerImpl.class.getSimpleName());

	private CompensableBeanFactory beanFactory;
	private final Map<Thread, CompensableTransaction> transactionMap = new ConcurrentHashMap<Thread, CompensableTransaction>();

	public void begin() throws NotSupportedException, SystemException {

		RemoteCoordinator jtaTransactionCoordinator = this.beanFactory.getTransactionCoordinator();
		XidFactory jtaXidFactory = this.beanFactory.getTransactionXidFactory();
		XidFactory tccXidFactory = this.beanFactory.getCompensableXidFactory();

		TransactionXid tccTransactionXid = tccXidFactory.createGlobalXid();
		TransactionXid jtaTransactionXid = jtaXidFactory.createGlobalXid(tccTransactionXid.getGlobalTransactionId());

		TransactionContext transactionContext = new TransactionContext();
		CompensableTransactionImpl transaction = (CompensableTransactionImpl) this.getTransactionQuietly();
		if (transaction == null) {
			transactionContext.setCoordinator(true);
			transactionContext.setXid(tccTransactionXid);
			transaction = new CompensableTransactionImpl(transactionContext);
			transaction.setBeanFactory(this.beanFactory);
		}

		TransactionContext jtaTransactionContext = new TransactionContext();
		long current = System.currentTimeMillis();
		jtaTransactionContext.setCreatedTime(current);
		jtaTransactionContext.setExpiredTime(current + 1000L * 60 * 5);
		jtaTransactionContext.setXid(jtaTransactionXid);
		try {
			Transaction jtaTransaction = jtaTransactionCoordinator.start(jtaTransactionContext, XAResource.TMNOFLAGS);
			jtaTransaction.setTransactionalExtra(transaction);
			transaction.setTransactionalExtra(jtaTransaction);
		} catch (TransactionException tex) {
			try {
				jtaTransactionCoordinator.end(jtaTransactionContext, XAResource.TMFAIL);
			} catch (TransactionException ignore) {
				// ignore
			}
			logger.info(String.format("[%s] begin-transaction<try>: error occurred while starting jta-transaction: %s",
					ByteUtils.byteArrayToString(tccTransactionXid.getGlobalTransactionId()),
					ByteUtils.byteArrayToString(jtaTransactionXid.getGlobalTransactionId())));

			throw new SystemException("Error occurred while beginning a compensable-transaction!");
		}

		CompensableInvocationRegistry registry = CompensableInvocationRegistry.getInstance();
		CompensableInvocation invocation = registry.getCurrent();
		if (invocation != null && invocation.isAvailable()) {
			invocation.markUnavailable();
			transactionContext.setCompensable(true);
			transaction.registerCompensableInvocation(invocation);
		}

		TransactionRepository transactionRepository = this.beanFactory.getTransactionRepository();
		this.associateThread(transaction);
		transactionRepository.putTransaction(tccTransactionXid, transaction);

	}

	public void commit() throws RollbackException, HeuristicMixedException, HeuristicRollbackException,
			SecurityException, IllegalStateException, SystemException {
		TransactionRepository transactionRepository = this.beanFactory.getTransactionRepository();
		RemoteCoordinator jtaTransactionCoordinator = this.beanFactory.getTransactionCoordinator();

		CompensableTransaction transaction = this.getTransactionQuietly();
		Transaction jtaTransaction = transaction.getTransaction();
		TransactionContext tccTransactionContext = transaction.getTransactionContext();
		TransactionContext jtaTransactionContext = jtaTransaction.getTransactionContext();

		TransactionXid jtaTransactionXid = jtaTransactionContext.getXid();
		// TransactionXid tccTransactionXid = tccTransactionContext.getXid();

		boolean coordinator = tccTransactionContext.isCoordinator();
		boolean compensable = tccTransactionContext.isCompensable();
		if (coordinator) {
			this.desociateThread();
		}

		boolean commitExists = false;
		boolean rollbackExists = false;
		boolean errorExists = false;
		try {
			jtaTransactionCoordinator.end(jtaTransactionContext, XAResource.TMSUCCESS);
			jtaTransactionCoordinator.commit(jtaTransactionXid, true);
			commitExists = true;
		} catch (XAException xaex) {
			switch (xaex.errorCode) {
			case XAException.XA_HEURRB:
				rollbackExists = true;
				break;
			case XAException.XA_HEURMIX:
				commitExists = true;
				rollbackExists = true;
				break;
			default:
				errorExists = true;
				break;
			}
		} finally {
			transaction.setTransactionalExtra(null);

			if (compensable && coordinator) {
				boolean success = false;
				try {
					if (commitExists && rollbackExists) {
						this.fireCompensableCommit(transaction); // TODO
						success = true;
					} else if (errorExists) {
						this.fireCompensableCommit(transaction); // TODO
						success = true;
					} else if (commitExists) {
						this.fireCompensableCommit(transaction); // TODO
						success = true;
					} else if (rollbackExists) {
						this.fireCompensableRollback(transaction);
						success = true;
					} else {
						success = true;
					}
				} finally {
					TransactionXid xid = tccTransactionContext.getXid();
					if (success) {
						transactionRepository.removeTransaction(xid);
					} else {
						transactionRepository.putErrorTransaction(xid, transaction);
					}
				}
			}
		}

	}

	public void fireCompensableCommit(CompensableTransaction transaction) throws RollbackException,
			HeuristicMixedException, HeuristicRollbackException, SecurityException, IllegalStateException,
			SystemException {
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();
		try {
			compensableManager.associateThread(transaction);
			compensableManager.compensableCommit();
		} finally {
			compensableManager.desociateThread();
		}
	}

	public void rollback() throws IllegalStateException, SecurityException, SystemException {
		RemoteCoordinator jtaTransactionCoordinator = this.beanFactory.getTransactionCoordinator();

		CompensableTransaction transaction = this.desociateThread();
		TransactionContext transactionContext = transaction.getTransactionContext();

		Transaction jtaTransaction = transaction.getTransaction();
		TransactionContext jtaTransactionContext = jtaTransaction.getTransactionContext();
		TransactionXid jtaTransactionXid = jtaTransactionContext.getXid();
		// boolean failure = true;
		try {
			jtaTransactionCoordinator.end(jtaTransactionContext, XAResource.TMSUCCESS);
			jtaTransactionCoordinator.rollback(jtaTransactionXid);
			// failure = false;
		} catch (XAException ex) {
			// TODO logger
			// failure = true;
		} finally {
			transaction.setTransactionalExtra(null);

			boolean compenseRequired = transactionContext.isCompensable() && transactionContext.isCoordinator();
			if (compenseRequired) {
				this.fireCompensableRollback(transaction);
			}
		}

	}

	public void fireCompensableRollback(CompensableTransaction transaction) throws IllegalStateException,
			SecurityException, SystemException {
		// TransactionContext transactionContext = transaction.getTransactionContext();
		// TransactionXid xid = transactionContext.getXid();
		// TransactionRepository transactionRepository = this.beanFactory.getTransactionRepository();

		CompensableManager compensableManager = this.beanFactory.getCompensableManager();
		try {
			compensableManager.associateThread(transaction);
			compensableManager.compensableRollback();
		} finally {
			compensableManager.desociateThread();
		}
	}

	public int getStatus() throws SystemException {
		CompensableTransaction transaction = this.transactionMap.get(Thread.currentThread());
		Transaction jtaTransaction = transaction == null ? null : transaction.getTransaction();
		return jtaTransaction == null ? Status.STATUS_NO_TRANSACTION : jtaTransaction.getStatus();
	}

	public Transaction suspend() throws SystemException {
		Transaction transaction = this.desociateThread();
		transaction.suspend();
		return transaction;
	}

	public void resume(javax.transaction.Transaction tobj) throws InvalidTransactionException, IllegalStateException,
			SystemException {

		if (CompensableTransaction.class.isInstance(tobj) == false) {
			throw new InvalidTransactionException();
		} else if (this.getTransaction() != null) {
			throw new IllegalStateException();
		}

		CompensableTransaction transaction = (CompensableTransaction) tobj;
		transaction.resume();
		this.associateThread(transaction);
	}

	public void associateThread(Transaction transaction) {
		this.transactionMap.put(Thread.currentThread(), (CompensableTransaction) transaction);
	}

	public CompensableTransaction desociateThread() {
		return this.transactionMap.remove(Thread.currentThread());
	}

	public CompensableTransaction getTransactionQuietly() {
		try {
			return (CompensableTransaction) this.getTransaction();
		} catch (SystemException ex) {
			return null;
		} catch (RuntimeException ex) {
			return null;
		}
	}

	public Transaction getTransaction() throws SystemException {
		return this.transactionMap.get(Thread.currentThread());
	}

	public void setRollbackOnly() throws IllegalStateException, SystemException {
	}

	public void setTransactionTimeout(int seconds) throws SystemException {
	}

	public int getTimeoutSeconds() {
		return 0;
	}

	public void setTimeoutSeconds(int timeoutSeconds) {
	}

	public void setBeanFactory(CompensableBeanFactory tbf) {
		this.beanFactory = tbf;
	}
}
