/**
 * Copyright 2014-2016 yangming.liu<bytefox@126.com>.
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
package org.bytesoft.bytetcc.supports.dubbo;

import java.util.List;
import java.util.Random;

import org.bytesoft.bytejta.supports.invoke.InvocationContext;
import org.bytesoft.bytejta.supports.invoke.InvocationContextRegistry;

import com.alibaba.druid.util.StringUtils;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.cluster.LoadBalance;

public final class CompensableDubboLoadBalance implements LoadBalance {
	static final Random random = new Random();

	public <T> Invoker<T> select(List<Invoker<T>> invokers, URL url, Invocation invocation) throws RpcException {
		InvocationContextRegistry registry = InvocationContextRegistry.getInstance();
		InvocationContext invocationContext = registry.getInvocationContext();
		if (invocationContext == null) {
			return this.selectRandomInvoker(invokers, url, invocation);
		} else {
			return this.selectSpecificInvoker(invokers, url, invocation, invocationContext);
		}
	}

	public <T> Invoker<T> selectRandomInvoker(List<Invoker<T>> invokers, URL url, Invocation invocation)
			throws RpcException {
		int lengthOfInvokerList = invokers == null ? 0 : invokers.size();
		if (lengthOfInvokerList == 0) {
			throw new RpcException("No invoker is found!");
		} else {
			return invokers.get(random.nextInt(lengthOfInvokerList));
		}
	}

	public <T> Invoker<T> selectSpecificInvoker(List<Invoker<T>> invokers, URL url, Invocation invocation,
			InvocationContext context) throws RpcException {
		String serverHost = context.getServerHost();
		int serverPort = context.getServerPort();
		for (int i = 0; invokers != null && i < invokers.size(); i++) {
			Invoker<T> invoker = invokers.get(i);
			URL targetUrl = invoker.getUrl();
			String targetAddr = targetUrl.getIp();
			int targetPort = targetUrl.getPort();
			if (StringUtils.equals(targetAddr, serverHost) && targetPort == serverPort) {
				return invoker;
			}
		}
		throw new RpcException(String.format("Invoker(%s:%s) is not found!", serverHost, serverPort));
	}
}
