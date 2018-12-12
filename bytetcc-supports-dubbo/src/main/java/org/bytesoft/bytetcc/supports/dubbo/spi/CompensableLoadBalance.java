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
import org.bytesoft.bytejta.supports.internal.RemoteCoordinatorRegistry;
import org.bytesoft.bytetcc.CompensableCoordinator;
import org.bytesoft.bytetcc.CompensableTransactionImpl;
import org.bytesoft.bytetcc.supports.dubbo.CompensableBeanRegistry;
import org.bytesoft.bytetcc.supports.dubbo.ext.ILoadBalancer;
import org.bytesoft.common.utils.CommonUtils;
import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.CompensableManager;
import org.bytesoft.transaction.archive.XAResourceArchive;
import org.bytesoft.transaction.remote.RemoteAddr;
import org.bytesoft.transaction.remote.RemoteCoordinator;
import org.bytesoft.transaction.remote.RemoteNode;
import org.bytesoft.transaction.supports.resource.XAResourceDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.config.ApplicationConfig;
import com.alibaba.dubbo.config.ProtocolConfig;
import com.alibaba.dubbo.config.ReferenceConfig;
import com.alibaba.dubbo.config.RegistryConfig;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.cluster.LoadBalance;

public final class CompensableLoadBalance implements LoadBalance {
	static final Logger logger = LoggerFactory.getLogger(CompensableLoadBalance.class);
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
		CompensableBeanFactory beanFactory = CompensableBeanRegistry.getInstance().getBeanFactory();
		RemoteCoordinatorRegistry participantRegistry = RemoteCoordinatorRegistry.getInstance();
		CompensableManager compensableManager = beanFactory.getCompensableManager();

		if (invokers == null || invokers.isEmpty()) {
			throw new RpcException("No invoker is found!");
		}

		CompensableTransactionImpl compensable = //
				(CompensableTransactionImpl) compensableManager.getCompensableTransactionQuietly();
		List<XAResourceArchive> participantList = compensable == null ? null : compensable.getParticipantArchiveList();
		if (participantList == null || participantList.isEmpty()) {
			return this.fireChooseInvoker(invokers, url, invocation);
		}

		RpcException rpcException = null;
		for (int i = 0; invokers != null && i < invokers.size(); i++) {
			Invoker<T> invoker = invokers.get(i);
			URL invokerUrl = invoker.getUrl();
			RemoteAddr remoteAddr = new RemoteAddr();
			remoteAddr.setServerHost(invokerUrl.getHost());
			remoteAddr.setServerPort(invokerUrl.getPort());

			if (participantRegistry.containsRemoteNode(remoteAddr) == false) {
				this.initializeRemoteParticipantIdentifier(remoteAddr);
			}
			RemoteNode remoteNode = participantRegistry.getRemoteNode(remoteAddr);
			if (remoteNode == null || StringUtils.isBlank(remoteNode.getServiceKey())) {
				if (rpcException == null) {
					rpcException = new RpcException("Cannot get application name of remote node!");
				} else {
					logger.warn("Cannot get application name of remote node({})!", remoteAddr);
				}
			}

			String application = remoteNode.getServiceKey();
			XAResourceDescriptor participant = compensable.getRemoteCoordinator(application);
			if (participant == null) {
				continue;
			} // end-if (participant==null)

			RemoteAddr expectAddr = CommonUtils.getRemoteAddr(participant.getIdentifier());
			if (remoteAddr.equals(expectAddr)) {
				if (invoker.isAvailable()) {
					return invoker;
				} // end-if (invoker.isAvailable())

				throw new RpcException("The instance has been enlisted is currently unavailable.");
			}

			rpcException = new RpcException("There is already an instance of the same application being enlisted.");
		}

		if (rpcException != null) {
			throw rpcException;
		} else {
			return this.fireChooseInvoker(invokers, url, invocation);
		}
	}

	private void initializeRemoteParticipantIdentifier(RemoteAddr remoteAddr) {
		RemoteCoordinatorRegistry participantRegistry = RemoteCoordinatorRegistry.getInstance();
		if (participantRegistry.containsPhysicalInstance(remoteAddr) == false) {
			this.initializeRemoteParticipantIfNecessary(remoteAddr);
		} // end-if (participantRegistry.containsPhysicalInstance(remoteAddr) == false)

		if (participantRegistry.containsRemoteNode(remoteAddr) == false) {
			this.initializeIdentifierIfNecessary(remoteAddr);
		} // end-if (participantRegistry.containsRemoteNode(remoteAddr) == false)
	}

	private void initializeIdentifierIfNecessary(RemoteAddr remoteAddr) {
		RemoteCoordinatorRegistry participantRegistry = RemoteCoordinatorRegistry.getInstance();
		RemoteCoordinator remoteCoordinator = participantRegistry.getPhysicalInstance(remoteAddr);
		if (remoteCoordinator != null && participantRegistry.containsRemoteNode(remoteAddr) == false) {
			String serverHost = remoteAddr.getServerHost();
			int serverPort = remoteAddr.getServerPort();
			final String target = String.format("%s:null:%s", serverHost, serverPort).intern();
			synchronized (target) {
				String identifier = remoteCoordinator.getIdentifier();
				RemoteNode remoteNode = CommonUtils.getRemoteNode(identifier);
				participantRegistry.putRemoteNode(remoteAddr, remoteNode);
			} // end-synchronized (target)
		} // end-if (participantRegistry.containsRemoteNode(remoteAddr) == false)
	}

	private void initializeRemoteParticipantIfNecessary(RemoteAddr remoteAddr) throws RpcException {
		RemoteCoordinatorRegistry participantRegistry = RemoteCoordinatorRegistry.getInstance();
		RemoteCoordinator physicalInst = participantRegistry.getPhysicalInstance(remoteAddr);
		if (physicalInst == null) {
			String serverHost = remoteAddr.getServerHost();
			int serverPort = remoteAddr.getServerPort();
			final String target = String.format("%s:%s", serverHost, serverPort).intern();
			synchronized (target) {
				RemoteCoordinator participant = participantRegistry.getPhysicalInstance(remoteAddr);
				if (participant == null) {
					this.processInitRemoteParticipantIfNecessary(remoteAddr);
				}
			} // end-synchronized (target)
		} // end-if (physicalInst == null)
	}

	private void processInitRemoteParticipantIfNecessary(RemoteAddr remoteAddr) throws RpcException {
		RemoteCoordinatorRegistry participantRegistry = RemoteCoordinatorRegistry.getInstance();
		CompensableBeanRegistry beanRegistry = CompensableBeanRegistry.getInstance();
		CompensableBeanFactory beanFactory = beanRegistry.getBeanFactory();
		CompensableCoordinator compensableCoordinator = (CompensableCoordinator) beanFactory.getCompensableNativeParticipant();

		RemoteCoordinator participant = participantRegistry.getPhysicalInstance(remoteAddr);
		if (participant == null) {
			ApplicationConfig applicationConfig = beanRegistry.getBean(ApplicationConfig.class);
			RegistryConfig registryConfig = beanRegistry.getBean(RegistryConfig.class);
			ProtocolConfig protocolConfig = beanRegistry.getBean(ProtocolConfig.class);

			ReferenceConfig<RemoteCoordinator> referenceConfig = new ReferenceConfig<RemoteCoordinator>();
			referenceConfig.setInterface(RemoteCoordinator.class);
			referenceConfig.setTimeout(6 * 1000);
			referenceConfig.setCluster("failfast");
			referenceConfig.setFilter("bytetcc");
			referenceConfig.setCheck(false);
			referenceConfig.setRetries(-1);
			referenceConfig.setUrl(String.format("%s:%s", remoteAddr.getServerHost(), remoteAddr.getServerPort()));
			referenceConfig.setScope(Constants.SCOPE_REMOTE);

			if (compensableCoordinator.isStatefully()) {
				referenceConfig.setGroup("x-bytetcc");
			} else {
				referenceConfig.setGroup("z-bytetcc");
			}

			referenceConfig.setApplication(applicationConfig);
			if (registryConfig != null) {
				referenceConfig.setRegistry(registryConfig);
			}
			if (protocolConfig != null) {
				referenceConfig.setProtocol(protocolConfig.getName());
			} // end-if (protocolConfig != null)

			RemoteCoordinator reference = referenceConfig.get();
			if (reference == null) {
				throw new RpcException("Cannot get the application name of the remote application.");
			}

			participantRegistry.putPhysicalInstance(remoteAddr, reference);
		}
	}

	public <T> Invoker<T> fireChooseInvoker(List<Invoker<T>> invokers, URL url, Invocation invocation) throws RpcException {
		this.fireInitializeIfNecessary();
		if (this.loadBalancer == null) {
			throw new RpcException("No org.bytesoft.bytetcc.supports.dubbo.ext.ILoadBalancer is found!");
		} else {
			return this.loadBalancer.select(invokers, url, invocation);
		}
	}

}
