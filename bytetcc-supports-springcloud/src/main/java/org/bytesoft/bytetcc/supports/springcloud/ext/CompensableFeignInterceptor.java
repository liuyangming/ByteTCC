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
package org.bytesoft.bytetcc.supports.springcloud.ext;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang.StringUtils;
import org.bytesoft.bytejta.supports.rpc.TransactionRequestImpl;
import org.bytesoft.bytejta.supports.rpc.TransactionResponseImpl;
import org.bytesoft.bytejta.supports.wire.RemoteCoordinator;
import org.bytesoft.bytetcc.CompensableTransactionImpl;
import org.bytesoft.bytetcc.supports.springcloud.CompensableRibbonInterceptor;
import org.bytesoft.bytetcc.supports.springcloud.SpringCloudBeanRegistry;
import org.bytesoft.common.utils.ByteUtils;
import org.bytesoft.common.utils.CommonUtils;
import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.CompensableManager;
import org.bytesoft.compensable.CompensableTransaction;
import org.bytesoft.compensable.TransactionContext;
import org.bytesoft.compensable.aware.CompensableEndpointAware;
import org.bytesoft.transaction.archive.XAResourceArchive;
import org.bytesoft.transaction.supports.resource.XAResourceDescriptor;
import org.bytesoft.transaction.supports.rpc.TransactionInterceptor;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import com.netflix.loadbalancer.Server;

import feign.InvocationHandlerFactory;
import feign.RequestTemplate;
import feign.Target;

public class CompensableFeignInterceptor implements InvocationHandlerFactory, InvocationHandler, feign.RequestInterceptor,
		CompensableEndpointAware, ApplicationContextAware {
	static final String HEADER_TRANCACTION_KEY = "org.bytesoft.bytetcc.transaction";
	static final String HEADER_PROPAGATION_KEY = "org.bytesoft.bytetcc.propagation";

	private String identifier;
	private ApplicationContext applicationContext;
	private Target<?> target;
	private Map<Method, MethodHandler> handlers;

	@SuppressWarnings("rawtypes")
	public InvocationHandler create(Target target, Map<Method, MethodHandler> dispatch) {
		CompensableFeignInterceptor handler = new CompensableFeignInterceptor();
		handler.setTarget(target);
		handler.setHandlers(dispatch);
		handler.setEndpoint(this.identifier);
		handler.setApplicationContext(this.applicationContext);
		return handler;
	}

	public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
		throw new IllegalStateException("Not supported yet!"); // TODO
	}

	public Object _invoke(Object proxy, Method method, Object[] args) throws Throwable {
		if (Object.class.equals(method.getDeclaringClass())) {
			return method.invoke(this, args);
		} else {
			final SpringCloudBeanRegistry beanRegistry = SpringCloudBeanRegistry.getInstance();
			CompensableBeanFactory beanFactory = beanRegistry.getBeanFactory();
			CompensableManager compensableManager = beanFactory.getCompensableManager();
			final TransactionInterceptor transactionInterceptor = beanFactory.getTransactionInterceptor();

			CompensableTransactionImpl compensable = //
					(CompensableTransactionImpl) compensableManager.getCompensableTransactionQuietly();
			if (compensable == null) {
				return this.handlers.get(method).invoke(args);
			}

			final TransactionContext transactionContext = compensable.getTransactionContext();
			if (transactionContext.isCompensable() == false) {
				return this.handlers.get(method).invoke(args);
			}

			final List<XAResourceArchive> participantList = compensable.getParticipantArchiveList();

			final RemoteCoordinatorHolder holder = new RemoteCoordinatorHolder();

			beanRegistry.setRibbonInterceptor(new CompensableRibbonInterceptor() {
				public Server beforeCompletion(List<Server> servers) {
					for (int i = 0; servers != null && participantList != null && i < servers.size(); i++) {
						Server server = servers.get(i);
						String instanceId = server.getMetaInfo().getInstanceId();
						for (int j = 0; participantList != null && j < participantList.size(); j++) {
							XAResourceArchive archive = participantList.get(j);
							XAResourceDescriptor descriptor = archive.getDescriptor();
							String identifier = descriptor.getIdentifier();
							if (StringUtils.equals(instanceId, identifier)) {
								return server;
							}
						}
					}
					return null;
				}

				public void afterCompletion(Server server) {
					beanRegistry.removeRibbonInterceptor();

					TransactionRequestImpl request = new TransactionRequestImpl();
					request.setTransactionContext(transactionContext);
					String identifier = server.getMetaInfo().getInstanceId();
					RemoteCoordinator coordinator = beanRegistry.getConsumeCoordinator(identifier);
					request.setTargetTransactionCoordinator(coordinator);

					holder.setCoordinator(coordinator);

					transactionInterceptor.beforeSendRequest(request);
				}
			});

			TransactionResponseImpl response = new TransactionResponseImpl();
			// response.setTransactionContext(transactionContext); // TODO
			response.setSourceTransactionCoordinator(holder.getCoordinator());
			try {
				MethodHandler methodHandler = this.handlers.get(method);
				Object result = methodHandler.invoke(args);
				// System.out.printf("result= %s%n", result);
				return result;
			} finally {
				transactionInterceptor.afterReceiveResponse(response);
			}
		}
	}

	private static class RemoteCoordinatorHolder {
		private RemoteCoordinator coordinator;

		public RemoteCoordinator getCoordinator() {
			return coordinator;
		}

		public void setCoordinator(RemoteCoordinator coordinator) {
			this.coordinator = coordinator;
		}
	}

	public void apply(RequestTemplate template) {
		final SpringCloudBeanRegistry beanRegistry = SpringCloudBeanRegistry.getInstance();
		CompensableBeanFactory beanFactory = beanRegistry.getBeanFactory();
		CompensableManager compensableManager = beanFactory.getCompensableManager();
		CompensableTransaction compensable = compensableManager.getCompensableTransactionQuietly();
		if (compensable == null) {
			return;
		}

		try {
			TransactionContext transactionContext = compensable.getTransactionContext();
			byte[] byteArray;
			byteArray = CommonUtils.serializeObject(transactionContext);

			String transactionText = ByteUtils.byteArrayToString(byteArray);

			Map<String, Collection<String>> headers = template.headers();
			if (headers.containsKey(HEADER_TRANCACTION_KEY) == false) {
				template.header(HEADER_TRANCACTION_KEY, transactionText);
			}

			if (headers.containsKey(HEADER_PROPAGATION_KEY) == false) {
				template.header(HEADER_PROPAGATION_KEY, identifier);
			}

		} catch (IOException ex) {
			ex.printStackTrace(); // TODO
		}
	}

	public void setEndpoint(String identifier) {
		this.identifier = identifier;
	}

	public Target<?> getTarget() {
		return target;
	}

	public void setTarget(Target<?> target) {
		this.target = target;
	}

	public Map<Method, MethodHandler> getHandlers() {
		return handlers;
	}

	public void setHandlers(Map<Method, MethodHandler> handlers) {
		this.handlers = handlers;
	}

	public ApplicationContext getApplicationContext() {
		return applicationContext;
	}

	public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
		this.applicationContext = applicationContext;
	}

}