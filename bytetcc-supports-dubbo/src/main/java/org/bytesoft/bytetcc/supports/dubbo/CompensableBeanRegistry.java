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

import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.aware.CompensableBeanFactoryAware;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;

public final class CompensableBeanRegistry implements CompensableBeanFactoryAware, ApplicationContextAware, EnvironmentAware {
	static final Logger logger = LoggerFactory.getLogger(CompensableBeanRegistry.class);
	private static final CompensableBeanRegistry instance = new CompensableBeanRegistry();

	private ApplicationContext applicationContext;
	private Environment environment;
	@javax.inject.Inject
	private CompensableBeanFactory beanFactory;

	private CompensableBeanRegistry() {
		if (instance != null) {
			throw new IllegalStateException();
		}
	}

	public static CompensableBeanRegistry getInstance() {
		return instance;
	}

	public <T> T getBean(Class<T> requiredType) {
		try {
			return this.applicationContext.getBean(requiredType);
		} catch (NoSuchBeanDefinitionException error) {
			return null; // ignore
		}
	}

	public Environment getEnvironment() {
		return environment;
	}

	public void setEnvironment(Environment environment) {
		this.environment = environment;
	}

	public void setBeanFactory(CompensableBeanFactory tbf) {
		this.beanFactory = tbf;
	}

	public CompensableBeanFactory getBeanFactory() {
		return beanFactory;
	}

	public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
		this.applicationContext = applicationContext;
	}

}
