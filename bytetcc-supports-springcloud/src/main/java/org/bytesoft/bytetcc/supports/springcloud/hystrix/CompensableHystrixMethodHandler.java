/**
 * Copyright 2014-2018 yangming.liu<bytefox@126.com>.
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
package org.bytesoft.bytetcc.supports.springcloud.hystrix;

import java.lang.reflect.Method;
import java.util.Map;

import org.bytesoft.bytejta.supports.rpc.TransactionRequestImpl;
import org.bytesoft.bytejta.supports.rpc.TransactionResponseImpl;
import org.bytesoft.bytetcc.CompensableTransactionImpl;
import org.bytesoft.bytetcc.supports.springcloud.SpringCloudBeanRegistry;
import org.bytesoft.bytetcc.supports.springcloud.feign.CompensableFeignResult;
import org.bytesoft.bytetcc.supports.springcloud.loadbalancer.CompensableLoadBalancerInterceptor;
import org.bytesoft.compensable.*;
import org.bytesoft.transaction.remote.RemoteCoordinator;
import org.bytesoft.transaction.supports.rpc.TransactionInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.netflix.loadbalancer.Server;

import feign.InvocationHandlerFactory.MethodHandler;

public class CompensableHystrixMethodHandler implements MethodHandler {
	static final Logger logger = LoggerFactory.getLogger(CompensableHystrixMethodHandler.class);

	private final Map<Method, MethodHandler> dispatch;
	private volatile boolean statefully;

	public CompensableHystrixMethodHandler(Map<Method, MethodHandler> handlerMap) {
		this.dispatch = handlerMap;
	}

	public Object invoke(Object[] argv) throws Throwable {
		final SpringCloudBeanRegistry beanRegistry = SpringCloudBeanRegistry.getInstance();
		CompensableBeanFactory beanFactory = beanRegistry.getBeanFactory();
		CompensableManager compensableManager = beanFactory.getCompensableManager();
		TransactionBeanFactory transactionBeanFactory = (TransactionBeanFactory) beanRegistry.getBeanFactory();
		final TransactionInterceptor transactionInterceptor = transactionBeanFactory.getTransactionInterceptor();

		CompensableHystrixInvocation invocation = (CompensableHystrixInvocation) argv[0];
		Thread thread = invocation.getThread(); // (Thread) argv[0];
		Method method = invocation.getMethod(); // (Method) argv[1];
		Object[] args = invocation.getArgs(); // (Object[]) argv[2];

		final CompensableTransactionImpl compensable = //
				(CompensableTransactionImpl) compensableManager.getCompensableTransaction(thread);
		if (compensable == null) {
			return this.dispatch.get(method).invoke(args);
		}

		final TransactionContext transactionContext = compensable.getTransactionContext();
		if (transactionContext.isCompensable() == false) {
			return this.dispatch.get(method).invoke(args);
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
			// compensableManager.attachThread(compensable);
			this.attachThreadIfNecessary(thread);
			return this.dispatch.get(method).invoke(args);
		} catch (Throwable error) {
			Throwable cause = error.getCause();

			CompensableFeignResult cfresult = null;
			if (CompensableFeignResult.class.isInstance(error)) {
				cfresult = (CompensableFeignResult) error;
			} else if (CompensableFeignResult.class.isInstance(cause)) {
				cfresult = (CompensableFeignResult) cause;
			}

			if (cfresult == null) {
				throw error;
			} // end-if (cfresult == null)

			// response.setTransactionContext(cfresult.getTransactionContext());
			response.setParticipantDelistFlag(cfresult.isParticipantValidFlag());

			Object targetResult = cfresult.getResult();
			if (cfresult.isError() == false) {
				return targetResult;
			} else if (RuntimeException.class.isInstance(targetResult)) {
				throw (RuntimeException) targetResult;
			} else {
				throw new RuntimeException((Exception) targetResult);
			}
		} finally {
			try {
				Object interceptedValue = response.getHeader(TransactionInterceptor.class.getName());
				if (Boolean.valueOf(String.valueOf(interceptedValue)) == false) {
					response.setParticipantEnlistFlag(request.isParticipantEnlistFlag());

					RemoteCoordinator coordinator = request.getTargetTransactionCoordinator();
					// TODO should be replaced by CompensableFeignResult.getRemoteParticipant()
					response.setSourceTransactionCoordinator(coordinator);

					transactionInterceptor.afterReceiveResponse(response);
				} // end-if (response.isIntercepted() == false)
			} finally {
				// compensableManager.detachThread();
				this.detachThreadIfNecessary(thread);
			}
		}

	}

	private void attachThreadIfNecessary(Thread thread) {
		final SpringCloudBeanRegistry beanRegistry = SpringCloudBeanRegistry.getInstance();
		CompensableBeanFactory beanFactory = beanRegistry.getBeanFactory();
		CompensableManager compensableManager = beanFactory.getCompensableManager();

		CompensableTransaction compensable = compensableManager.getCompensableTransaction(thread);
		if (Thread.currentThread().equals(thread) == false) {
			compensableManager.attachThread(compensable);
		} // end-if (Thread.currentThread().equals(thread) == false)
	}

	private void detachThreadIfNecessary(Thread thread) {
		final SpringCloudBeanRegistry beanRegistry = SpringCloudBeanRegistry.getInstance();
		CompensableBeanFactory beanFactory = beanRegistry.getBeanFactory();
		CompensableManager compensableManager = beanFactory.getCompensableManager();

		if (Thread.currentThread().equals(thread) == false) {
			compensableManager.detachThread();
		} // end-if (Thread.currentThread().equals(thread) == false)
	}

	public boolean isStatefully() {
		return statefully;
	}

	public void setStatefully(boolean statefully) {
		this.statefully = statefully;
	}

	public Map<Method, MethodHandler> getDispatch() {
		return dispatch;
	}

}
