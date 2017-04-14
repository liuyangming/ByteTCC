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
import java.util.Iterator;
import java.util.List;

import org.bytesoft.bytejta.supports.jdbc.LocalXADataSource;
import org.bytesoft.bytetcc.UserCompensableImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.FatalBeanException;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.transaction.jta.JtaTransactionManager;

public class CompensableManagerPostProcessor implements BeanFactoryPostProcessor, BeanPostProcessor {
	static final Logger logger = LoggerFactory.getLogger(CompensableManagerPostProcessor.class);

	private org.bytesoft.bytetcc.TransactionManagerImpl transactionManager;
	private org.bytesoft.bytetcc.UserCompensableImpl userCompensable;
	private org.springframework.transaction.jta.JtaTransactionManager jtaTransactionManager;

	private final List<Object> beanList = new ArrayList<Object>(); // LocalXADataSource/UserCompensableImpl/JtaTransactionManager

	public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
		if (org.bytesoft.bytejta.supports.jdbc.LocalXADataSource.class.isInstance(bean)) {
			LocalXADataSource dataSource = (LocalXADataSource) bean;

			if (dataSource.getTransactionManager() != null) {
				return bean;
			} else if (this.transactionManager == null) {
				this.beanList.add(dataSource);
			} else {
				dataSource.setTransactionManager(this.transactionManager);
			}
		} else if (org.bytesoft.bytetcc.UserCompensableImpl.class.isInstance(bean)) {
			UserCompensableImpl compensable = (UserCompensableImpl) bean;

			if (this.userCompensable != null) {
				throw new FatalBeanException("There are more than one org.bytesoft.bytetcc.UserCompensableImpl was found!");
			}
			this.userCompensable = compensable;

			this.configureUserTransactionIfNecessary();

			if (this.userCompensable.getTransactionManager() != null) {
				return bean;
			} else if (this.transactionManager == null) {
				this.beanList.add(compensable);
			} else {
				this.userCompensable.setTransactionManager(this.transactionManager);
			}

		} else if (org.bytesoft.bytetcc.TransactionManagerImpl.class.isInstance(bean)) {
			if (this.transactionManager != null) {
				throw new FatalBeanException("There are more than one org.bytesoft.bytetcc.TransactionManagerImpl was found!");
			}
			this.transactionManager = (org.bytesoft.bytetcc.TransactionManagerImpl) bean;

			this.configureTransactionManager();
		} else if (org.springframework.transaction.jta.JtaTransactionManager.class.isInstance(bean)) {

			if (this.jtaTransactionManager != null) {
				throw new FatalBeanException(
						"There are more than one org.springframework.transaction.jta.JtaTransactionManager was found!");
			}

			this.jtaTransactionManager = (JtaTransactionManager) bean;

			if (this.jtaTransactionManager.getTransactionManager() != null
					&& this.jtaTransactionManager.getUserTransaction() != null) {
				return bean;
			} else if (this.transactionManager == null) {
				this.configureTransactionManager();
				this.beanList.add(bean);
			} else {
				this.configureTransactionManager();
				this.jtaTransactionManager.setTransactionManager(this.transactionManager);
			}

		}
		return bean;
	}

	private void configureUserTransactionIfNecessary() {
		if (this.jtaTransactionManager != null) {
			this.jtaTransactionManager.setUserTransaction(this.userCompensable);
		}
	}

	private void configureTransactionManager() {
		Iterator<Object> itr = this.beanList.iterator();
		while (itr.hasNext()) {
			Object object = itr.next();
			if (UserCompensableImpl.class.isInstance(object)) {
				itr.remove();

				UserCompensableImpl compensable = (UserCompensableImpl) object;
				compensable.setTransactionManager(this.transactionManager);
			} else if (LocalXADataSource.class.isInstance(object)) {
				itr.remove();

				LocalXADataSource dataSource = (LocalXADataSource) object;
				dataSource.setTransactionManager(this.transactionManager);
			} else if (JtaTransactionManager.class.isInstance(object)) {
				itr.remove();

				JtaTransactionManager jtaTxMgr = (JtaTransactionManager) object;
				jtaTxMgr.setTransactionManager(this.transactionManager);
			}
		}
	}

	public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
		return bean;
	}

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

			String[] array = beanDef.getDependsOn();
			if (array == null || array.length == 0) {
				beanDef.setDependsOn(compensableManagerBeanName);
			} else {
				String[] dependsOn = new String[array.length + 1];
				System.arraycopy(array, 0, dependsOn, 0, array.length);
				dependsOn[array.length] = compensableManagerBeanName;
				beanDef.setDependsOn(dependsOn);
			}

			MutablePropertyValues mpv = beanDef.getPropertyValues();
			RuntimeBeanReference beanRef = new RuntimeBeanReference(compensableManagerBeanName);
			mpv.addPropertyValue("transactionManager", beanRef);
		}

		BeanDefinition transactionManagerBeanDef = beanFactory.getBeanDefinition(transactionManagerBeanName);
		String[] depandsArray = transactionManagerBeanDef.getDependsOn();
		if (depandsArray == null || depandsArray.length == 0) {
			transactionManagerBeanDef.setDependsOn(userCompensableBeanName);
		} else {
			String[] dependsOn = new String[depandsArray.length + 1];
			System.arraycopy(depandsArray, 0, dependsOn, 0, depandsArray.length);
			dependsOn[depandsArray.length] = userCompensableBeanName;
			transactionManagerBeanDef.setDependsOn(dependsOn);
		}

		MutablePropertyValues transactionManagerMPV = transactionManagerBeanDef.getPropertyValues();
		RuntimeBeanReference userCompensableBeanRef = new RuntimeBeanReference(userCompensableBeanName);
		transactionManagerMPV.addPropertyValue("userTransaction", userCompensableBeanRef);
	}

}
