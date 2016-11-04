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

import org.bytesoft.bytejta.supports.wire.RemoteCoordinator;
import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.CompensableManager;
import org.bytesoft.compensable.UserCompensable;
import org.bytesoft.compensable.aware.CompensableBeanFactoryAware;
import org.bytesoft.transaction.TransactionContext;
import org.bytesoft.transaction.TransactionManager;
import org.bytesoft.transaction.internal.TransactionException;
import org.bytesoft.transaction.xa.TransactionXid;
import org.bytesoft.transaction.xa.XidFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UserCompensableImpl implements UserCompensable, Referenceable, Serializable, CompensableBeanFactoryAware {
	private static final long serialVersionUID = 1L;
	static final Logger logger = LoggerFactory.getLogger(UserCompensableImpl.class);

	private CompensableBeanFactory beanFactory;
	private TransactionManager transactionManager;

	public void compensableBegin() throws NotSupportedException, SystemException {
		RemoteCoordinator compensableCoordinator = this.beanFactory.getCompensableCoordinator();
		CompensableManager tompensableManager = this.beanFactory.getCompensableManager();
		XidFactory compensableXidFactory = this.beanFactory.getCompensableXidFactory();

		TransactionContext compensableContext = new TransactionContext();
		CompensableTransactionImpl compensable = (CompensableTransactionImpl) tompensableManager
				.getCompensableTransactionQuietly();
		if (compensable != null) {
			throw new NotSupportedException();
		}

		TransactionXid compensableXid = compensableXidFactory.createGlobalXid();
		compensableContext.setCoordinator(true);
		compensableContext.setPropagated(true);
		compensableContext.setCompensable(true);
		compensableContext.setXid(compensableXid);
		compensable = new CompensableTransactionImpl(compensableContext);
		compensable.setBeanFactory(this.beanFactory);

		try {
			compensableCoordinator.start(compensableContext, XAResource.TMNOFLAGS);
		} catch (TransactionException ex) {
			logger.error("Error occurred while beginning an compensable transaction!", ex);
			throw new SystemException(ex.getMessage());
		}
	}

	public void begin() throws NotSupportedException, SystemException {
		this.transactionManager.begin();
	}

	public void commit() throws HeuristicMixedException, HeuristicRollbackException, IllegalStateException,
			RollbackException, SecurityException, SystemException {
		this.transactionManager.commit();
	}

	public void compensableCommit() throws RollbackException, HeuristicMixedException, HeuristicRollbackException,
			SecurityException, IllegalStateException, SystemException {
		RemoteCoordinator compensableCoordinator = this.beanFactory.getCompensableCoordinator();
		CompensableManager tompensableManager = this.beanFactory.getCompensableManager();

		CompensableTransactionImpl compensable = (CompensableTransactionImpl) tompensableManager
				.getCompensableTransactionQuietly();
		if (compensable == null) {
			throw new IllegalStateException();
		}

		TransactionContext compensableContext = compensable.getTransactionContext();
		boolean success = false;
		try {
			compensableCoordinator.commit(compensableContext.getXid(), true);
			success = true;
		} catch (XAException xaex) {
			switch (xaex.errorCode) {
			case XAException.XAER_NOTA:
				throw new IllegalStateException();
			case XAException.XA_HEURRB:
				throw new HeuristicRollbackException();
			case XAException.XA_HEURMIX:
				throw new HeuristicMixedException();
			case XAException.XAER_INVAL:
				throw new IllegalStateException();
			case XAException.XAER_RMERR:
			case XAException.XAER_RMFAIL:
			default:
				throw new SystemException();
			}
		} finally {
			if (success) {
				try {
					compensableCoordinator.forget(compensableContext.getXid());
				} catch (XAException ex) {
					switch (ex.errorCode) {
					case XAException.XAER_INVAL:
						throw new IllegalStateException();
					case XAException.XAER_NOTA:
						throw new IllegalStateException();
					case XAException.XAER_RMERR:
					default:
						throw new SystemException();
					}
				}
			} // end-if (success)
		}
	}

	public void rollback() throws IllegalStateException, SecurityException, SystemException {
		this.transactionManager.rollback();
	}

	public void compensableRollback() throws IllegalStateException, SecurityException, SystemException {
		RemoteCoordinator compensableCoordinator = this.beanFactory.getCompensableCoordinator();
		CompensableManager tompensableManager = this.beanFactory.getCompensableManager();

		CompensableTransactionImpl compensable = (CompensableTransactionImpl) tompensableManager
				.getCompensableTransactionQuietly();
		if (compensable == null) {
			throw new IllegalStateException();
		}

		TransactionContext compensableContext = compensable.getTransactionContext();
		boolean success = false;
		try {
			compensableCoordinator.rollback(compensableContext.getXid());
			success = true;
		} catch (XAException xaex) {
			switch (xaex.errorCode) {
			case XAException.XAER_NOTA:
				throw new IllegalStateException();
			case XAException.XAER_INVAL:
				throw new IllegalStateException();
			case XAException.XAER_RMERR:
			case XAException.XAER_RMFAIL:
			default:
				throw new SystemException();
			}
		} finally {
			if (success) {
				try {
					compensableCoordinator.forget(compensableContext.getXid());
				} catch (XAException ex) {
					switch (ex.errorCode) {
					case XAException.XAER_INVAL:
						throw new IllegalStateException();
					case XAException.XAER_NOTA:
						throw new IllegalStateException();
					case XAException.XAER_RMERR:
					default:
						throw new SystemException();
					}
				}
			} // end-if (success)
		}
	}

	public int getStatus() throws SystemException {
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();
		return compensableManager.getStatus();
	}

	public void setRollbackOnly() throws IllegalStateException, SystemException {
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();
		compensableManager.setRollbackOnly();
	}

	public void setTransactionTimeout(int timeout) throws SystemException {
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();
		compensableManager.setTransactionTimeout(timeout);
	}

	public Reference getReference() throws NamingException {
		throw new NamingException("Not supported yet!");
	}

	public void setBeanFactory(CompensableBeanFactory beanFactory) {
		this.beanFactory = beanFactory;
	}

	public TransactionManager getTransactionManager() {
		return transactionManager;
	}

	public void setTransactionManager(TransactionManager transactionManager) {
		this.transactionManager = transactionManager;
	}

}
