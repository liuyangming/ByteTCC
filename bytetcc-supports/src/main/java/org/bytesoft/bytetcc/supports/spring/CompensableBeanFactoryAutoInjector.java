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
package org.bytesoft.bytetcc.supports.spring;

import java.util.Iterator;
import java.util.Map;

import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.aware.CompensableBeanFactoryAware;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.Ordered;

@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
public class CompensableBeanFactoryAutoInjector
		implements BeanPostProcessor, Ordered, SmartInitializingSingleton, ApplicationContextAware {
	static final Logger logger = LoggerFactory.getLogger(CompensableBeanFactoryAutoInjector.class);

	private ApplicationContext applicationContext;

	public void afterSingletonsInstantiated() {
		Map<String, CompensableBeanFactoryAware> beanMap = //
				this.applicationContext.getBeansOfType(CompensableBeanFactoryAware.class);
		Iterator<Map.Entry<String, CompensableBeanFactoryAware>> iterator = //
				(beanMap == null) ? null : beanMap.entrySet().iterator();
		while (iterator != null && iterator.hasNext()) {
			Map.Entry<String, CompensableBeanFactoryAware> entry = iterator.next();
			CompensableBeanFactoryAware bean = entry.getValue();
			this.initializeCompensableBeanFactoryIfNecessary(bean);
		}
	}

	public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
		return bean;
	}

	public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
		if (CompensableBeanFactoryAware.class.isInstance(bean)) {
			this.initializeCompensableBeanFactoryIfNecessary((CompensableBeanFactoryAware) bean);
		} // end-if (CompensableBeanFactoryAware.class.isInstance(bean))

		return bean;
	}

	private void initializeCompensableBeanFactoryIfNecessary(CompensableBeanFactoryAware aware) {
		if (aware.getBeanFactory() == null) {
			CompensableBeanFactory beanFactory = //
					this.applicationContext.getBean(CompensableBeanFactory.class);
			aware.setBeanFactory(beanFactory);
		} // end-if (aware.getBeanFactory() == null)
	}

	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE;
	}

	public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
		this.applicationContext = applicationContext;
	}

}
