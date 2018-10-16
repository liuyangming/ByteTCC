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
import org.bytesoft.transaction.aware.TransactionEndpointAware;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.FatalBeanException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import com.alibaba.dubbo.common.utils.ConfigUtils;

public class CompensableEndpointAutoInjector implements InitializingBean, BeanPostProcessor, ApplicationContextAware {
	static final Logger logger = LoggerFactory.getLogger(CompensableEndpointAutoInjector.class);

	private ApplicationContext applicationContext;

	public void afterPropertiesSet() throws Exception {
		String host = CommonUtils.getInetAddress();

		String name = null;
		try {
			com.alibaba.dubbo.config.ApplicationConfig applicationConfig = //
					this.applicationContext.getBean(com.alibaba.dubbo.config.ApplicationConfig.class);
			if (StringUtils.isBlank(applicationConfig.getName())) {
				throw new IllegalStateException();
			}
			name = String.valueOf(applicationConfig.getName());
		} catch (IllegalStateException ex) {
			String application = ConfigUtils.getProperty("dubbo.application.name");
			if (StringUtils.isBlank(application)) {
				throw new FatalBeanException("No configuration of class com.alibaba.dubbo.config.ApplicationConfig was found.");
			}

			name = application;
		} catch (NoSuchBeanDefinitionException error) {
			String application = ConfigUtils.getProperty("dubbo.application.name");
			if (StringUtils.isBlank(application)) {
				throw new FatalBeanException("No configuration of class com.alibaba.dubbo.config.ApplicationConfig was found.");
			}

			name = application;
		}

		String port = null;
		try {
			com.alibaba.dubbo.config.ProtocolConfig protocolConfig = //
					this.applicationContext.getBean(com.alibaba.dubbo.config.ProtocolConfig.class);
			if (protocolConfig.getPort() == null) {
				throw new IllegalStateException();
			}
			port = String.valueOf(protocolConfig.getPort());
		} catch (NoSuchBeanDefinitionException error) {
			String serverPort = ConfigUtils.getProperty("dubbo.protocol.dubbo.port");
			if (StringUtils.isBlank(serverPort)) {
				throw new FatalBeanException("No configuration of class com.alibaba.dubbo.config.ProtocolConfig was found.");
			}

			port = serverPort;
		} catch (IllegalStateException error) {
			String serverPort = ConfigUtils.getProperty("dubbo.protocol.dubbo.port");
			if (StringUtils.isBlank(serverPort)) {
				throw new FatalBeanException("No configuration of class com.alibaba.dubbo.config.ProtocolConfig was found.");
			}

			port = serverPort;
		}

		if (StringUtils.isBlank(name)) {
			throw new FatalBeanException("No configuration of class com.alibaba.dubbo.config.ApplicationConfig was found.");
		}

		if (StringUtils.isBlank(port)) {
			throw new FatalBeanException(
					"The value of the attribute 'port' (<dubbo:protocol port='...' />) must be explicitly specified.");
		} else if (Integer.valueOf(port) <= 0) {
			throw new FatalBeanException(
					"The value of the attribute 'port' (<dubbo:protocol port='...' />) can not equal to -1.");
		}

		String identifier = String.format("%s:%s:%s", host, name, port);

		Map<String, TransactionEndpointAware> transactionEndpointMap = //
				this.applicationContext.getBeansOfType(TransactionEndpointAware.class);
		for (Iterator<Map.Entry<String, TransactionEndpointAware>> itr = transactionEndpointMap.entrySet().iterator(); itr
				.hasNext();) {
			Map.Entry<String, TransactionEndpointAware> entry = itr.next();
			TransactionEndpointAware aware = entry.getValue();
			aware.setEndpoint(identifier);
		} // end-for (Iterator<Map.Entry<String, TransactionEndpointAware>> itr = beanMap.entrySet().iterator(); itr.hasNext();)

		Map<String, CompensableEndpointAware> compensableEndpointMap = //
				this.applicationContext.getBeansOfType(CompensableEndpointAware.class);
		for (Iterator<Map.Entry<String, CompensableEndpointAware>> itr = compensableEndpointMap.entrySet().iterator(); itr
				.hasNext();) {
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
			String host = CommonUtils.getInetAddress();

			String name = null;
			try {
				com.alibaba.dubbo.config.ApplicationConfig applicationConfig = //
						this.applicationContext.getBean(com.alibaba.dubbo.config.ApplicationConfig.class);
				name = String.valueOf(applicationConfig.getName());
			} catch (NoSuchBeanDefinitionException error) {
				String application = ConfigUtils.getProperty("dubbo.application.name");
				if (StringUtils.isBlank(application)) {
					throw error;
				} else {
					name = application;
				}
			}

			String port = null;
			try {
				com.alibaba.dubbo.config.ProtocolConfig protocolConfig = //
						this.applicationContext.getBean(com.alibaba.dubbo.config.ProtocolConfig.class);
				port = String.valueOf(protocolConfig.getPort());
			} catch (NoSuchBeanDefinitionException error) {
				String serverPort = ConfigUtils.getProperty("dubbo.protocol.dubbo.port");
				if (StringUtils.isBlank(serverPort)) {
					throw error;
				} else {
					port = serverPort;
				}
			}

			aware.setEndpoint(String.format("%s:%s:%s", host, name, port));
		}
	}

	public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
		this.applicationContext = applicationContext;
	}

}
