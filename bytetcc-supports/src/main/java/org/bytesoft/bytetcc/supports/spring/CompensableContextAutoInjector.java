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

import org.bytesoft.bytetcc.supports.spring.aware.CompensableContextAware;
import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.aware.CompensableBeanFactoryAware;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

public class CompensableContextAutoInjector
		implements CompensableBeanFactoryAware, BeanPostProcessor, SmartInitializingSingleton, ApplicationContextAware {
	static final Logger logger = LoggerFactory.getLogger(CompensableContextAutoInjector.class);

	private ApplicationContext applicationContext;

	private CompensableBeanFactory beanFactory;

	public void afterSingletonsInstantiated() {
		Map<String, CompensableContextAware> beanMap = //
				this.applicationContext.getBeansOfType(CompensableContextAware.class);
		for (Iterator<Map.Entry<String, CompensableContextAware>> itr = beanMap.entrySet().iterator(); itr.hasNext();) {
			Map.Entry<String, CompensableContextAware> entry = itr.next();
			CompensableContextAware aware = entry.getValue();
			aware.setCompensableContext(this.beanFactory.getCompensableContext());
		}
	}

	public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
		if (CompensableContextAware.class.isInstance(bean)) {
			CompensableContextAware aware = (CompensableContextAware) bean;
			aware.setCompensableContext(this.beanFactory.getCompensableContext());
		} // end-if (CompensableContextAware.class.isInstance(bean))

		return bean;
	}

	public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
		if (CompensableContextAware.class.isInstance(bean)) {
			CompensableContextAware aware = (CompensableContextAware) bean;
			aware.setCompensableContext(this.beanFactory.getCompensableContext());
		} // end-if (CompensableContextAware.class.isInstance(bean))

		return bean;
	}

	public CompensableBeanFactory getBeanFactory() {
		return beanFactory;
	}

	public void setBeanFactory(CompensableBeanFactory beanFactory) {
		this.beanFactory = beanFactory;
	}

	public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
		this.applicationContext = applicationContext;
	}

}
