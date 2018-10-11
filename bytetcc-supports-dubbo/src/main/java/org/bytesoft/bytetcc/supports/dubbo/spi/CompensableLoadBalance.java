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
package org.bytesoft.bytetcc.supports.dubbo.spi;

import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.bytesoft.bytejta.supports.dubbo.InvocationContext;
import org.bytesoft.bytejta.supports.dubbo.InvocationContextRegistry;
import org.bytesoft.bytejta.supports.internal.RemoteCoordinatorRegistry;
import org.bytesoft.bytetcc.CompensableTransactionImpl;
import org.bytesoft.bytetcc.supports.dubbo.CompensableBeanRegistry;
import org.bytesoft.bytetcc.supports.dubbo.ext.ILoadBalancer;
import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.CompensableManager;
import org.bytesoft.transaction.archive.XAResourceArchive;
import org.bytesoft.transaction.remote.RemoteAddr;
import org.bytesoft.transaction.remote.RemoteNode;
import org.bytesoft.transaction.supports.resource.XAResourceDescriptor;
import org.springframework.core.env.Environment;

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.cluster.LoadBalance;

public final class CompensableLoadBalance implements LoadBalance {
	static final String CONSTANT_LOADBALANCE_KEY = "org.bytesoft.bytetcc.loadbalance";

	private ILoadBalancer loadBalancer;

	private void fireInitializeIfNecessary() {
		if (this.loadBalancer == null) {
			this.initializeIfNecessary();
		}
	}

	private synchronized void initializeIfNecessary() {
		if (this.loadBalancer == null) {
			Environment environment = CompensableBeanRegistry.getInstance().getEnvironment();
			String loadBalanceKey = environment.getProperty(CONSTANT_LOADBALANCE_KEY, "default");
			ExtensionLoader<ILoadBalancer> extensionLoader = ExtensionLoader.getExtensionLoader(ILoadBalancer.class);
			this.loadBalancer = extensionLoader.getExtension(loadBalanceKey);
		}
	}

	public <T> Invoker<T> select(List<Invoker<T>> invokers, URL url, Invocation invocation) throws RpcException {
		InvocationContextRegistry registry = InvocationContextRegistry.getInstance();
		InvocationContext invocationContext = registry.getInvocationContext();
		if (invocationContext == null) {
			return this.selectInvokerForSVC(invokers, url, invocation);
		} else {
			return this.selectInvokerForTCC(invokers, url, invocation);
		}
	}

	public <T> Invoker<T> selectInvokerForSVC(List<Invoker<T>> invokers, URL url, Invocation invocation) throws RpcException {
		if (invokers == null || invokers.isEmpty()) {
			throw new RpcException("No invoker is found!");
		}

		CompensableBeanFactory beanFactory = CompensableBeanRegistry.getInstance().getBeanFactory();
		RemoteCoordinatorRegistry participantRegistry = RemoteCoordinatorRegistry.getInstance();
		CompensableManager compensableManager = beanFactory.getCompensableManager();
		CompensableTransactionImpl compensable = //
				(CompensableTransactionImpl) compensableManager.getCompensableTransactionQuietly();
		List<XAResourceArchive> participantList = compensable == null ? null : compensable.getParticipantArchiveList();

		for (int i = 0; invokers != null && participantList != null && participantList.isEmpty() == false
				&& i < invokers.size(); i++) {
			Invoker<T> invoker = invokers.get(i);
			URL invokerUrl = invoker.getUrl();
			RemoteAddr invokerAddr = new RemoteAddr();
			invokerAddr.setServerHost(invokerUrl.getHost());
			invokerAddr.setServerPort(invokerUrl.getPort());

			RemoteNode remoteNode = participantRegistry.getRemoteNode(invokerAddr);
			if (remoteNode == null) {
				continue;
			}

			XAResourceDescriptor participant = compensable.getRemoteCoordinator(remoteNode.getServiceKey());
			if (participant == null) {
				continue;
			}

			return invoker;
		}

		this.fireInitializeIfNecessary();

		if (this.loadBalancer == null) {
			throw new RpcException("No org.bytesoft.bytetcc.supports.dubbo.ext.ILoadBalancer is found!");
		} else {
			return this.loadBalancer.select(invokers, url, invocation);
		}

	}

	public <T> Invoker<T> selectInvokerForTCC(List<Invoker<T>> invokers, URL url, Invocation invocation) throws RpcException {
		InvocationContextRegistry invocationContextRegistry = InvocationContextRegistry.getInstance();
		RemoteCoordinatorRegistry remoteCoordinatorRegistry = RemoteCoordinatorRegistry.getInstance();
		InvocationContext invocationContext = invocationContextRegistry.getInvocationContext();

		String serverHost = invocationContext.getServerHost();
		int serverPort = invocationContext.getServerPort();
		RemoteAddr expectRemoteAddr = new RemoteAddr();
		expectRemoteAddr.setServerHost(serverHost);
		expectRemoteAddr.setServerPort(serverPort);
		RemoteNode expectRemoteNode = remoteCoordinatorRegistry.getRemoteNode(expectRemoteAddr);
		if (expectRemoteNode == null) {
			for (int i = 0; invokers != null && i < invokers.size(); i++) {
				Invoker<T> invoker = invokers.get(i);
				URL targetUrl = invoker.getUrl();
				String targetAddr = targetUrl.getIp();
				int targetPort = targetUrl.getPort();

				RemoteAddr actualRemoteAddr = new RemoteAddr();
				actualRemoteAddr.setServerHost(targetAddr);
				actualRemoteAddr.setServerPort(targetPort);

				if (expectRemoteAddr.equals(actualRemoteAddr)) {
					return invoker;
				} // end-if (expectRemoteAddr.equals(actualRemoteAddr))

			} // end-for (int i = 0; invokers != null && i < invokers.size(); i++)
		} else {
			String expectApplication = expectRemoteNode.getServiceKey();
			for (int i = 0; invokers != null && i < invokers.size(); i++) {
				Invoker<T> invoker = invokers.get(i);
				URL targetUrl = invoker.getUrl();
				String targetAddr = targetUrl.getIp();
				int targetPort = targetUrl.getPort();

				RemoteAddr actualRemoteAddr = new RemoteAddr();
				actualRemoteAddr.setServerHost(targetAddr);
				actualRemoteAddr.setServerPort(targetPort);
				RemoteNode actualRemoteNode = remoteCoordinatorRegistry.getRemoteNode(actualRemoteAddr);
				String actualApplication = actualRemoteNode == null ? null : actualRemoteNode.getServiceKey();

				if (StringUtils.equals(expectApplication, actualApplication)) {
					return invoker;
				} else if (expectRemoteAddr.equals(actualRemoteAddr)) {
					return invoker;
				}
			} // end-for (int i = 0; invokers != null && i < invokers.size(); i++)
		}

		throw new RpcException(String.format("Invoker(%s:%s) is not found!", serverHost, serverPort));
	}

}
