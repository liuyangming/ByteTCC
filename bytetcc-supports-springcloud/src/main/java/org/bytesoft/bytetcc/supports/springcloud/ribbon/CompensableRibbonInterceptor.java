package org.bytesoft.bytetcc.supports.springcloud.ribbon;

import java.util.List;

import com.netflix.loadbalancer.Server;

public interface CompensableRibbonInterceptor {

	public List<Server> beforeCompletion(List<Server> servers);

	public void afterCompletion(Server server);

}
