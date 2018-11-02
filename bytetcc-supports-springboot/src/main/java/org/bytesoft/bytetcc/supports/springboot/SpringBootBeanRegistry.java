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
package org.bytesoft.bytetcc.supports.springboot;

import java.lang.reflect.Proxy;

import org.apache.commons.lang3.StringUtils;
import org.bytesoft.bytejta.supports.internal.RemoteCoordinatorRegistry;
import org.bytesoft.common.utils.CommonUtils;
import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.aware.CompensableBeanFactoryAware;
import org.bytesoft.transaction.remote.RemoteAddr;
import org.bytesoft.transaction.remote.RemoteCoordinator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestTemplate;

public final class SpringBootBeanRegistry implements CompensableBeanFactoryAware, EnvironmentAware {
	static final Logger logger = LoggerFactory.getLogger(SpringBootBeanRegistry.class);
	private static final SpringBootBeanRegistry instance = new SpringBootBeanRegistry();

	@javax.inject.Inject
	private CompensableBeanFactory beanFactory;
	private RestTemplate restTemplate;
	private Environment environment;

	private SpringBootBeanRegistry() {
		if (instance != null) {
			throw new IllegalStateException();
		}
	}

	public static SpringBootBeanRegistry getInstance() {
		return instance;
	}

	public RemoteCoordinator getConsumeCoordinator(String identifier) {
		RemoteCoordinatorRegistry registry = RemoteCoordinatorRegistry.getInstance();
		if (StringUtils.isBlank(identifier)) {
			return null;
		}

		RemoteAddr remoteAddr = CommonUtils.getRemoteAddr(identifier);
		String application = CommonUtils.getApplication(identifier);

		if (registry.containsPhysicalInstance(remoteAddr) == false) {
			SpringBootCoordinator handler = new SpringBootCoordinator();
			handler.setIdentifier(identifier);
			handler.setEnvironment(this.environment);

			RemoteCoordinator participant = (RemoteCoordinator) Proxy.newProxyInstance(
					SpringBootCoordinator.class.getClassLoader(), new Class[] { RemoteCoordinator.class }, handler);

			registry.putPhysicalInstance(remoteAddr, participant);
			registry.putRemoteNode(remoteAddr, CommonUtils.getRemoteNode(identifier));
			registry.putParticipant(application, participant);
		}

		return registry.getPhysicalInstance(remoteAddr);
	}

	public RestTemplate getRestTemplate() {
		return restTemplate;
	}

	public void setRestTemplate(RestTemplate restTemplate) {
		this.restTemplate = restTemplate;
	}

	public void setBeanFactory(CompensableBeanFactory tbf) {
		this.beanFactory = tbf;
	}

	public CompensableBeanFactory getBeanFactory() {
		return beanFactory;
	}

	public Environment getEnvironment() {
		return environment;
	}

	public void setEnvironment(Environment environment) {
		this.environment = environment;
	}

}
