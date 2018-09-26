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
package org.bytesoft.bytetcc.supports.boot;

import javax.sql.DataSource;
import javax.transaction.TransactionManager;

import org.bytesoft.bytejta.supports.jdbc.LocalXADataSource;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;

public class LocalXADataSourcePostProcessor implements BeanPostProcessor {

	@javax.annotation.Resource
	private TransactionManager transactionManager;

	public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
		return this.createDataSourceWrapperIfNecessary(bean, beanName);
	}

	public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
		return this.createDataSourceWrapperIfNecessary(bean, beanName);
	}

	private Object createDataSourceWrapperIfNecessary(Object bean, String beanName) {
		if (DataSource.class.isInstance(bean) == false) {
			return bean;
		} else if (LocalXADataSource.class.isInstance(bean)) {
			return bean;
		}

		DataSource delegate = (DataSource) bean;
		LocalXADataSource dataSource = new LocalXADataSource();
		dataSource.setDataSource(delegate);
		dataSource.setTransactionManager(this.transactionManager);
		return dataSource;
	}

}
