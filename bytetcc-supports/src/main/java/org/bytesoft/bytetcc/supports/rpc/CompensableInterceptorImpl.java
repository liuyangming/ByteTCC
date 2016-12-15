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

import org.bytesoft.bytejta.supports.resource.RemoteResourceDescriptor;
import org.bytesoft.bytejta.supports.rpc.TransactionRequestImpl;
import org.bytesoft.bytejta.supports.rpc.TransactionResponseImpl;
import org.bytesoft.bytejta.supports.wire.RemoteCoordinator;
import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.CompensableManager;
import org.bytesoft.compensable.CompensableTransaction;
import org.bytesoft.compensable.aware.CompensableBeanFactoryAware;
import org.bytesoft.transaction.TransactionContext;
import org.bytesoft.transaction.internal.TransactionException;
import org.bytesoft.transaction.supports.rpc.TransactionInterceptor;
import org.bytesoft.transaction.supports.rpc.TransactionRequest;
import org.bytesoft.transaction.supports.rpc.TransactionResponse;
import org.bytesoft.transaction.xa.TransactionXid;
import org.bytesoft.transaction.xa.XidFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CompensableInterceptorImpl implements TransactionInterceptor, CompensableBeanFactoryAware {
	static final Logger logger = LoggerFactory.getLogger(CompensableInterceptorImpl.class);

	private CompensableBeanFactory beanFactory;

	public void beforeSendRequest(TransactionRequest request) throws IllegalStateException {
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();
		XidFactory xidFactory = this.beanFactory.getCompensableXidFactory();
		CompensableTransaction transaction = compensableManager.getCompensableTransactionQuietly();
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

			boolean participantEnlisted = transaction.enlistResource(descriptor);
			((TransactionRequestImpl) request).setParticipantEnlistFlag(participantEnlisted);
		} catch (IllegalStateException ex) {
			logger.error("CompensableInterceptorImpl.beforeSendRequest({})", request, ex);
			throw ex;
		} catch (RollbackException ex) {
			transaction.setRollbackOnlyQuietly();
			logger.error("CompensableInterceptorImpl.beforeSendRequest({})", request, ex);
			throw new IllegalStateException(ex);
		} catch (SystemException ex) {
			logger.error("CompensableInterceptorImpl.beforeSendRequest({})", request, ex);
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
		transactionContext.setPropagatedBy(srcTransactionContext.getPropagatedBy());
		try {
			compensableCoordinator.start(transactionContext, XAResource.TMNOFLAGS);
		} catch (TransactionException ex) {
			logger.error("CompensableInterceptorImpl.afterReceiveRequest({})", request, ex);
			IllegalStateException exception = new IllegalStateException();
			exception.initCause(ex);
			throw exception;
		}

	}

	public void beforeSendResponse(TransactionResponse response) throws IllegalStateException {
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();
		CompensableTransaction transaction = compensableManager.getCompensableTransactionQuietly();
		if (transaction == null) {
			return;
		}

		RemoteCoordinator compensableCoordinator = this.beanFactory.getCompensableCoordinator();

		TransactionContext srcTransactionContext = transaction.getTransactionContext();
		TransactionContext transactionContext = srcTransactionContext.clone();
		transactionContext.setPropagatedBy(srcTransactionContext.getPropagatedBy());
		response.setTransactionContext(transactionContext);
		try {
			compensableCoordinator.end(transactionContext, XAResource.TMSUCCESS);
		} catch (TransactionException ex) {
			logger.error("CompensableInterceptorImpl.beforeSendResponse({})", response, ex);
			IllegalStateException exception = new IllegalStateException();
			exception.initCause(ex);
			throw exception;
		}
	}

	public void afterReceiveResponse(TransactionResponse response) throws IllegalStateException {
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();
		TransactionContext remoteTransactionContext = response.getTransactionContext();
		CompensableTransaction transaction = compensableManager.getCompensableTransactionQuietly();

		boolean participantEnlistFlag = ((TransactionResponseImpl) response).isParticipantEnlistFlag();
		boolean participantDelistFlag = ((TransactionResponseImpl) response).isParticipantDelistFlag();

		if (transaction == null || remoteTransactionContext == null) {
			return;
		} else if (participantEnlistFlag == false) {
			return;
		}

		try {
			RemoteCoordinator resource = response.getSourceTransactionCoordinator();

			RemoteResourceDescriptor descriptor = new RemoteResourceDescriptor();
			descriptor.setDelegate(resource);
			descriptor.setIdentifier(resource.getIdentifier());

			transaction.delistResource(descriptor, participantDelistFlag ? XAResource.TMFAIL : XAResource.TMSUCCESS);
		} catch (IllegalStateException ex) {
			logger.error("CompensableInterceptorImpl.afterReceiveResponse({})", response, ex);
			throw ex;
		} catch (SystemException ex) {
			logger.error("CompensableInterceptorImpl.afterReceiveResponse({})", response, ex);
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
