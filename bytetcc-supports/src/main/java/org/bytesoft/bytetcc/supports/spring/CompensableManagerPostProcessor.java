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

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.FatalBeanException;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.RuntimeBeanReference;

public class CompensableManagerPostProcessor implements BeanFactoryPostProcessor {
	static final Logger logger = LoggerFactory.getLogger(CompensableManagerPostProcessor.class);

	public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
		ClassLoader cl = Thread.currentThread().getContextClassLoader();

		String compensableManagerBeanName = null;
		String userCompensableBeanName = null;
		String transactionManagerBeanName = null;
		final List<String> beanNameList = new ArrayList<String>();

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

			if (org.bytesoft.bytejta.supports.jdbc.LocalXADataSource.class.equals(beanClass)) {
				beanNameList.add(beanName);
			} else if (org.bytesoft.bytetcc.UserCompensableImpl.class.equals(beanClass)) {
				beanNameList.add(beanName);
				userCompensableBeanName = beanName;
			} else if (org.bytesoft.bytetcc.TransactionManagerImpl.class.equals(beanClass)) {
				compensableManagerBeanName = beanName;
			} else if (org.springframework.transaction.jta.JtaTransactionManager.class.equals(beanClass)) {
				beanNameList.add(beanName);
				transactionManagerBeanName = beanName;
			}

		}

		if (compensableManagerBeanName == null) {
			throw new FatalBeanException("No configuration of class org.bytesoft.bytetcc.TransactionManagerImpl was found.");
		}

		if (transactionManagerBeanName == null) {
			throw new FatalBeanException(
					"No configuration of org.springframework.transaction.jta.JtaTransactionManager was found.");
		}

		for (int i = 0; i < beanNameList.size(); i++) {
			String beanName = beanNameList.get(i);
			BeanDefinition beanDef = beanFactory.getBeanDefinition(beanName);
			MutablePropertyValues mpv = beanDef.getPropertyValues();
			RuntimeBeanReference beanRef = new RuntimeBeanReference(compensableManagerBeanName);
			mpv.addPropertyValue("transactionManager", beanRef);
		}

		BeanDefinition transactionManagerBeanDef = beanFactory.getBeanDefinition(transactionManagerBeanName);
		MutablePropertyValues transactionManagerMPV = transactionManagerBeanDef.getPropertyValues();
		RuntimeBeanReference userCompensableBeanRef = new RuntimeBeanReference(userCompensableBeanName);
		transactionManagerMPV.addPropertyValue("userTransaction", userCompensableBeanRef);
	}

}
