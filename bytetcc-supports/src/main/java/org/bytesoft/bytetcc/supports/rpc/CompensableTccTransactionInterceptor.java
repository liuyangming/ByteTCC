/**
 * Copyright 2014-2015 yangming.liu<liuyangming@gmail.com>.
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

import javax.transaction.NotSupportedException;
import javax.transaction.RollbackException;
import javax.transaction.SystemException;
import javax.transaction.xa.XAResource;

import org.apache.log4j.Logger;
import org.bytesoft.bytetcc.CompensableTccTransaction;
import org.bytesoft.bytetcc.CompensableTransactionManager;
import org.bytesoft.bytetcc.common.TransactionConfigurator;
import org.bytesoft.transaction.TransactionContext;
import org.bytesoft.transaction.rpc.TransactionInterceptor;
import org.bytesoft.transaction.rpc.TransactionRequest;
import org.bytesoft.transaction.rpc.TransactionResource;
import org.bytesoft.transaction.rpc.TransactionResponse;
import org.bytesoft.transaction.supports.rpc.TransactionCredential;
import org.bytesoft.transaction.xa.TransactionXid;
import org.bytesoft.transaction.xa.XAResourceDescriptor;

public class CompensableTccTransactionInterceptor implements TransactionInterceptor {
	static final Logger logger = Logger.getLogger(CompensableTccTransactionInterceptor.class.getSimpleName());

	public void beforeSendRequest(TransactionRequest request) throws IllegalStateException {
		TransactionConfigurator transactionConfigurator = TransactionConfigurator.getInstance();
		CompensableTransactionManager transactionManager = transactionConfigurator.getTransactionManager();
		CompensableTccTransaction transaction = (CompensableTccTransaction) transactionManager.getCurrentTransaction();
		if (transaction != null) {
			TransactionContext srcTransactionContext = transaction.getTransactionContext();
			TransactionContext transactionContext = srcTransactionContext.clone();
			TransactionXid currentXid = srcTransactionContext.getCurrentXid();
			TransactionXid globalXid = currentXid.getGlobalXid();
			transactionContext.setCurrentXid(globalXid);
			byte[] bytes = currentXid.getBranchQualifier();
			TransactionCredential credential = new TransactionCredential(bytes);
			transactionContext.setPropagated(credential);
			request.setTransactionContext(transactionContext);

			try {
				TransactionResource resource = request.getTransactionResource();
				XAResourceDescriptor descriptor = new XAResourceDescriptor();
				descriptor.setDelegate(resource);
				descriptor.setIdentifier(resource.getIdentifier());
				descriptor.setRemote(true);
				descriptor.setSupportsXA(true);

				transaction.enlistResource(descriptor);
			} catch (IllegalStateException ex) {
				logger.error("CompensableTccTransactionInterceptor.beforeSendRequest(TransactionRequest)", ex);
				throw ex;
			} catch (RollbackException ex) {
				transaction.setRollbackOnlyQuietly();
				logger.error("CompensableTccTransactionInterceptor.beforeSendRequest(TransactionRequest)", ex);
				throw new IllegalStateException(ex);
			} catch (SystemException ex) {
				logger.error("CompensableTccTransactionInterceptor.beforeSendRequest(TransactionRequest)", ex);
				throw new IllegalStateException(ex);
			}
		}
	}

	public void afterReceiveRequest(TransactionRequest request) throws IllegalStateException {
		TransactionConfigurator transactionConfigurator = TransactionConfigurator.getInstance();
		CompensableTransactionManager transactionManager = transactionConfigurator.getTransactionManager();
		TransactionContext srcTransactionContext = request.getTransactionContext();
		if (srcTransactionContext != null) {
			TransactionContext transactionContext = srcTransactionContext.clone();
			try {
				transactionManager.propagationBegin(transactionContext);
			} catch (SystemException ex) {
				logger.error("CompensableTccTransactionInterceptor.afterReceiveRequest(TransactionRequest)", ex);
				IllegalStateException exception = new IllegalStateException();
				exception.initCause(ex);
				throw exception;
			} catch (NotSupportedException ex) {
				logger.error("CompensableTccTransactionInterceptor.afterReceiveRequest(TransactionRequest)", ex);
				IllegalStateException exception = new IllegalStateException();
				exception.initCause(ex);
				throw exception;
			}
		}

	}

	public void beforeSendResponse(TransactionResponse response) throws IllegalStateException {
		TransactionConfigurator transactionConfigurator = TransactionConfigurator.getInstance();
		CompensableTransactionManager transactionManager = transactionConfigurator.getTransactionManager();
		CompensableTccTransaction transaction = (CompensableTccTransaction) transactionManager.getCurrentTransaction();
		if (transaction != null) {
			TransactionContext srcTransactionContext = transaction.getTransactionContext();
			TransactionContext transactionContext = srcTransactionContext.clone();
			response.setTransactionContext(transactionContext);
			try {
				transactionManager.propagationFinish(transactionContext);
				// } catch (SystemException ex) {
				// logger.error("CompensableTccTransactionInterceptor.beforeSendResponse(TransactionResponse)", ex);
				// IllegalStateException exception = new IllegalStateException();
				// exception.initCause(ex);
				// throw exception;
				// } catch (HeuristicMixedException ex) {
				// } catch (HeuristicRollbackException ex) {
				// } catch (RollbackException ex) {
				// } catch (RuntimeException ex) {
			} catch (Exception ex) {
				logger.error("CompensableTccTransactionInterceptor.beforeSendResponse(TransactionResponse)", ex);
				IllegalStateException exception = new IllegalStateException();
				exception.initCause(ex);
				throw exception;
			}
		}
	}

	public void afterReceiveResponse(TransactionResponse response) throws IllegalStateException {
		TransactionConfigurator transactionConfigurator = TransactionConfigurator.getInstance();
		CompensableTransactionManager transactionManager = transactionConfigurator.getTransactionManager();
		CompensableTccTransaction transaction = (CompensableTccTransaction) transactionManager.getCurrentTransaction();
		if (transaction != null) {
			TransactionContext nativeTransactionContext = transaction.getTransactionContext();
			TransactionContext remoteTransactionContext = response.getTransactionContext();
			if (remoteTransactionContext != null) {

				TransactionXid currentXid = nativeTransactionContext.getCurrentXid();
				byte[] bytes = currentXid.getBranchQualifier();
				Object nativeCredential = new TransactionCredential(bytes);
				Object remoteCredential = remoteTransactionContext.getPropagated();

				if (nativeCredential.equals(remoteCredential)) {
					try {
						TransactionResource resource = response.getTransactionResource();

						XAResourceDescriptor descriptor = new XAResourceDescriptor();
						descriptor.setDelegate(resource);
						descriptor.setIdentifier(resource.getIdentifier());
						descriptor.setRemote(true);
						descriptor.setSupportsXA(true);

						transaction.delistResource(descriptor, XAResource.TMSUCCESS);
					} catch (IllegalStateException ex) {
						logger.error("CompensableTccTransactionInterceptor.afterReceiveResponse(TransactionRequest)", ex);
						throw ex;
					} catch (SystemException ex) {
						logger.error("CompensableTccTransactionInterceptor.afterReceiveResponse(TransactionRequest)", ex);
						throw new IllegalStateException(ex);
					}
				}
			}
		}// end-if(transaction != null)
	}

}
