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

import java.util.List;
import java.util.Random;

import org.bytesoft.bytetcc.supports.springcloud.CompensableRibbonInterceptor;
import org.bytesoft.bytetcc.supports.springcloud.SpringCloudBeanRegistry;

import com.netflix.client.config.IClientConfig;
import com.netflix.loadbalancer.AbstractLoadBalancerRule;
import com.netflix.loadbalancer.ILoadBalancer;
import com.netflix.loadbalancer.IRule;
import com.netflix.loadbalancer.Server;

public class CompensableRibbonRule extends AbstractLoadBalancerRule implements IRule {
	static Random random = new Random();

	private IRule delegateRule;
	private IClientConfig clientConfig;

	public Server choose(Object key) {
		ILoadBalancer loadBalancer = this.getLoadBalancer();
		List<Server> servers = loadBalancer.getReachableServers();

		SpringCloudBeanRegistry registry = SpringCloudBeanRegistry.getInstance();
		CompensableRibbonInterceptor interceptor = registry.getRibbonInterceptor();
		Server server = null;
		try {
			server = interceptor.beforeCompletion(servers);
			if (server == null) {
				server = this.invokeChoose(key);
			}
		} finally {
			interceptor.afterCompletion(server);
		}
		return server;
	}

	public Server invokeChoose(Object key) {
		if (this.delegateRule != null) {
			return this.delegateRule.choose(key);
		} else {
			ILoadBalancer loadBalancer = this.getLoadBalancer();
			// List<Server> allServers = loadBalancer.getAllServers();
			List<Server> reachableServers = loadBalancer.getReachableServers();
			if (reachableServers == null || reachableServers.isEmpty()) {
				return null;
			} else {
				return reachableServers.get(random.nextInt(reachableServers.size()));
			}
		}
	}

	public IRule getDelegateRule() {
		return delegateRule;
	}

	public void setDelegateRule(IRule delegateRule) {
		this.delegateRule = delegateRule;
	}

	public void initWithNiwsConfig(IClientConfig clientConfig) {
		this.clientConfig = clientConfig;
	}

	public IClientConfig getClientConfig() {
		return clientConfig;
	}

}
