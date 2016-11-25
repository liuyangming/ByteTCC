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

import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.CompensableManager;
import org.bytesoft.compensable.CompensableTransaction;
import org.bytesoft.compensable.TransactionContext;
import org.bytesoft.compensable.aware.CompensableBeanFactoryAware;
import org.bytesoft.transaction.supports.rpc.TransactionInterceptor;
import org.bytesoft.transaction.supports.rpc.TransactionRequest;
import org.bytesoft.transaction.supports.rpc.TransactionResponse;

public class TransactionInterceptorImpl implements TransactionInterceptor, CompensableBeanFactoryAware {

	private CompensableBeanFactory beanFactory;

	// private TransactionInterceptor transactionInterceptor;
	private TransactionInterceptor compensableInterceptor;

	public void beforeSendRequest(TransactionRequest request) throws IllegalStateException {
		// TransactionManager transactionManager = this.beanFactory.getTransactionManager();
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();
		CompensableTransaction compensable = compensableManager.getCompensableTransactionQuietly();
		// Transaction transaction = transactionManager.getTransactionQuietly();
		if (compensable != null && compensable.getTransactionContext().isCompensable()) {
			this.compensableInterceptor.beforeSendRequest(request);
		} else /* if (transaction != null) */ {
			throw new IllegalStateException(
					"Only compensable transaction can propagate its transaction context to the remote node!");
		}
	}

	public void afterReceiveRequest(TransactionRequest request) throws IllegalStateException {
		TransactionContext transactionContext = (TransactionContext) request.getTransactionContext();
		if (transactionContext != null && transactionContext.isCompensable()) {
			this.compensableInterceptor.afterReceiveRequest(request);
		} else /* if (transactionContext != null && transactionContext.isCompensable() == false) */ {
			throw new IllegalStateException(
					"Only compensable transaction can propagate its transaction context to the remote node!");
		}
	}

	public void beforeSendResponse(TransactionResponse response) throws IllegalStateException {
		// TransactionManager transactionManager = this.beanFactory.getTransactionManager();
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();
		CompensableTransaction compensable = compensableManager.getCompensableTransactionQuietly();
		// Transaction transaction = transactionManager.getTransactionQuietly();
		if (compensable != null && compensable.getTransactionContext().isCompensable()) {
			this.compensableInterceptor.beforeSendResponse(response);
		} else /* if (transaction != null) */ {
			throw new IllegalStateException(
					"Only compensable transaction can propagate its transaction context to the remote node!");
		}
	}

	public void afterReceiveResponse(TransactionResponse response) throws IllegalStateException {
		// TransactionManager transactionManager = this.beanFactory.getTransactionManager();
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();
		CompensableTransaction compensable = compensableManager.getCompensableTransactionQuietly();
		// Transaction transaction = transactionManager.getTransactionQuietly();
		if (compensable != null && compensable.getTransactionContext().isCompensable()) {
			this.compensableInterceptor.afterReceiveResponse(response);
		} else /* if (transaction != null) */ {
			throw new IllegalStateException(
					"Only compensable transaction can propagate its transaction context to the remote node!");
		}
	}

	public void setBeanFactory(CompensableBeanFactory tbf) {
		this.beanFactory = tbf;
	}

	// public TransactionInterceptor getTransactionInterceptor() {
	// return transactionInterceptor;
	// }
	// public void setTransactionInterceptor(TransactionInterceptor transactionInterceptor) {
	// this.transactionInterceptor = transactionInterceptor;
	// }

	public TransactionInterceptor getCompensableInterceptor() {
		return compensableInterceptor;
	}

	public void setCompensableInterceptor(TransactionInterceptor compensableInterceptor) {
		this.compensableInterceptor = compensableInterceptor;
	}

}
