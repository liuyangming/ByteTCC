package org.bytesoft.bytetcc.supports.springcloud.loadbalancer;

import java.util.List;

import com.netflix.loadbalancer.Server;

public interface CompensableLoadBalancerInterceptor {

	public List<Server> beforeCompletion(List<Server> servers);

	public void afterCompletion(Server server);

}
