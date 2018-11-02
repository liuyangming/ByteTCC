/**
 * Copyright 2014-2018 yangming.liu<bytefox@126.com>.
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
package org.bytesoft.bytetcc.supports.springboot.serialize;

import java.lang.reflect.Proxy;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bytesoft.bytejta.supports.internal.RemoteCoordinatorRegistry;
import org.bytesoft.bytejta.supports.resource.RemoteResourceDescriptor;
import org.bytesoft.bytetcc.supports.springboot.SpringBootCoordinator;
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
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;

public class XAResourceDeserializerImpl implements XAResourceDeserializer, ApplicationContextAware, EnvironmentAware {
	static final Logger logger = LoggerFactory.getLogger(XAResourceDeserializerImpl.class);
	static Pattern pattern = Pattern.compile("^[^:]+\\s*:\\s*[^:]+\\s*:\\s*\\d+$");

	private XAResourceDeserializer resourceDeserializer;
	private Environment environment;
	private ApplicationContext applicationContext;

	public XAResourceDescriptor deserialize(String identifier) {
		XAResourceDescriptor resourceDescriptor = this.resourceDeserializer.deserialize(identifier);
		if (resourceDescriptor != null) {
			return resourceDescriptor;
		}

		Matcher matcher = pattern.matcher(identifier);
		if (matcher.find() == false) {
			logger.error("can not find a matching xa-resource(identifier= {})!", identifier);
			return null;
		}

		RemoteCoordinatorRegistry registry = RemoteCoordinatorRegistry.getInstance();
		String application = CommonUtils.getApplication(identifier);
		if (registry.containsParticipant(application) == false) {
			SpringBootCoordinator springCloudCoordinator = new SpringBootCoordinator();
			springCloudCoordinator.setIdentifier(identifier);
			springCloudCoordinator.setEnvironment(this.environment);

			RemoteCoordinator participant = (RemoteCoordinator) Proxy.newProxyInstance(
					SpringBootCoordinator.class.getClassLoader(), new Class[] { RemoteCoordinator.class },
					springCloudCoordinator);

			RemoteAddr remoteAddr = CommonUtils.getRemoteAddr(identifier);
			RemoteNode remoteNode = CommonUtils.getRemoteNode(identifier);

			registry.putParticipant(application, participant);
			registry.putPhysicalInstance(remoteAddr, participant);
			registry.putRemoteNode(remoteAddr, remoteNode);
		}

		RemoteResourceDescriptor descriptor = new RemoteResourceDescriptor();
		descriptor.setIdentifier(identifier);
		descriptor.setDelegate(registry.getParticipant(application));

		return descriptor;
	}

	public XAResourceDeserializer getResourceDeserializer() {
		return resourceDeserializer;
	}

	public void setResourceDeserializer(XAResourceDeserializer resourceDeserializer) {
		this.resourceDeserializer = resourceDeserializer;
	}

	public void setEnvironment(Environment environment) {
		this.environment = environment;
	}

	public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
		this.applicationContext = applicationContext;
	}

	public ApplicationContext getApplicationContext() {
		return applicationContext;
	}

}
