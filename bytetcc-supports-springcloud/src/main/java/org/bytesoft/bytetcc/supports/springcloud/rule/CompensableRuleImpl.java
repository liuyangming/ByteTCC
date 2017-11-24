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
package org.bytesoft.bytetcc.supports.springcloud.rule;

import java.util.List;
import java.util.Random;

import com.netflix.client.config.IClientConfig;
import com.netflix.loadbalancer.ILoadBalancer;
import com.netflix.loadbalancer.Server;

public class CompensableRuleImpl implements CompensableRule {
	static final Random RANDOM = new Random();
	private IClientConfig clientConfig;
	private ILoadBalancer loadBalancer;

	public void initWithNiwsConfig(IClientConfig clientConfig) {
		this.clientConfig = clientConfig;
	}

	public Server chooseServer(Object key, List<Server> serverList) {
		if (serverList == null || serverList.isEmpty()) {
			return null;
		} else if (serverList.size() == 1) {
			return serverList.get(0);
		} else {
			return serverList.get(RANDOM.nextInt(serverList.size()));
		}
	}

	public Server chooseServer(Object key) {
		List<Server> reachableServers = this.loadBalancer.getReachableServers();
		List<Server> allServers = this.loadBalancer.getAllServers();

		if (reachableServers != null && reachableServers.isEmpty() == false) {
			return reachableServers.get(RANDOM.nextInt(reachableServers.size()));
		} else if (allServers != null && allServers.isEmpty() == false) {
			return allServers.get(RANDOM.nextInt(allServers.size()));
		} else {
			return null;
		}
	}

	public IClientConfig getClientConfig() {
		return clientConfig;
	}

	public ILoadBalancer getLoadBalancer() {
		return this.loadBalancer;
	}

	public void setLoadBalancer(ILoadBalancer loadBalancer) {
		this.loadBalancer = loadBalancer;
	}

}
