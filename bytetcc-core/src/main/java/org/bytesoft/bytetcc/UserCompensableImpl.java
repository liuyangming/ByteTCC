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

import java.io.Serializable;

import javax.naming.NamingException;
import javax.naming.Reference;
import javax.naming.Referenceable;
import javax.transaction.HeuristicMixedException;
import javax.transaction.HeuristicRollbackException;
import javax.transaction.NotSupportedException;
import javax.transaction.RollbackException;
import javax.transaction.SystemException;
import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;

import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.CompensableManager;
import org.bytesoft.compensable.CompensableTransaction;
import org.bytesoft.compensable.TransactionContext;
import org.bytesoft.compensable.UserCompensable;
import org.bytesoft.compensable.aware.CompensableBeanFactoryAware;
import org.bytesoft.transaction.TransactionException;
import org.bytesoft.transaction.TransactionManager;
import org.bytesoft.transaction.TransactionParticipant;
import org.bytesoft.transaction.TransactionRepository;
import org.bytesoft.transaction.remote.RemoteCoordinator;
import org.bytesoft.transaction.xa.TransactionXid;
import org.bytesoft.transaction.xa.XidFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UserCompensableImpl implements UserCompensable, Referenceable, Serializable, CompensableBeanFactoryAware {
	private static final long serialVersionUID = 1L;
	static final Logger logger = LoggerFactory.getLogger(UserCompensableImpl.class);

	@javax.annotation.Resource
	private TransactionManager transactionManager;
	@javax.inject.Inject
	private CompensableBeanFactory beanFactory;
	private transient boolean statefully;

	public TransactionXid compensableBegin() throws NotSupportedException, SystemException {
		RemoteCoordinator compensableCoordinator = (RemoteCoordinator) this.beanFactory.getCompensableNativeParticipant();
		CompensableManager tompensableManager = this.beanFactory.getCompensableManager();
		XidFactory compensableXidFactory = this.beanFactory.getCompensableXidFactory();

		CompensableTransactionImpl compensable = (CompensableTransactionImpl) tompensableManager
				.getCompensableTransactionQuietly();
		if (compensable != null) {
			throw new NotSupportedException();
		}

		TransactionXid compensableXid = compensableXidFactory.createGlobalXid();

		TransactionContext compensableContext = new TransactionContext();
		compensableContext.setCoordinator(true);
		compensableContext.setPropagated(true);
		compensableContext.setCompensable(true);
		compensableContext.setStatefully(this.statefully);
		compensableContext.setXid(compensableXid);
		compensableContext.setPropagatedBy(compensableCoordinator.getIdentifier());
		compensable = new CompensableTransactionImpl(compensableContext);
		compensable.setBeanFactory(this.beanFactory);

		try {
			compensableCoordinator.start(compensableContext, XAResource.TMNOFLAGS);
		} catch (XAException ex) {
			logger.error("Error occurred while beginning an compensable transaction!", ex);
			throw new SystemException(ex.getMessage());
		}

		return compensableXid;
	}

	public void begin() throws NotSupportedException, SystemException {
		this.transactionManager.begin();
	}

	public void commit() throws HeuristicMixedException, HeuristicRollbackException, IllegalStateException, RollbackException,
			SecurityException, SystemException {
		this.transactionManager.commit();
	}

	public void compensableRecoverySuspend() throws NotSupportedException, SystemException {
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();
		CompensableTransaction compensable = compensableManager.getCompensableTransactionQuietly();
		if (compensable == null) {
			throw new IllegalStateException();
		}

		TransactionContext transactionContext = compensable.getTransactionContext();
		if (transactionContext.isCoordinator() == false) {
			throw new IllegalStateException();
		}

		TransactionParticipant compensableCoordinator = this.beanFactory.getCompensableNativeParticipant();

		TransactionContext compensableContext = compensable.getTransactionContext();
		try {
			compensableCoordinator.end(compensableContext, XAResource.TMSUCCESS);
		} catch (XAException ex) {
			logger.error("Error occurred while suspending an compensable transaction!", ex);
			throw new SystemException(ex.getMessage());
		}
	}

	public void compensableRecoveryResume(Xid xid) throws NotSupportedException, SystemException {
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();
		TransactionRepository transactionRepository = this.beanFactory.getCompensableRepository();
		TransactionParticipant compensableCoordinator = this.beanFactory.getCompensableNativeParticipant();
		if (xid == null) {
			throw new IllegalStateException();
		} else if (TransactionXid.class.isInstance(xid) == false) {
			throw new IllegalStateException();
		} else if (xid.getFormatId() != XidFactory.TCC_FORMAT_ID) {
			throw new IllegalStateException();
		}

		TransactionXid compensableXid = (TransactionXid) xid;
		CompensableTransaction transaction = null;
		try {
			transaction = (CompensableTransaction) transactionRepository.getTransaction(compensableXid);
		} catch (TransactionException tex) {
			logger.error("Error occurred while getting transaction from transaction repository!", tex);
			throw new IllegalStateException();
		}

		if (transaction == null) {
			throw new IllegalStateException();
		} else if (CompensableTransaction.class.isInstance(transaction) == false) {
			throw new IllegalStateException();
		}

		CompensableTransaction compensable = compensableManager.getCompensableTransactionQuietly();

		TransactionContext transactionContext = transaction.getTransactionContext();
		if (transactionContext.isCoordinator() == false) {
			throw new IllegalStateException();
		} else if (transactionContext.isRecoveried()) {
			try {
				compensableCoordinator.start(transactionContext, XAResource.TMNOFLAGS);
			} catch (XAException ex) {
				logger.error("Error occurred while resuming an compensable transaction!", ex);
				throw new SystemException(ex.getMessage());
			}
		} else if (compensable == null) {
			throw new IllegalStateException();
		} else if (compensable.equals(transaction) == false) {
			throw new IllegalStateException();
		}

	}

	public void compensableRecoveryCommit() throws RollbackException, HeuristicMixedException, HeuristicRollbackException,
			SecurityException, IllegalStateException, SystemException {
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();
		CompensableTransaction compensable = compensableManager.getCompensableTransactionQuietly();
		if (compensable == null) {
			throw new IllegalStateException();
		}

		TransactionContext transactionContext = compensable.getTransactionContext();
		if (transactionContext.isCoordinator() == false) {
			throw new IllegalStateException();
		}
		this.invokeCompensableCommit(compensable);
	}

	public void compensableCommit() throws RollbackException, HeuristicMixedException, HeuristicRollbackException,
			SecurityException, IllegalStateException, SystemException {
		CompensableManager tompensableManager = this.beanFactory.getCompensableManager();

		CompensableTransaction compensable = (CompensableTransaction) tompensableManager.getCompensableTransactionQuietly();
		if (compensable == null) {
			throw new IllegalStateException();
		}

		TransactionContext transactionContext = compensable.getTransactionContext();
		if (transactionContext.isCoordinator() == false) {
			throw new IllegalStateException();
		}

		this.invokeCompensableCommit(compensable);
	}

	private void invokeCompensableCommit(CompensableTransaction compensable) throws RollbackException, HeuristicMixedException,
			HeuristicRollbackException, SecurityException, IllegalStateException, SystemException {
		TransactionParticipant compensableCoordinator = this.beanFactory.getCompensableNativeParticipant();

		TransactionContext compensableContext = compensable.getTransactionContext();
		try {
			compensableCoordinator.end(compensableContext, XAResource.TMSUCCESS);
		} catch (XAException ex) {
			logger.error("Error occurred while beginning an compensable transaction!", ex);
			throw new SystemException(ex.getMessage());
		}

		boolean success = false;
		try {
			compensableCoordinator.commit(compensableContext.getXid(), true);
			success = true;
		} catch (XAException xaex) {
			switch (xaex.errorCode) {
			case XAException.XAER_NOTA:
				IllegalStateException stateEx = new IllegalStateException();
				stateEx.initCause(xaex);
				throw stateEx;
			case XAException.XA_HEURRB:
				HeuristicRollbackException hrex = new HeuristicRollbackException();
				hrex.initCause(xaex);
				throw hrex;
			case XAException.XA_HEURMIX:
				HeuristicMixedException hmex = new HeuristicMixedException();
				hmex.initCause(xaex);
				throw hmex;
			case XAException.XAER_INVAL:
				IllegalStateException error = new IllegalStateException();
				error.initCause(xaex);
				throw error;
			case XAException.XAER_RMERR:
			case XAException.XAER_RMFAIL:
			default:
				SystemException systemEx = new SystemException(xaex.errorCode);
				systemEx.initCause(xaex);
				throw systemEx;
			}
		} finally {
			if (success) {
				compensableCoordinator.forgetQuietly(compensableContext.getXid());
			} // end-if (success)
		}
	}

	public void rollback() throws IllegalStateException, SecurityException, SystemException {
		this.transactionManager.rollback();
	}

	public void compensableRecoveryRollback() throws IllegalStateException, SecurityException, SystemException {
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();
		CompensableTransaction compensable = compensableManager.getCompensableTransactionQuietly();
		if (compensable == null) {
			throw new IllegalStateException();
		}

		TransactionContext transactionContext = compensable.getTransactionContext();
		if (transactionContext.isCoordinator() == false) {
			throw new IllegalStateException();
		}
		this.invokeCompensableRollback(compensable);
	}

	public void compensableRollback() throws IllegalStateException, SecurityException, SystemException {
		CompensableManager tompensableManager = this.beanFactory.getCompensableManager();

		CompensableTransaction compensable = (CompensableTransaction) tompensableManager.getCompensableTransactionQuietly();
		if (compensable == null) {
			throw new IllegalStateException();
		}

		TransactionContext transactionContext = compensable.getTransactionContext();
		if (transactionContext.isCoordinator() == false) {
			throw new IllegalStateException();
		}

		this.invokeCompensableRollback(compensable);
	}

	private void invokeCompensableRollback(CompensableTransaction compensable)
			throws IllegalStateException, SecurityException, SystemException {
		TransactionParticipant compensableCoordinator = this.beanFactory.getCompensableNativeParticipant();
		TransactionContext compensableContext = compensable.getTransactionContext();

		try {
			compensableCoordinator.end(compensableContext, XAResource.TMSUCCESS);
		} catch (XAException ex) {
			logger.error("Error occurred while beginning an compensable transaction!", ex);
			throw new SystemException(ex.getMessage());
		}

		boolean success = false;
		try {
			compensableCoordinator.rollback(compensableContext.getXid());
			success = true;
		} catch (XAException xaex) {
			switch (xaex.errorCode) {
			case XAException.XAER_NOTA:
				IllegalStateException stateEx = new IllegalStateException();
				stateEx.initCause(xaex);
				throw stateEx;
			case XAException.XAER_INVAL:
				IllegalStateException error = new IllegalStateException();
				error.initCause(xaex);
				throw error;
			case XAException.XAER_RMERR:
			case XAException.XAER_RMFAIL:
			default:
				SystemException systemEx = new SystemException(xaex.errorCode);
				systemEx.initCause(xaex);
				throw systemEx;
			}
		} finally {
			if (success) {
				compensableCoordinator.forgetQuietly(compensableContext.getXid());
			} // end-if (success)
		}
	}

	public int getStatus() throws SystemException {
		return this.transactionManager.getStatus();
	}

	public void setRollbackOnly() throws IllegalStateException, SystemException {
		this.transactionManager.setRollbackOnly();
	}

	public void setTransactionTimeout(int timeout) throws SystemException {
		this.transactionManager.setTimeoutSeconds(timeout);
	}

	public Reference getReference() throws NamingException {
		throw new NamingException("Not supported yet!");
	}

	public CompensableBeanFactory getBeanFactory() {
		return this.beanFactory;
	}

	public void setBeanFactory(CompensableBeanFactory beanFactory) {
		this.beanFactory = beanFactory;
	}

	public boolean isStatefully() {
		return statefully;
	}

	public void setStatefully(boolean statefully) {
		this.statefully = statefully;
	}

	public TransactionManager getTransactionManager() {
		return transactionManager;
	}

	public void setTransactionManager(TransactionManager transactionManager) {
		this.transactionManager = transactionManager;
	}

}
