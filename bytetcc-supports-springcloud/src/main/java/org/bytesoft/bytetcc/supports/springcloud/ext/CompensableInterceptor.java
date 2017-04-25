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
import java.io.Serializable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang.StringUtils;
import org.bytesoft.bytejta.supports.rpc.TransactionRequestImpl;
import org.bytesoft.bytejta.supports.rpc.TransactionResponseImpl;
import org.bytesoft.bytejta.supports.wire.RemoteCoordinator;
import org.bytesoft.bytetcc.CompensableTransactionImpl;
import org.bytesoft.bytetcc.supports.springcloud.CompensableRibbonInterceptor;
import org.bytesoft.bytetcc.supports.springcloud.SpringCloudBeanRegistry;
import org.bytesoft.bytetcc.supports.springcloud.controller.CompensableCoordinatorController;
import org.bytesoft.common.utils.ByteUtils;
import org.bytesoft.common.utils.CommonUtils;
import org.bytesoft.compensable.Compensable;
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
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import com.netflix.loadbalancer.Server;

import feign.InvocationHandlerFactory;
import feign.RequestTemplate;
import feign.Target;

public class CompensableInterceptor implements HandlerInterceptor, ClientHttpRequestInterceptor, InvocationHandlerFactory,
		InvocationHandler, feign.RequestInterceptor, CompensableEndpointAware, ApplicationContextAware {
	static final String HEADER_TRANCACTION_KEY = "org.bytesoft.bytetcc.transaction";
	static final String HEADER_PROPAGATION_KEY = "org.bytesoft.bytetcc.propagation";

	private String identifier;
	private ApplicationContext applicationContext;
	private Target<?> target;
	private Map<Method, MethodHandler> handlers;

	@SuppressWarnings("rawtypes")
	public InvocationHandler create(Target target, Map<Method, MethodHandler> dispatch) {
		CompensableInterceptor handler = new CompensableInterceptor();
		handler.setTarget(target);
		handler.setHandlers(dispatch);
		handler.setEndpoint(this.identifier);
		handler.setApplicationContext(this.applicationContext);
		return handler;
	}

	public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
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
				throw new IllegalStateException(); // TODO
			}

			// Class<?> clazz = method.getDeclaringClass();
			// FeignClient client = clazz.getAnnotation(FeignClient.class);
			// String serviceId = client.value();

			final List<XAResourceArchive> participantList = compensable == null ? null
					: compensable.getParticipantArchiveList();

			final RemoteCoordinatorHolder holder = new RemoteCoordinatorHolder();

			beanRegistry.setRibbonInterceptor(new CompensableRibbonInterceptor() {
				public Server beforeCompletion(List<Server> servers) {
					for (int i = 0; servers != null && participantList != null && i < servers.size(); i++) {
						Server server = servers.get(i);

						// System.out.printf(
						// "list: id= %s, host= %s, port= %s, host-port= %s, zone= %s, app= %s, inst= %s, group= %s, sid= %s%n",
						// server.getId(), server.getHost(), server.getPort(), server.getHostPort(), server.getZone(),
						// server.getMetaInfo().getAppName(), server.getMetaInfo().getInstanceId(),
						// server.getMetaInfo().getServerGroup(), server.getMetaInfo().getServiceIdForDiscovery());

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
					// System.out.printf(
					// "id= %s, host= %s, port= %s, host-port= %s, zone= %s, app= %s, inst= %s, group= %s, sid= %s%n",
					// server.getId(), server.getHost(), server.getPort(), server.getHostPort(), server.getZone(),
					// server.getMetaInfo().getAppName(), server.getMetaInfo().getInstanceId(),
					// server.getMetaInfo().getServerGroup(), server.getMetaInfo().getServiceIdForDiscovery());
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
				return methodHandler.invoke(args);
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

	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
		HandlerMethod hm = (HandlerMethod) handler;
		Class<?> clazz = hm.getBeanType();
		if (CompensableCoordinatorController.class.equals(clazz)) {
			return true;
		}

		String transactionStr = request.getHeader(HEADER_TRANCACTION_KEY);
		System.out.printf("preHandle: clazz= %s, handler= %s, tx= %s%n", clazz.getSimpleName(), hm.getBean(), transactionStr);
		if (StringUtils.isBlank(transactionStr)) {
			return true;
		}

		String propagationStr = request.getHeader(HEADER_PROPAGATION_KEY);

		String transactionText = StringUtils.trimToNull(transactionStr);
		String propagationText = StringUtils.trimToNull(propagationStr);

		Compensable annotation = clazz.getAnnotation(Compensable.class);
		if (annotation == null) {
			return true;
		}

		SpringCloudBeanRegistry beanRegistry = SpringCloudBeanRegistry.getInstance();
		CompensableBeanFactory beanFactory = beanRegistry.getBeanFactory();
		TransactionInterceptor transactionInterceptor = beanFactory.getTransactionInterceptor();

		byte[] byteArray = transactionText == null ? new byte[0] : ByteUtils.stringToByteArray(transactionText);

		TransactionContext transactionContext = null;
		if (byteArray != null && byteArray.length > 0) {
			transactionContext = (TransactionContext) CommonUtils.deserializeObject(byteArray);
		}

		TransactionRequestImpl req = new TransactionRequestImpl();
		req.setTransactionContext(transactionContext);
		req.setTargetTransactionCoordinator(beanRegistry.getConsumeCoordinator(propagationText));

		transactionInterceptor.afterReceiveRequest(req);

		return true;
	}

	public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView modelAndView)
			throws Exception {
		HandlerMethod hm = (HandlerMethod) handler;
		Class<?> clazz = hm.getBeanType();
		if (CompensableCoordinatorController.class.equals(clazz)) {
			return;
		}

		String transactionStr = request.getHeader(HEADER_TRANCACTION_KEY);
		if (StringUtils.isBlank(transactionStr)) {
			return;
		}

		Compensable annotation = clazz.getAnnotation(Compensable.class);
		if (annotation == null) {
			return;
		}

		SpringCloudBeanRegistry beanRegistry = SpringCloudBeanRegistry.getInstance();
		CompensableBeanFactory beanFactory = beanRegistry.getBeanFactory();
		CompensableManager compensableManager = beanFactory.getCompensableManager();
		TransactionInterceptor transactionInterceptor = beanFactory.getTransactionInterceptor();

		CompensableTransaction compensable = compensableManager.getCompensableTransactionQuietly();
		TransactionContext transactionContext = compensable == null ? null : compensable.getTransactionContext();

		TransactionResponseImpl resp = new TransactionResponseImpl();
		resp.setTransactionContext(transactionContext);
		resp.setSourceTransactionCoordinator(beanRegistry.getConsumeCoordinator(null));

		transactionInterceptor.beforeSendResponse(resp);
	}

	public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex)
			throws Exception {
	}

	public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
			throws IOException {

		URI uri = request.getURI();
		String path = uri.getPath();
		if (path.startsWith("/org/bytesoft/bytetcc")) {
			return execution.execute(request, body);
		}

		SpringCloudBeanRegistry beanRegistry = SpringCloudBeanRegistry.getInstance();
		CompensableBeanFactory beanFactory = beanRegistry.getBeanFactory();
		TransactionInterceptor transactionInterceptor = beanFactory.getTransactionInterceptor();
		transactionInterceptor.beforeSendRequest(null); // TODO
		try {
			return execution.execute(request, body);
		} finally {
			transactionInterceptor.afterReceiveResponse(null); // TODO
		}

	}

	static class WrappedRequest implements java.io.Serializable {
		private static final long serialVersionUID = 1L;
		private Object[] args;
		private TransactionContext transactionContext;
		private String identifier;

		public Object[] getArgs() {
			return args;
		}

		public void setArgs(Object[] args) {
			this.args = args;
		}

		public TransactionContext getTransactionContext() {
			return transactionContext;
		}

		public void setTransactionContext(TransactionContext transactionContext) {
			this.transactionContext = transactionContext;
		}

		public String getIdentifier() {
			return identifier;
		}

		public void setIdentifier(String identifier) {
			this.identifier = identifier;
		}
	}

	static class WrappedResponse implements java.io.Serializable {
		private static final long serialVersionUID = 1L;
		private TransactionContext transactionContext;
		private String identifier;
		private Serializable value;
		private boolean failure;

		public TransactionContext getTransactionContext() {
			return transactionContext;
		}

		public void setTransactionContext(TransactionContext transactionContext) {
			this.transactionContext = transactionContext;
		}

		public String getIdentifier() {
			return identifier;
		}

		public void setIdentifier(String identifier) {
			this.identifier = identifier;
		}

		public Serializable getValue() {
			return value;
		}

		public void setValue(Serializable value) {
			this.value = value;
		}

		public boolean isFailure() {
			return failure;
		}

		public void setFailure(boolean failure) {
			this.failure = failure;
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