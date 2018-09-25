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

import org.apache.commons.lang3.StringUtils;
import org.bytesoft.bytetcc.supports.dubbo.CompensableBeanRegistry;
import org.bytesoft.transaction.remote.RemoteCoordinator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.FatalBeanException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.SingletonBeanRegistry;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.config.ProtocolConfig;
import com.alibaba.dubbo.config.ReferenceConfig;
import com.alibaba.dubbo.config.RegistryConfig;
import com.alibaba.dubbo.config.ServiceConfig;

public class CompensableParticipantRegistrant implements SmartInitializingSingleton, BeanFactoryAware {
	static final Logger logger = LoggerFactory.getLogger(CompensableParticipantRegistrant.class);

	private BeanFactory beanFactory;

	public void afterSingletonsInstantiated() {
		com.alibaba.dubbo.config.ApplicationConfig applicationConfig = //
				this.beanFactory.getBean(com.alibaba.dubbo.config.ApplicationConfig.class);
		org.bytesoft.bytetcc.TransactionCoordinator transactionCoordinator = //
				this.beanFactory.getBean(org.bytesoft.bytetcc.TransactionCoordinator.class);
		org.bytesoft.bytetcc.supports.dubbo.CompensableBeanRegistry beanRegistry = //
				this.beanFactory.getBean(org.bytesoft.bytetcc.supports.dubbo.CompensableBeanRegistry.class);
		org.bytesoft.bytetcc.CompensableCoordinator compensableCoordinator = //
				this.beanFactory.getBean(org.bytesoft.bytetcc.CompensableCoordinator.class);

		if (applicationConfig == null || StringUtils.isBlank(applicationConfig.getName())) {
			throw new FatalBeanException("No application name was found!");
		}

		if (compensableCoordinator == null) {
			throw new FatalBeanException("No configuration of class org.bytesoft.bytetcc.CompensableCoordinator was found.");
		} else if (transactionCoordinator == null) {
			throw new FatalBeanException("No configuration of class org.bytesoft.bytetcc.TransactionCoordinator was found.");
		} else if (beanRegistry == null) {
			throw new FatalBeanException(
					"No configuration of class org.bytesoft.bytetcc.supports.dubbo.CompensableBeanRegistry was found.");
		}

		this.initializeForProvider(applicationConfig, transactionCoordinator);
		this.initializeForConsumer(applicationConfig, beanRegistry);
	}

	public void initializeForProvider(com.alibaba.dubbo.config.ApplicationConfig applicationConfig, RemoteCoordinator reference)
			throws BeansException {
		// BeanDefinitionRegistry registry = (BeanDefinitionRegistry) this.beanFactory;
		// GenericBeanDefinition beanDef = new GenericBeanDefinition();
		// beanDef.setBeanClass(com.alibaba.dubbo.config.spring.ServiceBean.class);
		//
		// MutablePropertyValues mpv = beanDef.getPropertyValues();
		// mpv.addPropertyValue("interface", RemoteCoordinator.class.getName());
		// mpv.addPropertyValue("ref", new RuntimeBeanReference(refBeanName));
		// mpv.addPropertyValue("cluster", "failfast");
		// mpv.addPropertyValue("loadbalance", "compensable");
		// mpv.addPropertyValue("filter", "compensable");
		// mpv.addPropertyValue("group", "org-bytesoft-bytetcc");
		// mpv.addPropertyValue("retries", "0");
		// mpv.addPropertyValue("timeout", "6000");
		//
		// String skeletonBeanId = String.format("skeleton@%s", RemoteCoordinator.class.getName());
		// registry.registerBeanDefinition(skeletonBeanId, beanDef);

		SingletonBeanRegistry registry = (SingletonBeanRegistry) this.beanFactory;
		ServiceConfig<RemoteCoordinator> serviceConfig = new ServiceConfig<RemoteCoordinator>();
		serviceConfig.setInterface(RemoteCoordinator.class);
		serviceConfig.setRef(reference);
		serviceConfig.setCluster("failfast");
		serviceConfig.setLoadbalance("compensable");
		serviceConfig.setFilter("compensable");
		serviceConfig.setGroup("org-bytesoft-bytetcc");
		serviceConfig.setRetries(0);
		serviceConfig.setTimeout(6000);
		serviceConfig.setApplication(applicationConfig);
		serviceConfig.setRegistry(this.beanFactory.getBean(RegistryConfig.class));
		serviceConfig.setProtocol(this.beanFactory.getBean(ProtocolConfig.class));

		serviceConfig.export();

		String skeletonBeanId = String.format("skeleton@%s", RemoteCoordinator.class.getName());
		registry.registerSingleton(skeletonBeanId, serviceConfig);
	}

	public void initializeForConsumer(com.alibaba.dubbo.config.ApplicationConfig applicationConfig,
			CompensableBeanRegistry beanRegistry) throws BeansException {
		// BeanDefinitionRegistry registry = (BeanDefinitionRegistry) beanFactory;
		// GenericBeanDefinition beanDef = new GenericBeanDefinition();
		// beanDef.setBeanClass(com.alibaba.dubbo.config.spring.ReferenceBean.class);
		//
		// MutablePropertyValues mpv = beanDef.getPropertyValues();
		// mpv.addPropertyValue("interface", RemoteCoordinator.class.getName());
		// mpv.addPropertyValue("timeout", "6000");
		// mpv.addPropertyValue("cluster", "failfast");
		// mpv.addPropertyValue("loadbalance", "compensable");
		// mpv.addPropertyValue("filter", "compensable");
		// mpv.addPropertyValue("group", "org-bytesoft-bytetcc");
		// mpv.addPropertyValue("check", "false");
		// mpv.addPropertyValue("retries", "0");
		//
		// String stubBeanId = String.format("stub@%s", RemoteCoordinator.class.getName());
		// registry.registerBeanDefinition(stubBeanId, beanDef);
		//
		// BeanDefinition targetBeanDef = this.applicationContext.getBeanDefinition(targetBeanName);
		// MutablePropertyValues targetMpv = targetBeanDef.getPropertyValues();
		// targetMpv.addPropertyValue("consumeCoordinator", new RuntimeBeanReference(stubBeanId));

		SingletonBeanRegistry registry = (SingletonBeanRegistry) this.beanFactory;
		ReferenceConfig<RemoteCoordinator> referenceConfig = new ReferenceConfig<RemoteCoordinator>();
		referenceConfig.setInterface(RemoteCoordinator.class);
		referenceConfig.setTimeout(6000);
		referenceConfig.setCluster("failfast");
		referenceConfig.setLoadbalance("compensable");
		referenceConfig.setFilter("compensable");
		referenceConfig.setGroup("org-bytesoft-bytetcc");
		referenceConfig.setCheck(false);
		referenceConfig.setRetries(0);
		referenceConfig.setApplication(applicationConfig);
		referenceConfig.setScope(Constants.SCOPE_REMOTE);
		referenceConfig.setRegistry(this.beanFactory.getBean(RegistryConfig.class));
		referenceConfig.setProtocol(this.beanFactory.getBean(ProtocolConfig.class).getName());

		RemoteCoordinator globalCoordinator = referenceConfig.get();
		beanRegistry.setConsumeCoordinator(globalCoordinator);

		String stubBeanId = String.format("stub@%s", RemoteCoordinator.class.getName());
		registry.registerSingleton(stubBeanId, globalCoordinator);
	}

	public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
		this.beanFactory = beanFactory;
	}

}
