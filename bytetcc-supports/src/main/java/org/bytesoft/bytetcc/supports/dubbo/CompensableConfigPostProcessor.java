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

import org.apache.commons.lang3.StringUtils;
import org.bytesoft.bytejta.supports.wire.RemoteCoordinator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.FatalBeanException;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.beans.PropertyValue;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.GenericBeanDefinition;

public class CompensableConfigPostProcessor implements BeanFactoryPostProcessor {
	static final Logger logger = LoggerFactory.getLogger(CompensableConfigPostProcessor.class);

	public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
		String[] beanNameArray = beanFactory.getBeanDefinitionNames();

		String applicationBeanId = null;
		String registryBeanId = null;
		String transactionBeanId = null;
		String compensableBeanId = null;

		for (int i = 0; i < beanNameArray.length; i++) {
			String beanName = beanNameArray[i];
			BeanDefinition beanDef = beanFactory.getBeanDefinition(beanName);
			String beanClassName = beanDef.getBeanClassName();
			if (com.alibaba.dubbo.config.ApplicationConfig.class.getName().equals(beanClassName)) {
				if (StringUtils.isBlank(applicationBeanId)) {
					applicationBeanId = beanName;
				} else {
					throw new FatalBeanException("There are more than one application name was found!");
				}
			} else if (org.bytesoft.bytetcc.TransactionCoordinator.class.getName().equals(beanClassName)) {
				if (StringUtils.isBlank(transactionBeanId)) {
					transactionBeanId = beanName;
				} else {
					throw new FatalBeanException(
							"There are more than one org.bytesoft.bytetcc.TransactionCoordinator was found!");
				}
			} else if (org.bytesoft.bytetcc.supports.dubbo.CompensableBeanRegistry.class.getName().equals(beanClassName)) {
				if (StringUtils.isBlank(registryBeanId)) {
					registryBeanId = beanName;
				} else {
					throw new FatalBeanException(
							"There are more than one org.bytesoft.bytetcc.supports.dubbo.CompensableBeanRegistry was found!");
				}
			} else if (org.bytesoft.bytetcc.CompensableCoordinator.class.getName().equals(beanClassName)) {
				if (StringUtils.isBlank(compensableBeanId)) {
					compensableBeanId = beanName;
				} else {
					throw new FatalBeanException(
							"There are more than one org.bytesoft.bytetcc.CompensableCoordinator was found!");
				}
			}
		}

		if (StringUtils.isBlank(applicationBeanId)) {
			throw new FatalBeanException("No application name was found!");
		}

		BeanDefinition beanDef = beanFactory.getBeanDefinition(applicationBeanId);
		MutablePropertyValues mpv = beanDef.getPropertyValues();
		PropertyValue pv = mpv.getPropertyValue("name");

		if (pv == null || pv.getValue() == null || StringUtils.isBlank(String.valueOf(pv.getValue()))) {
			throw new FatalBeanException("No application name was found!");
		}

		if (StringUtils.isBlank(compensableBeanId)) {
			throw new FatalBeanException("No configuration of class org.bytesoft.bytetcc.CompensableCoordinator was found.");
		} else if (StringUtils.isBlank(transactionBeanId)) {
			throw new FatalBeanException("No configuration of class org.bytesoft.bytetcc.TransactionCoordinator was found.");
		} else if (registryBeanId == null) {
			throw new FatalBeanException(
					"No configuration of class org.bytesoft.bytetcc.supports.dubbo.CompensableBeanRegistry was found.");
		}

		String application = String.valueOf(pv.getValue());
		this.initializeForProvider(beanFactory, application, transactionBeanId);
		this.initializeForConsumer(beanFactory, application, registryBeanId);
	}

	public void initializeForProvider(ConfigurableListableBeanFactory beanFactory, String application, String refBeanName)
			throws BeansException {
		BeanDefinitionRegistry registry = (BeanDefinitionRegistry) beanFactory;

		// <dubbo:service interface="org.bytesoft.bytejta.supports.wire.RemoteCoordinator" group="org.bytesoft.bytetcc"
		// ref="dispatcherCoordinator" filter="compensable" loadbalance="compensable" cluster="failfast" />
		GenericBeanDefinition beanDef = new GenericBeanDefinition();
		beanDef.setBeanClass(com.alibaba.dubbo.config.spring.ServiceBean.class);

		MutablePropertyValues mpv = beanDef.getPropertyValues();
		mpv.addPropertyValue("interface", RemoteCoordinator.class.getName());
		mpv.addPropertyValue("ref", new RuntimeBeanReference(refBeanName));
		mpv.addPropertyValue("cluster", "failfast");
		mpv.addPropertyValue("loadbalance", "compensable");
		mpv.addPropertyValue("filter", "compensable");
		mpv.addPropertyValue("group", "org.bytesoft.bytetcc");
		mpv.addPropertyValue("retries", "0");
		mpv.addPropertyValue("timeout", "60000");

		String skeletonBeanId = String.format("skeleton@%s", RemoteCoordinator.class.getName());
		registry.registerBeanDefinition(skeletonBeanId, beanDef);
	}

	public void initializeForConsumer(ConfigurableListableBeanFactory beanFactory, String application, String targetBeanName)
			throws BeansException {
		BeanDefinitionRegistry registry = (BeanDefinitionRegistry) beanFactory;

		// <dubbo:reference id="yyy"
		// interface="org.bytesoft.bytejta.supports.wire.RemoteCoordinator" group="org.bytesoft.bytetcc"
		// timeout="3000" filter="compensable" loadbalance="compensable" cluster="failfast" />
		GenericBeanDefinition beanDef = new GenericBeanDefinition();
		beanDef.setBeanClass(com.alibaba.dubbo.config.spring.ReferenceBean.class);

		MutablePropertyValues mpv = beanDef.getPropertyValues();
		mpv.addPropertyValue("interface", RemoteCoordinator.class.getName());
		mpv.addPropertyValue("timeout", "60000");
		mpv.addPropertyValue("cluster", "failfast");
		mpv.addPropertyValue("loadbalance", "compensable");
		mpv.addPropertyValue("filter", "compensable");
		mpv.addPropertyValue("group", "org.bytesoft.bytetcc");
		mpv.addPropertyValue("check", "false");
		mpv.addPropertyValue("retries", "0");

		String stubBeanId = String.format("stub@%s", RemoteCoordinator.class.getName());
		registry.registerBeanDefinition(stubBeanId, beanDef);

		// <bean id="xxx" class="org.bytesoft.bytetcc.supports.dubbo.CompensableBeanRegistry"
		// factory-method="getInstance">
		// <property name="consumeCoordinator" ref="yyy" />
		// </bean>
		BeanDefinition targetBeanDef = beanFactory.getBeanDefinition(targetBeanName);
		MutablePropertyValues targetMpv = targetBeanDef.getPropertyValues();
		targetMpv.addPropertyValue("consumeCoordinator", new RuntimeBeanReference(stubBeanId));
	}

}
