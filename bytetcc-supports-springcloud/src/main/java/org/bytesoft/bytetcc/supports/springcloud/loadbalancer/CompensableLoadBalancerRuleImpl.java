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

import java.util.List;
import java.util.Random;

import org.bytesoft.bytetcc.supports.springcloud.SpringCloudBeanRegistry;

import com.netflix.client.config.IClientConfig;
import com.netflix.loadbalancer.AbstractLoadBalancerRule;
import com.netflix.loadbalancer.ILoadBalancer;
import com.netflix.loadbalancer.IRule;
import com.netflix.loadbalancer.Server;

public class CompensableLoadBalancerRuleImpl extends AbstractLoadBalancerRule implements IRule {
	static Random random = new Random();

	private IClientConfig clientConfig;

	public Server choose(Object key) {
		SpringCloudBeanRegistry registry = SpringCloudBeanRegistry.getInstance();
		CompensableLoadBalancerInterceptor interceptor = registry.getLoadBalancerInterceptor();

		if (interceptor == null) {
			return this.chooseServer(key);
		} // end-if (interceptor == null)

		ILoadBalancer loadBalancer = this.getLoadBalancer();
		List<Server> servers = loadBalancer.getAllServers();

		Server server = null;
		try {
			List<Server> serverList = interceptor.beforeCompletion(servers);

			server = this.chooseServer(key, serverList);
		} finally {
			interceptor.afterCompletion(server);
		}

		return server;
	}

	public Server chooseServer(Object key) {
		ILoadBalancer loadBalancer = this.getLoadBalancer();
		List<Server> reachableServers = loadBalancer.getReachableServers();
		List<Server> allServers = loadBalancer.getAllServers();

		if (reachableServers != null && reachableServers.isEmpty() == false) {
			return reachableServers.get(random.nextInt(reachableServers.size()));
		} else if (allServers != null && allServers.isEmpty() == false) {
			return allServers.get(random.nextInt(allServers.size()));
		} else {
			return null;
		}

	}

	public Server chooseServer(Object key, List<Server> serverList) {
		if (serverList == null || serverList.isEmpty()) {
			return null;
		} else if (serverList.size() == 1) {
			return serverList.get(0);
		} else {
			return serverList.get(random.nextInt(serverList.size()));
		}
	}

	public void initWithNiwsConfig(IClientConfig clientConfig) {
		this.clientConfig = clientConfig;
	}

	public IClientConfig getClientConfig() {
		return clientConfig;
	}

}
