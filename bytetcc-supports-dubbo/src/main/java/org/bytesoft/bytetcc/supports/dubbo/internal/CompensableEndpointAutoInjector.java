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

import java.util.Iterator;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.bytesoft.common.utils.CommonUtils;
import org.bytesoft.compensable.aware.CompensableEndpointAware;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.FatalBeanException;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

public class CompensableEndpointAutoInjector implements SmartInitializingSingleton, BeanPostProcessor, ApplicationContextAware {
	static final Logger logger = LoggerFactory.getLogger(CompensableEndpointAutoInjector.class);

	private ApplicationContext applicationContext;

	public void afterSingletonsInstantiated() {
		com.alibaba.dubbo.config.ApplicationConfig applicationConfig = //
				this.applicationContext.getBean(com.alibaba.dubbo.config.ApplicationConfig.class);
		com.alibaba.dubbo.config.ProtocolConfig protocolConfig = //
				this.applicationContext.getBean(com.alibaba.dubbo.config.ProtocolConfig.class);

		if (applicationConfig == null || StringUtils.isBlank(applicationConfig.getName())) {
			throw new FatalBeanException("No configuration of class com.alibaba.dubbo.config.ApplicationConfig was found.");
		}

		if (protocolConfig == null) {
			throw new FatalBeanException("No configuration of class com.alibaba.dubbo.config.ProtocolConfig was found.");
		} else if (protocolConfig.getPort() == null) {
			throw new FatalBeanException(
					"The value of the attribute 'port' (<dubbo:protocol port='...' />) must be explicitly specified.");
		} else if (protocolConfig.getPort() <= 0) {
			throw new FatalBeanException(
					"The value of the attribute 'port' (<dubbo:protocol port='...' />) can not equal to -1.");
		}

		String host = CommonUtils.getInetAddress();
		String name = String.valueOf(applicationConfig.getName());
		String port = String.valueOf(protocolConfig.getPort());
		String identifier = String.format("%s:%s:%s", host, name, port);

		Map<String, CompensableEndpointAware> beanMap = //
				this.applicationContext.getBeansOfType(CompensableEndpointAware.class);
		for (Iterator<Map.Entry<String, CompensableEndpointAware>> itr = beanMap.entrySet().iterator(); itr.hasNext();) {
			Map.Entry<String, CompensableEndpointAware> entry = itr.next();
			CompensableEndpointAware aware = entry.getValue();
			aware.setEndpoint(identifier);
		} // end-for (Iterator<Map.Entry<String, CompensableEndpointAware>> itr = beanMap.entrySet().iterator(); itr.hasNext();)
	}

	public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
		if (CompensableEndpointAware.class.isInstance(bean)) {
			CompensableEndpointAware aware = (CompensableEndpointAware) bean;
			this.initializeEndpointIfNecessary(aware);
		} // end-if (CompensableEndpointAware.class.isInstance(bean))

		return bean;
	}

	public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
		if (CompensableEndpointAware.class.isInstance(bean)) {
			CompensableEndpointAware aware = (CompensableEndpointAware) bean;
			this.initializeEndpointIfNecessary(aware);
		} // end-if (CompensableEndpointAware.class.isInstance(bean))

		return bean;
	}

	private void initializeEndpointIfNecessary(CompensableEndpointAware aware) {
		if (StringUtils.isBlank(aware.getEndpoint())) {
			com.alibaba.dubbo.config.ApplicationConfig applicationConfig = //
					this.applicationContext.getBean(com.alibaba.dubbo.config.ApplicationConfig.class);
			com.alibaba.dubbo.config.ProtocolConfig protocolConfig = //
					this.applicationContext.getBean(com.alibaba.dubbo.config.ProtocolConfig.class);

			String host = CommonUtils.getInetAddress();
			String name = String.valueOf(applicationConfig.getName());
			String port = String.valueOf(protocolConfig.getPort());
			aware.setEndpoint(String.format("%s:%s:%s", host, name, port));
		}
	}

	public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
		this.applicationContext = applicationContext;
	}

}
