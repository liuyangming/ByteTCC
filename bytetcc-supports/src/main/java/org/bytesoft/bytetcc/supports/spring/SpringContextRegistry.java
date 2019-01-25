/**
 * Copyright 2014-2019 yangming.liu<bytefox@126.com>.
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

import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.aware.CompensableBeanFactoryAware;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.transaction.PlatformTransactionManager;

public final class SpringContextRegistry implements ApplicationContextAware, CompensableBeanFactoryAware {
	private static final SpringContextRegistry instance = new SpringContextRegistry();

	private PlatformTransactionManager transactionManager;
	private ApplicationContext applicationContext;
	private CompensableBeanFactory compensableBeanFactory;

	private SpringContextRegistry() {
		if (instance != null) {
			throw new IllegalStateException();
		}
	}

	public static SpringContextRegistry getInstance() {
		return instance;
	}

	public PlatformTransactionManager getTransactionManager() {
		return transactionManager;
	}

	public void setTransactionManager(PlatformTransactionManager transactionManager) {
		this.transactionManager = transactionManager;
	}

	public ApplicationContext getApplicationContext() {
		return applicationContext;
	}

	public void setApplicationContext(ApplicationContext applicationContext) {
		this.applicationContext = applicationContext;
	}

	public CompensableBeanFactory getBeanFactory() {
		return this.compensableBeanFactory;
	}

	public void setBeanFactory(CompensableBeanFactory tbf) {
		this.compensableBeanFactory = tbf;
	}

}
