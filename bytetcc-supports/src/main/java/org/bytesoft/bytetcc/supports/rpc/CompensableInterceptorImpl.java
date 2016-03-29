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
package org.bytesoft.bytetcc.supports.rpc;

import javax.transaction.RollbackException;
import javax.transaction.SystemException;
import javax.transaction.xa.XAResource;

import org.apache.log4j.Logger;
import org.bytesoft.bytejta.supports.resource.RemoteResourceDescriptor;
import org.bytesoft.bytejta.supports.wire.RemoteCoordinator;
import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.aware.CompensableBeanFactoryAware;
import org.bytesoft.transaction.Transaction;
import org.bytesoft.transaction.TransactionContext;
import org.bytesoft.transaction.TransactionManager;
import org.bytesoft.transaction.internal.TransactionException;
import org.bytesoft.transaction.supports.rpc.TransactionInterceptor;
import org.bytesoft.transaction.supports.rpc.TransactionRequest;
import org.bytesoft.transaction.supports.rpc.TransactionResponse;
import org.bytesoft.transaction.xa.TransactionXid;
import org.bytesoft.transaction.xa.XidFactory;

public class CompensableInterceptorImpl implements TransactionInterceptor, CompensableBeanFactoryAware {
	static final Logger logger = Logger.getLogger(CompensableInterceptorImpl.class.getSimpleName());

	private CompensableBeanFactory beanFactory;

	public void beforeSendRequest(TransactionRequest request) throws IllegalStateException {
		TransactionManager transactionManager = (TransactionManager) this.beanFactory.getTransactionManager();
		XidFactory xidFactory = this.beanFactory.getCompensableXidFactory();
		Transaction transaction = (Transaction) transactionManager.getTransactionQuietly();
		if (transaction == null) {
			return;
		}

		TransactionContext srcTransactionContext = transaction.getTransactionContext();
		TransactionContext transactionContext = srcTransactionContext.clone();
		TransactionXid currentXid = srcTransactionContext.getXid();
		TransactionXid globalXid = xidFactory.createGlobalXid(currentXid.getGlobalTransactionId());
		transactionContext.setXid(globalXid);
		request.setTransactionContext(transactionContext);

		try {
			RemoteCoordinator resource = request.getTargetTransactionCoordinator();
			RemoteResourceDescriptor descriptor = new RemoteResourceDescriptor();
			descriptor.setDelegate(resource);
			descriptor.setIdentifier(resource.getIdentifier());

			transaction.enlistResource(descriptor);
		} catch (IllegalStateException ex) {
			logger.error("CompensableInterceptorImpl.beforeSendRequest(TransactionRequest)", ex);
			throw ex;
		} catch (RollbackException ex) {
			transaction.setRollbackOnlyQuietly();
			logger.error("CompensableInterceptorImpl.beforeSendRequest(TransactionRequest)", ex);
			throw new IllegalStateException(ex);
		} catch (SystemException ex) {
			logger.error("CompensableInterceptorImpl.beforeSendRequest(TransactionRequest)", ex);
			throw new IllegalStateException(ex);
		}
	}

	public void afterReceiveRequest(TransactionRequest request) throws IllegalStateException {
		TransactionContext srcTransactionContext = request.getTransactionContext();
		if (srcTransactionContext == null) {
			return;
		}

		RemoteCoordinator compensableCoordinator = this.beanFactory.getCompensableCoordinator();
		TransactionContext transactionContext = srcTransactionContext.clone();
		try {
			compensableCoordinator.start(transactionContext, XAResource.TMNOFLAGS);
		} catch (TransactionException ex) {
			logger.error("CompensableInterceptorImpl.afterReceiveRequest(TransactionRequest)", ex);
			IllegalStateException exception = new IllegalStateException();
			exception.initCause(ex);
			throw exception;
		}

	}

	public void beforeSendResponse(TransactionResponse response) throws IllegalStateException {
		TransactionManager transactionManager = (TransactionManager) this.beanFactory.getTransactionManager();
		Transaction transaction = (Transaction) transactionManager.getTransactionQuietly();
		if (transaction == null) {
			return;
		}

		RemoteCoordinator compensableCoordinator = this.beanFactory.getCompensableCoordinator();

		TransactionContext srcTransactionContext = transaction.getTransactionContext();
		TransactionContext transactionContext = srcTransactionContext.clone();
		response.setTransactionContext(transactionContext);
		try {
			compensableCoordinator.end(transactionContext, XAResource.TMSUCCESS);
		} catch (TransactionException ex) {
			logger.error("CompensableInterceptorImpl.beforeSendResponse(TransactionResponse)", ex);
			IllegalStateException exception = new IllegalStateException();
			exception.initCause(ex);
			throw exception;
		}
	}

	public void afterReceiveResponse(TransactionResponse response) throws IllegalStateException {
		TransactionManager transactionManager = (TransactionManager) this.beanFactory.getTransactionManager();
		TransactionContext remoteTransactionContext = response.getTransactionContext();
		Transaction transaction = (Transaction) transactionManager.getTransactionQuietly();
		if (transaction == null || remoteTransactionContext == null) {
			return;
		}

		try {
			RemoteCoordinator resource = response.getSourceTransactionCoordinator();

			RemoteResourceDescriptor descriptor = new RemoteResourceDescriptor();
			descriptor.setDelegate(resource);
			descriptor.setIdentifier(resource.getIdentifier());

			transaction.delistResource(descriptor, XAResource.TMSUCCESS);
		} catch (IllegalStateException ex) {
			logger.error("CompensableInterceptorImpl.afterReceiveResponse(TransactionRequest)", ex);
			throw ex;
		} catch (SystemException ex) {
			logger.error("CompensableInterceptorImpl.afterReceiveResponse(TransactionRequest)", ex);
			throw new IllegalStateException(ex);
		}
	}

	public void setBeanFactory(CompensableBeanFactory tbf) {
		this.beanFactory = tbf;
	}

	public CompensableBeanFactory getBeanFactory() {
		return beanFactory;
	}

}
