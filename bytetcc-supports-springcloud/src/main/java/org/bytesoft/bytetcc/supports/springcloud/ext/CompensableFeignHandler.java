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

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang.StringUtils;
import org.bytesoft.bytejta.supports.rpc.TransactionRequestImpl;
import org.bytesoft.bytejta.supports.wire.RemoteCoordinator;
import org.bytesoft.bytetcc.CompensableTransactionImpl;
import org.bytesoft.bytetcc.supports.springcloud.CompensableRibbonInterceptor;
import org.bytesoft.bytetcc.supports.springcloud.SpringCloudBeanRegistry;
import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.CompensableManager;
import org.bytesoft.compensable.TransactionContext;
import org.bytesoft.transaction.archive.XAResourceArchive;
import org.bytesoft.transaction.supports.resource.XAResourceDescriptor;
import org.bytesoft.transaction.supports.rpc.TransactionInterceptor;

import com.netflix.loadbalancer.Server;

import feign.InvocationHandlerFactory.MethodHandler;
import feign.Target;

public class CompensableFeignHandler implements InvocationHandler {

	private Target<?> target;
	private Map<Method, MethodHandler> handlers;

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
				return this.handlers.get(method).invoke(args);
			}

			final List<XAResourceArchive> participantList = compensable.getParticipantArchiveList();

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

					transactionInterceptor.beforeSendRequest(request);
				}
			});

			// TransactionResponseImpl response = new TransactionResponseImpl();
			// try {
			MethodHandler methodHandler = this.handlers.get(method);
			Object result = methodHandler.invoke(args);
			System.out.printf("result= %s%n", result);
			return result;
			// } finally { transactionInterceptor.afterReceiveResponse(response); }
		}
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

}
