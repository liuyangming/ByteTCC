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
package org.bytesoft.bytetcc.supports.dubbo.serialize;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.bytesoft.bytejta.supports.internal.RemoteCoordinatorRegistry;
import org.bytesoft.bytejta.supports.resource.RemoteResourceDescriptor;
import org.bytesoft.bytetcc.supports.dubbo.CompensableBeanRegistry;
import org.bytesoft.common.utils.CommonUtils;
import org.bytesoft.transaction.remote.RemoteAddr;
import org.bytesoft.transaction.remote.RemoteCoordinator;
import org.bytesoft.transaction.remote.RemoteNode;
import org.bytesoft.transaction.supports.resource.XAResourceDescriptor;
import org.bytesoft.transaction.supports.serialize.XAResourceDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.config.ApplicationConfig;
import com.alibaba.dubbo.config.ProtocolConfig;
import com.alibaba.dubbo.config.ReferenceConfig;
import com.alibaba.dubbo.config.RegistryConfig;
import com.alibaba.dubbo.rpc.RpcException;

public class XAResourceDeserializerImpl implements XAResourceDeserializer, ApplicationContextAware {
	static final Logger logger = LoggerFactory.getLogger(XAResourceDeserializerImpl.class);
	static Pattern pattern = Pattern.compile("^[^:]+\\s*:\\s*[^:]+\\s*:\\s*\\d+$");

	private ApplicationContext applicationContext;
	private XAResourceDeserializer resourceDeserializer;
	private transient boolean statefully;

	public XAResourceDescriptor deserialize(String identifier) {
		XAResourceDescriptor resourceDescriptor = this.resourceDeserializer.deserialize(identifier);
		if (resourceDescriptor != null) {
			return resourceDescriptor;
		}

		Matcher matcher = pattern.matcher(identifier);
		if (matcher.find()) {
			RemoteCoordinatorRegistry registry = RemoteCoordinatorRegistry.getInstance();
			String application = CommonUtils.getApplication(identifier);
			RemoteCoordinator participant = registry.getParticipant(application);
			if (participant == null) {
				RemoteAddr remoteAddr = CommonUtils.getRemoteAddr(identifier);
				RemoteNode remoteNode = CommonUtils.getRemoteNode(identifier);

				this.initializeRemoteParticipantIfNecessary(application);
				registry.putRemoteNode(remoteAddr, remoteNode);
			}

			RemoteResourceDescriptor descriptor = new RemoteResourceDescriptor();
			descriptor.setIdentifier(identifier);
			descriptor.setDelegate(registry.getParticipant(application));

			return descriptor;
		} else {
			logger.error("can not find a matching xa-resource(identifier= {})!", identifier);
			return null;
		}

	}

	private void initializeRemoteParticipantIfNecessary(final String system) throws RpcException {
		RemoteCoordinatorRegistry participantRegistry = RemoteCoordinatorRegistry.getInstance();
		final String application = StringUtils.trimToEmpty(system).intern();
		RemoteCoordinator remoteParticipant = participantRegistry.getParticipant(application);
		if (remoteParticipant == null) {
			synchronized (application) {
				RemoteCoordinator participant = participantRegistry.getParticipant(application);
				if (participant == null) {
					this.processInitRemoteParticipantIfNecessary(application);
				}
			} // end-synchronized (target)
		} // end-if (remoteParticipant == null)
	}

	private void processInitRemoteParticipantIfNecessary(String application) {
		RemoteCoordinatorRegistry participantRegistry = RemoteCoordinatorRegistry.getInstance();
		CompensableBeanRegistry beanRegistry = CompensableBeanRegistry.getInstance();

		RemoteCoordinator participant = participantRegistry.getParticipant(application);
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
			referenceConfig.setScope(Constants.SCOPE_REMOTE);

			if (this.statefully) {
				referenceConfig.setGroup(String.format("x-%s", application));
				referenceConfig.setLoadbalance("bytetcc");
			} else {
				referenceConfig.setGroup(String.format("z-%s", application));
			}

			referenceConfig.setApplication(applicationConfig);
			if (registryConfig != null) {
				referenceConfig.setRegistry(registryConfig);
			} // end-if (registryConfig != null)

			if (protocolConfig != null) {
				referenceConfig.setProtocol(protocolConfig.getName());
			} // end-if (protocolConfig != null)

			RemoteCoordinator reference = referenceConfig.get();
			if (reference == null) {
				throw new RpcException("Cannot get the application name of the remote application.");
			} // end-if (reference == null)

			participantRegistry.putParticipant(application, reference);
		}
	}

	public XAResourceDeserializer getResourceDeserializer() {
		return resourceDeserializer;
	}

	public void setResourceDeserializer(XAResourceDeserializer resourceDeserializer) {
		this.resourceDeserializer = resourceDeserializer;
	}

	public boolean isStatefully() {
		return statefully;
	}

	public void setStatefully(boolean statefully) {
		this.statefully = statefully;
	}

	public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
		this.applicationContext = applicationContext;
	}

	public ApplicationContext getApplicationContext() {
		return applicationContext;
	}

}
