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

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.bytesoft.common.utils.CommonUtils;
import org.bytesoft.compensable.aware.CompensableEndpointAware;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;

public class SpringBootEndpointPostProcessor
		implements InitializingBean, BeanFactoryPostProcessor, BeanPostProcessor, EnvironmentAware {
	static final Logger logger = LoggerFactory.getLogger(SpringBootEndpointPostProcessor.class);

	private Environment environment;
	private String identifier;

	public void afterPropertiesSet() throws Exception {
		this.initializeEndpointIfNecessary();
	}

	public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
		this.injectEndpointIfNecessary(bean);
		return bean;
	}

	public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
		this.injectEndpointIfNecessary(bean);
		return bean;
	}

	public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
		ClassLoader cl = Thread.currentThread().getContextClassLoader();

		List<BeanDefinition> beanDefList = new ArrayList<BeanDefinition>();
		String[] beanNameArray = beanFactory.getBeanDefinitionNames();
		for (int i = 0; i < beanNameArray.length; i++) {
			String beanName = beanNameArray[i];
			BeanDefinition beanDef = beanFactory.getBeanDefinition(beanName);
			String beanClassName = beanDef.getBeanClassName();

			Class<?> beanClass = null;
			try {
				beanClass = cl.loadClass(beanClassName);
			} catch (Exception ex) {
				logger.debug("Cannot load class {}, beanId= {}!", beanClassName, beanName, ex);
				continue;
			}

			if (CompensableEndpointAware.class.isAssignableFrom(beanClass)) {
				beanDefList.add(beanDef);
			}
		}

		this.initializeEndpointIfNecessary();

		for (int i = 0; i < beanDefList.size(); i++) {
			BeanDefinition beanDef = beanDefList.get(i);
			MutablePropertyValues mpv = beanDef.getPropertyValues();
			mpv.addPropertyValue(CompensableEndpointAware.ENDPOINT_FIELD_NAME, this.identifier);
		}

	}

	private void injectEndpointIfNecessary(Object bean) {
		if (CompensableEndpointAware.class.isInstance(bean)) {
			CompensableEndpointAware aware = (CompensableEndpointAware) bean;
			if (StringUtils.isBlank(aware.getEndpoint())) {
				initializeEndpointIfNecessary();
				aware.setEndpoint(identifier);
			} // end-if (StringUtils.isBlank(aware.getEndpoint()))
		} // end-if (CompensableEndpointAware.class.isInstance(bean))
	}

	public void initializeEndpointIfNecessary() {
		if (StringUtils.isBlank(this.identifier)) {
			String host = CommonUtils.getInetAddress();
			String name = this.environment.getProperty("spring.application.name");
			String port = this.environment.getProperty("server.port");
			this.identifier = String.format("%s:%s:%s", host, name, port);
		}
	}

	public void setEnvironment(Environment environment) {
		this.environment = environment;
	}

}
