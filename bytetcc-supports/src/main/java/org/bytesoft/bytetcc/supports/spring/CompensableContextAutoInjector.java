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

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import org.bytesoft.bytetcc.supports.spring.aware.CompensableContextAware;
import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.aware.CompensableBeanFactoryAware;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;

public class CompensableContextAutoInjector implements CompensableBeanFactoryAware, BeanPostProcessor {
	static final Logger logger = LoggerFactory.getLogger(CompensableContextAutoInjector.class);

	private final Set<CompensableContextAware> awares = new HashSet<CompensableContextAware>();
	private CompensableBeanFactory beanFactory;

	public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
		if (CompensableContextAware.class.isInstance(bean)) {
			this.initializeCompensableContext((CompensableContextAware) bean);
		} // end-if (CompensableContextAware.class.isInstance(bean))

		return bean;
	}

	public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
		if (CompensableContextAware.class.isInstance(bean)) {
			this.initializeCompensableContext((CompensableContextAware) bean);
		} // end-if (CompensableContextAware.class.isInstance(bean))

		return bean;
	}

	private synchronized void initializeCompensableContext(CompensableContextAware aware) {
		if (this.beanFactory != null) {
			aware.setCompensableContext(this.beanFactory.getCompensableContext());
		} else {
			this.awares.add(aware);
		}
	}

	private synchronized void afterBeanFactoryInitialized() {
		for (Iterator<CompensableContextAware> itr = this.awares.iterator(); itr.hasNext();) {
			CompensableContextAware aware = itr.next();
			aware.setCompensableContext(this.beanFactory.getCompensableContext());
			itr.remove();
		} // end-for (Iterator<CompensableContextAware> itr = this.awares.iterator(); itr.hasNext();)
	}

	public CompensableBeanFactory getBeanFactory() {
		return beanFactory;
	}

	public synchronized void setBeanFactory(CompensableBeanFactory beanFactory) {
		this.beanFactory = beanFactory;
		this.afterBeanFactoryInitialized();
	}

}
