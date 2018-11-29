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
import org.bytesoft.common.utils.CommonUtils;
import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.CompensableManager;
import org.bytesoft.transaction.supports.resource.XAResourceDescriptor;

import com.netflix.appinfo.InstanceInfo;
import com.netflix.loadbalancer.Server;
import com.netflix.loadbalancer.Server.MetaInfo;
import com.netflix.niws.loadbalancer.DiscoveryEnabledServer;

public abstract class CompensableLoadBalancerInterceptor {
	private final boolean stateful;

	public CompensableLoadBalancerInterceptor() {
		this(false);
	}

	public CompensableLoadBalancerInterceptor(boolean stateful) {
		this.stateful = stateful;
	}

	public List<Server> beforeCompletion(List<Server> servers) {
		SpringCloudBeanRegistry beanRegistry = SpringCloudBeanRegistry.getInstance();
		CompensableBeanFactory beanFactory = beanRegistry.getBeanFactory();
		CompensableManager compensableManager = beanFactory.getCompensableManager();

		final List<Server> readyServerList = new ArrayList<Server>();
		final List<Server> unReadyServerList = new ArrayList<Server>();

		if (this.stateful) {
			boolean applicationEnlisted = false;
			for (int i = 0; servers != null && i < servers.size(); i++) {
				Server server = servers.get(i);

				String application = null;
				String instanceId = null;
				if (DiscoveryEnabledServer.class.isInstance(server)) {
					DiscoveryEnabledServer discoveryEnabledServer = (DiscoveryEnabledServer) server;
					InstanceInfo instanceInfo = discoveryEnabledServer.getInstanceInfo();
					String addr = instanceInfo.getIPAddr();
					application = instanceInfo.getAppName();
					int port = instanceInfo.getPort();

					instanceId = String.format("%s:%s:%s", addr, application, port);
				} else {
					MetaInfo metaInfo = server.getMetaInfo();

					String host = server.getHost();
					String addr = host.matches("\\d+(\\.\\d+){3}") ? host : CommonUtils.getInetAddress(host);
					application = metaInfo.getAppName();
					int port = server.getPort();
					instanceId = String.format("%s:%s:%s", addr, application, port);
				}

				final CompensableTransactionImpl compensable = //
						(CompensableTransactionImpl) compensableManager.getCompensableTransactionQuietly();
				XAResourceDescriptor descriptor = compensable.getRemoteCoordinator(application);
				if (descriptor == null) {
					if (server.isReadyToServe()) {
						readyServerList.add(server);
					} else {
						unReadyServerList.add(server);
					}
				} else {
					String identifier = descriptor.getIdentifier();
					applicationEnlisted = true;

					if (StringUtils.equals(identifier, instanceId)) {
						List<Server> serverList = new ArrayList<Server>();
						serverList.add(server);
						return serverList;
					} // end-if (StringUtils.equals(identifier, instanceId))
				}
			}

			if (applicationEnlisted) {
				return new ArrayList<Server>();
			} // end-if (systemMatched)

		} else {
			for (int i = 0; servers != null && i < servers.size(); i++) {
				Server server = servers.get(i);

				if (server.isReadyToServe()) {
					readyServerList.add(server);
				} else {
					unReadyServerList.add(server);
				}
			}
		}

		return readyServerList.isEmpty() ? unReadyServerList : readyServerList;
	}

	public abstract void afterCompletion(Server server);

	public String getInstanceId(Server server) {
		String instanceId = null;

		if (DiscoveryEnabledServer.class.isInstance(server)) {
			DiscoveryEnabledServer discoveryEnabledServer = (DiscoveryEnabledServer) server;
			InstanceInfo instanceInfo = discoveryEnabledServer.getInstanceInfo();
			String addr = instanceInfo.getIPAddr();
			String appName = instanceInfo.getAppName();
			int port = instanceInfo.getPort();

			instanceId = String.format("%s:%s:%s", addr, appName, port);
		} else {
			MetaInfo metaInfo = server.getMetaInfo();

			String host = server.getHost();
			String addr = host.matches("\\d+(\\.\\d+){3}") ? host : CommonUtils.getInetAddress(host);
			String appName = metaInfo.getAppName();
			int port = server.getPort();
			instanceId = String.format("%s:%s:%s", addr, appName, port);
		}

		return instanceId;
	}

}
