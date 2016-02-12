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
package org.bytesoft.bytetcc.supports.internal;

import org.bytesoft.bytetcc.CompensableTransaction;
import org.bytesoft.bytetcc.CompensableTransactionBeanFactory;
import org.bytesoft.bytetcc.TransactionContext;
import org.bytesoft.bytetcc.aware.CompensableTransactionBeanFactoryAware;
import org.bytesoft.transaction.TransactionManager;
import org.bytesoft.transaction.supports.rpc.TransactionInterceptor;
import org.bytesoft.transaction.supports.rpc.TransactionRequest;
import org.bytesoft.transaction.supports.rpc.TransactionResponse;

public class TransactionInterceptorDispatcher implements TransactionInterceptor, CompensableTransactionBeanFactoryAware {

	private TransactionInterceptor jtaTransactionInterceptor;
	private TransactionInterceptor tccTransactionInterceptor;

	private CompensableTransactionBeanFactory beanFactory;

	public void beforeSendRequest(TransactionRequest request) throws IllegalStateException {
		TransactionManager transactionManager = this.beanFactory.getCompensableTransactionManager();
		CompensableTransaction transaction = (CompensableTransaction) transactionManager.getTransactionQuietly();
		if (transaction == null) {
			return;
		} else if (transaction.getTransactionContext().isCompensable()) {
			this.tccTransactionInterceptor.beforeSendRequest(request);
		} else {
			this.jtaTransactionInterceptor.beforeSendRequest(request);
		}
	}

	public void afterReceiveRequest(TransactionRequest request) throws IllegalStateException {
		TransactionContext transactionContext = (TransactionContext) request.getTransactionContext();
		if (transactionContext == null) {
			return;
		} else if (transactionContext.isCompensable()) {
			this.tccTransactionInterceptor.afterReceiveRequest(request);
		} else {
			this.jtaTransactionInterceptor.afterReceiveRequest(request);
		}
	}

	public void beforeSendResponse(TransactionResponse response) throws IllegalStateException {
		TransactionManager transactionManager = this.beanFactory.getCompensableTransactionManager();
		CompensableTransaction transaction = (CompensableTransaction) transactionManager.getTransactionQuietly();
		if (transaction == null) {
			return;
		} else if (transaction.getTransactionContext().isCompensable()) {
			this.tccTransactionInterceptor.beforeSendResponse(response);
		} else {
			this.jtaTransactionInterceptor.beforeSendResponse(response);
		}
	}

	public void afterReceiveResponse(TransactionResponse response) throws IllegalStateException {
		TransactionContext transactionContext = (TransactionContext) response.getTransactionContext();
		if (transactionContext == null) {
			return;
		} else if (transactionContext.isCompensable()) {
			this.tccTransactionInterceptor.afterReceiveResponse(response);
		} else {
			this.jtaTransactionInterceptor.afterReceiveResponse(response);
		}
	}

	public TransactionInterceptor getJtaTransactionInterceptor() {
		return jtaTransactionInterceptor;
	}

	public void setJtaTransactionInterceptor(TransactionInterceptor jtaTransactionInterceptor) {
		this.jtaTransactionInterceptor = jtaTransactionInterceptor;
	}

	public TransactionInterceptor getTccTransactionInterceptor() {
		return tccTransactionInterceptor;
	}

	public void setTccTransactionInterceptor(TransactionInterceptor tccTransactionInterceptor) {
		this.tccTransactionInterceptor = tccTransactionInterceptor;
	}

	public void setBeanFactory(CompensableTransactionBeanFactory tbf) {
		this.beanFactory = tbf;
	}

}
