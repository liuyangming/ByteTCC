/**
 * Copyright 2014-2017 yangming.liu<bytefox@126.com>.
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
package org.bytesoft.bytetcc.supports.springcloud.feign;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

import org.bytesoft.bytejta.supports.rpc.TransactionRequestImpl;
import org.bytesoft.bytejta.supports.rpc.TransactionResponseImpl;
import org.bytesoft.bytetcc.CompensableTransactionImpl;
import org.bytesoft.bytetcc.supports.springcloud.SpringCloudBeanRegistry;
import org.bytesoft.bytetcc.supports.springcloud.loadbalancer.CompensableLoadBalancerInterceptor;
import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.CompensableManager;
import org.bytesoft.compensable.TransactionContext;
import org.bytesoft.transaction.remote.RemoteCoordinator;
import org.bytesoft.transaction.supports.rpc.TransactionInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.netflix.loadbalancer.Server;

public class CompensableFeignHandler implements InvocationHandler {
	static final Logger logger = LoggerFactory.getLogger(CompensableFeignHandler.class);

	private InvocationHandler delegate;
	private volatile boolean statefully;

	public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
		if (Object.class.equals(method.getDeclaringClass())) {
			return method.invoke(this, args);
		}

		final SpringCloudBeanRegistry beanRegistry = SpringCloudBeanRegistry.getInstance();
		CompensableBeanFactory beanFactory = beanRegistry.getBeanFactory();
		CompensableManager compensableManager = beanFactory.getCompensableManager();
		final TransactionInterceptor transactionInterceptor = beanFactory.getTransactionInterceptor();

		final CompensableTransactionImpl compensable = //
				(CompensableTransactionImpl) compensableManager.getCompensableTransactionQuietly();
		if (compensable == null) {
			return this.delegate.invoke(proxy, method, args);
		}

		final TransactionContext transactionContext = compensable.getTransactionContext();
		if (transactionContext.isCompensable() == false) {
			return this.delegate.invoke(proxy, method, args);
		}

		final TransactionRequestImpl request = new TransactionRequestImpl();
		final TransactionResponseImpl response = new TransactionResponseImpl();

		beanRegistry.setLoadBalancerInterceptor(new CompensableLoadBalancerInterceptor(this.statefully) {
			public void afterCompletion(Server server) {
				beanRegistry.removeLoadBalancerInterceptor();

				if (server == null) {
					logger.warn(
							"There is no suitable server, the TransactionInterceptor.beforeSendRequest() operation is not executed!");
					return;
				} // end-if (server == null)

				// TransactionRequestImpl request = new TransactionRequestImpl();
				request.setTransactionContext(transactionContext);

				String instanceId = this.getInstanceId(server);

				RemoteCoordinator coordinator = beanRegistry.getConsumeCoordinator(instanceId);
				request.setTargetTransactionCoordinator(coordinator);

				transactionInterceptor.beforeSendRequest(request);
			}
		});

		// TODO should be replaced by CompensableFeignResult.getTransactionContext()
		response.setTransactionContext(transactionContext);

		try {
			Object result = this.delegate.invoke(proxy, method, args);
			if (CompensableFeignResult.class.isInstance(result)) {
				CompensableFeignResult cfresult = (CompensableFeignResult) result;
				response.setTransactionContext(cfresult.getTransactionContext());
				response.setParticipantDelistFlag(cfresult.isParticipantValidFlag() == false);
				return cfresult.getResult();
			} else {
				return result;
			}
		} catch (CompensableFeignResult error) {
			CompensableFeignResult cfresult = (CompensableFeignResult) error;
			response.setTransactionContext(cfresult.getTransactionContext());
			response.setParticipantDelistFlag(cfresult.isParticipantValidFlag() == false);

			Object targetResult = cfresult.getResult();
			if (RuntimeException.class.isInstance(targetResult)) {
				throw (RuntimeException) targetResult;
			} else {
				throw new RuntimeException((Exception) targetResult);
			}
		} finally {
			Object interceptedValue = response.getHeader(TransactionInterceptor.class.getName());
			if (Boolean.valueOf(String.valueOf(interceptedValue)) == false) {
				response.setParticipantEnlistFlag(request.isParticipantEnlistFlag());

				RemoteCoordinator coordinator = request.getTargetTransactionCoordinator();
				// TODO should be replaced by CompensableFeignResult.getRemoteParticipant()
				response.setSourceTransactionCoordinator(coordinator);

				transactionInterceptor.afterReceiveResponse(response);
			} // end-if (response.isIntercepted() == false)
		}
	}

	public boolean isStatefully() {
		return statefully;
	}

	public void setStatefully(boolean statefully) {
		this.statefully = statefully;
	}

	public InvocationHandler getDelegate() {
		return delegate;
	}

	public void setDelegate(InvocationHandler delegate) {
		this.delegate = delegate;
	}

}
