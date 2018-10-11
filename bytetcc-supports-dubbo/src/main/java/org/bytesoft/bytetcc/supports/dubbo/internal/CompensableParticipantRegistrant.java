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
package org.bytesoft.bytetcc.supports.dubbo.internal;

import org.bytesoft.bytetcc.supports.dubbo.CompensableBeanRegistry;
import org.bytesoft.common.utils.CommonUtils;
import org.bytesoft.compensable.aware.CompensableEndpointAware;
import org.bytesoft.transaction.remote.RemoteCoordinator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.FatalBeanException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.SingletonBeanRegistry;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.config.ReferenceConfig;
import com.alibaba.dubbo.config.ServiceConfig;

public class CompensableParticipantRegistrant
		implements SmartInitializingSingleton, CompensableEndpointAware, BeanFactoryAware {
	static final Logger logger = LoggerFactory.getLogger(CompensableParticipantRegistrant.class);

	private BeanFactory beanFactory;
	private String endpoint;

	public void afterSingletonsInstantiated() {
		org.bytesoft.bytetcc.TransactionCoordinator transactionCoordinator = //
				this.beanFactory.getBean(org.bytesoft.bytetcc.TransactionCoordinator.class);
		org.bytesoft.bytetcc.supports.dubbo.CompensableBeanRegistry beanRegistry = //
				this.beanFactory.getBean(org.bytesoft.bytetcc.supports.dubbo.CompensableBeanRegistry.class);
		org.bytesoft.bytetcc.CompensableCoordinator compensableCoordinator = //
				this.beanFactory.getBean(org.bytesoft.bytetcc.CompensableCoordinator.class);

		if (compensableCoordinator == null) {
			throw new FatalBeanException("No configuration of class org.bytesoft.bytetcc.CompensableCoordinator was found.");
		} else if (transactionCoordinator == null) {
			throw new FatalBeanException("No configuration of class org.bytesoft.bytetcc.TransactionCoordinator was found.");
		} else if (beanRegistry == null) {
			throw new FatalBeanException(
					"No configuration of class org.bytesoft.bytetcc.supports.dubbo.CompensableBeanRegistry was found.");
		}

		this.initializeForProvider(transactionCoordinator);
		this.initializeForConsumer(beanRegistry);
	}

	public void initializeForProvider(RemoteCoordinator reference) throws BeansException {
		SingletonBeanRegistry registry = (SingletonBeanRegistry) this.beanFactory;
		ServiceConfig<RemoteCoordinator> globalServiceConfig = new ServiceConfig<RemoteCoordinator>();
		globalServiceConfig.setInterface(RemoteCoordinator.class);
		globalServiceConfig.setRef(reference);
		globalServiceConfig.setCluster("failfast");
		globalServiceConfig.setLoadbalance("bytetcc");
		globalServiceConfig.setFilter("bytetcc");
		globalServiceConfig.setGroup("org-bytesoft-bytetcc");
		globalServiceConfig.setRetries(0);
		globalServiceConfig.setTimeout(15000);

		ServiceConfig<RemoteCoordinator> applicationServiceConfig = new ServiceConfig<RemoteCoordinator>();
		applicationServiceConfig.setInterface(RemoteCoordinator.class);
		applicationServiceConfig.setRef(reference);
		applicationServiceConfig.setCluster("failfast");
		applicationServiceConfig.setLoadbalance("bytetcc");
		applicationServiceConfig.setFilter("bytetcc");
		applicationServiceConfig.setGroup(CommonUtils.getApplication(this.endpoint));
		applicationServiceConfig.setRetries(0);
		applicationServiceConfig.setTimeout(15000);

		try {
			com.alibaba.dubbo.config.ApplicationConfig applicationConfig = //
					this.beanFactory.getBean(com.alibaba.dubbo.config.ApplicationConfig.class);
			globalServiceConfig.setApplication(applicationConfig);
			applicationServiceConfig.setApplication(applicationConfig);
		} catch (NoSuchBeanDefinitionException error) {
			logger.warn("No configuration of class com.alibaba.dubbo.config.ApplicationConfig was found.");
		}

		try {
			com.alibaba.dubbo.config.RegistryConfig registryConfig = //
					this.beanFactory.getBean(com.alibaba.dubbo.config.RegistryConfig.class);
			if (registryConfig != null) {
				globalServiceConfig.setRegistry(registryConfig);
				applicationServiceConfig.setRegistry(registryConfig);
			}
		} catch (NoSuchBeanDefinitionException error) {
			logger.warn("No configuration of class com.alibaba.dubbo.config.RegistryConfig was found.");
		}

		try {
			com.alibaba.dubbo.config.ProtocolConfig protocolConfig = //
					this.beanFactory.getBean(com.alibaba.dubbo.config.ProtocolConfig.class);
			globalServiceConfig.setProtocol(protocolConfig);
			applicationServiceConfig.setProtocol(protocolConfig);
		} catch (NoSuchBeanDefinitionException error) {
			logger.warn("No configuration of class com.alibaba.dubbo.config.ProtocolConfig was found.");
		}

		globalServiceConfig.export();
		applicationServiceConfig.export();

		String globalSkeletonBeanId = String.format("skeleton@%s", RemoteCoordinator.class.getName());
		registry.registerSingleton(globalSkeletonBeanId, globalServiceConfig);

		String applicationSkeletonBeanId = //
				String.format("%s@%s", CommonUtils.getApplication(this.endpoint), RemoteCoordinator.class.getName());
		registry.registerSingleton(applicationSkeletonBeanId, applicationServiceConfig);
	}

	public void initializeForConsumer(CompensableBeanRegistry beanRegistry) throws BeansException {
		SingletonBeanRegistry registry = (SingletonBeanRegistry) this.beanFactory;
		ReferenceConfig<RemoteCoordinator> referenceConfig = new ReferenceConfig<RemoteCoordinator>();
		referenceConfig.setInterface(RemoteCoordinator.class);
		referenceConfig.setTimeout(15000);
		referenceConfig.setCluster("failfast");
		referenceConfig.setLoadbalance("bytetcc");
		referenceConfig.setFilter("bytetcc");
		referenceConfig.setGroup("org-bytesoft-bytetcc");
		referenceConfig.setCheck(false);
		referenceConfig.setRetries(0);
		referenceConfig.setScope(Constants.SCOPE_REMOTE);

		try {
			com.alibaba.dubbo.config.ApplicationConfig applicationConfig = //
					this.beanFactory.getBean(com.alibaba.dubbo.config.ApplicationConfig.class);
			referenceConfig.setApplication(applicationConfig);
		} catch (NoSuchBeanDefinitionException error) {
			logger.warn("No configuration of class com.alibaba.dubbo.config.ApplicationConfig was found.");
		}

		try {
			com.alibaba.dubbo.config.RegistryConfig registryConfig = //
					this.beanFactory.getBean(com.alibaba.dubbo.config.RegistryConfig.class);
			if (registryConfig != null) {
				referenceConfig.setRegistry(registryConfig);
			}
		} catch (NoSuchBeanDefinitionException error) {
			logger.warn("No configuration of class com.alibaba.dubbo.config.RegistryConfig was found.");
		}

		try {
			com.alibaba.dubbo.config.ProtocolConfig protocolConfig = //
					this.beanFactory.getBean(com.alibaba.dubbo.config.ProtocolConfig.class);
			referenceConfig.setProtocol(protocolConfig.getName());
		} catch (NoSuchBeanDefinitionException error) {
			logger.warn("No configuration of class com.alibaba.dubbo.config.ProtocolConfig was found.");
		}

		RemoteCoordinator globalCoordinator = referenceConfig.get();
		beanRegistry.setConsumeCoordinator(globalCoordinator);

		String stubBeanId = String.format("stub@%s", RemoteCoordinator.class.getName());
		registry.registerSingleton(stubBeanId, globalCoordinator);
	}

	public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
		this.beanFactory = beanFactory;
	}

	public String getEndpoint() {
		return this.endpoint;
	}

	public void setEndpoint(String identifier) {
		this.endpoint = identifier;
	}

}
