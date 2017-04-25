package org.bytesoft.bytetcc.supports.springcloud;

import java.util.List;

import com.netflix.loadbalancer.Server;

public interface CompensableRibbonInterceptor {

	public Server beforeCompletion(List<Server> servers);

	public void afterCompletion(Server server);

}
