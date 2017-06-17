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
import java.util.List;
import java.util.Map;

import org.bytesoft.bytejta.supports.rpc.TransactionRequestImpl;
import org.bytesoft.bytejta.supports.wire.RemoteCoordinator;
import org.bytesoft.bytetcc.CompensableTransactionImpl;
import org.bytesoft.bytetcc.supports.springcloud.SpringCloudBeanRegistry;
import org.bytesoft.bytetcc.supports.springcloud.ribbon.CompensableRibbonInterceptor;
import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.CompensableManager;
import org.bytesoft.compensable.TransactionContext;
import org.bytesoft.transaction.archive.XAResourceArchive;
import org.bytesoft.transaction.supports.rpc.TransactionInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.netflix.loadbalancer.Server;
import com.netflix.loadbalancer.Server.MetaInfo;

import feign.InvocationHandlerFactory.MethodHandler;
import feign.Target;

public class CompensableFeignHandler implements InvocationHandler {
	static final Logger logger = LoggerFactory.getLogger(CompensableFeignHandler.class);

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

			final Map<String, XAResourceArchive> participants = compensable.getParticipantArchiveMap();
			beanRegistry.setRibbonInterceptor(new CompensableRibbonInterceptor() {
				public Server beforeCompletion(List<Server> servers) {
					for (int i = 0; servers != null && i < servers.size(); i++) {
						Server server = servers.get(i);
						MetaInfo metaInfo = server.getMetaInfo();
						String instanceId = metaInfo.getInstanceId();
						if (participants.containsKey(instanceId)) {
							return server;
						} // end-if (participants.containsKey(instanceId))
					}

					logger.warn("There is no suitable server: expect= {}, actual= {}!", participants.keySet(), servers);
					return null;
				}

				public void afterCompletion(Server server) {
					beanRegistry.removeRibbonInterceptor();

					if (server == null) {
						logger.warn(
								"There is no suitable server, the TransactionInterceptor.beforeSendRequest() operation is not executed!");
						return;
					} // end-if (server == null)

					TransactionRequestImpl request = new TransactionRequestImpl();
					request.setTransactionContext(transactionContext);

					MetaInfo metaInfo = server.getMetaInfo();
					String identifier = metaInfo.getInstanceId();
					RemoteCoordinator coordinator = beanRegistry.getConsumeCoordinator(identifier);
					request.setTargetTransactionCoordinator(coordinator);

					transactionInterceptor.beforeSendRequest(request);
				}
			});

			return this.handlers.get(method).invoke(args);
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
