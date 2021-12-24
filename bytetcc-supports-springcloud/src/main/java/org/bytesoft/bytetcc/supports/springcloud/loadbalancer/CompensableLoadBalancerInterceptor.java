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
package org.bytesoft.bytetcc.supports.springcloud.loadbalancer;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.bytesoft.bytetcc.CompensableTransactionImpl;
import org.bytesoft.bytetcc.supports.springcloud.SpringCloudBeanRegistry;
import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.CompensableManager;
import org.bytesoft.transaction.supports.resource.XAResourceDescriptor;
import org.springframework.cloud.client.ServiceInstance;

public abstract class CompensableLoadBalancerInterceptor {
	private final boolean stateful;

	public CompensableLoadBalancerInterceptor() {
		this(false);
	}

	public CompensableLoadBalancerInterceptor(boolean stateful) {
		this.stateful = stateful;
	}

	public List<ServiceInstance> beforeCompletion(List<ServiceInstance> servers) {
		SpringCloudBeanRegistry beanRegistry = SpringCloudBeanRegistry.getInstance();
		CompensableBeanFactory beanFactory = beanRegistry.getBeanFactory();
		CompensableManager compensableManager = beanFactory.getCompensableManager();

		final List<ServiceInstance> readyServerList = new ArrayList<ServiceInstance>();
		final List<ServiceInstance> unReadyServerList = new ArrayList<ServiceInstance>();

		if (this.stateful) {
			boolean applicationEnlisted = false;
			for (int i = 0; servers != null && i < servers.size(); i++) {
				ServiceInstance server = servers.get(i);

				String instanceId = this.getInstanceId(server);

				final CompensableTransactionImpl compensable = //
						(CompensableTransactionImpl) compensableManager.getCompensableTransactionQuietly();
				XAResourceDescriptor descriptor = compensable.getRemoteCoordinator(server.getServiceId());
				if (descriptor == null) {
//					if (server.isReadyToServe()) {
					readyServerList.add(server);
//					} else {
//						unReadyServerList.add(server);
//					}
				} else {
					String identifier = descriptor.getIdentifier();
					applicationEnlisted = true;

					if (StringUtils.equals(identifier, instanceId)) {
						List<ServiceInstance> serverList = new ArrayList<ServiceInstance>();
						serverList.add(server);
						return serverList;
					} // end-if (StringUtils.equals(identifier, instanceId))
				}
			}

			if (applicationEnlisted) {
				return new ArrayList<ServiceInstance>();
			} // end-if (systemMatched)

		} else {
			for (int i = 0; servers != null && i < servers.size(); i++) {
				ServiceInstance server = servers.get(i);
//				if (server.isReadyToServe()) {
				readyServerList.add(server);
//				} else {
//					unReadyServerList.add(server);
//				}
			}
		}

		return readyServerList.isEmpty() ? unReadyServerList : readyServerList;
	}

	public abstract void afterCompletion(ServiceInstance server);

	public String getInstanceId(ServiceInstance server) {
		String addr = server.getHost();
		String application = server.getServiceId();
		int port = server.getPort();

		return String.format("%s:%s:%s", addr, application, port);
	}

}
