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

import org.springframework.beans.BeansException;
import org.springframework.beans.FatalBeanException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

public class TransactionConfigPostProcessor implements BeanFactoryPostProcessor {

	public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
		String[] beanNameArray = beanFactory.getBeanDefinitionNames();
		for (int i = 0; i < beanNameArray.length; i++) {
			String beanName = beanNameArray[i];
			BeanDefinition beanDef = beanFactory.getBeanDefinition(beanName);
			String beanClassName = beanDef.getBeanClassName();

			if (org.springframework.transaction.interceptor.TransactionProxyFactoryBean.class.getName().equals(beanClassName)) {
				throw new FatalBeanException(String.format(
						"Declaring transactions by configuration is not supported yet, please use annotations to declare transactions(beanId= %s).",
						beanName));
			}

			if (org.springframework.transaction.interceptor.TransactionInterceptor.class.getName().equals(beanClassName)) {
				throw new FatalBeanException(String.format(
						"Declaring transactions by configuration is not supported yet, please use annotations to declare transactions(beanId= %s).",
						beanName));
			}
		}
	}
}
