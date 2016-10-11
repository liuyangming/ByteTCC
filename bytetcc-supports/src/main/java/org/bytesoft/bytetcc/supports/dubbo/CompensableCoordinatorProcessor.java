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

import org.apache.commons.lang3.StringUtils;
import org.bytesoft.bytetcc.TransactionCoordinator;
import org.springframework.beans.BeansException;
import org.springframework.beans.FatalBeanException;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.beans.PropertyValue;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.GenericBeanDefinition;

import com.alibaba.dubbo.config.ApplicationConfig;

public class CompensableCoordinatorProcessor implements BeanFactoryPostProcessor {

	public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {

		BeanDefinitionRegistry registry = (BeanDefinitionRegistry) beanFactory;

		String application = null;
		String[] appBeanNameArray = beanFactory.getBeanNamesForType(ApplicationConfig.class);
		if (appBeanNameArray != null && appBeanNameArray.length == 1) {
			String beanName = appBeanNameArray[0];
			BeanDefinition beanDef = registry.getBeanDefinition(beanName);
			MutablePropertyValues values = beanDef.getPropertyValues();
			String propertyName = "name";
			PropertyValue pv = values.getPropertyValue(propertyName);
			application = pv == null ? null : (String) pv.getValue();
		}

		if (StringUtils.isBlank(application)) {
			throw new FatalBeanException("There is no application name specified, or there is more than one application name!");
		}

		String[] coordinatorNameArray = beanFactory.getBeanNamesForType(TransactionCoordinator.class);
		if (coordinatorNameArray != null && coordinatorNameArray.length == 1) {
			String beanName = coordinatorNameArray[0];

			GenericBeanDefinition beanDef = new GenericBeanDefinition();
			beanDef.setBeanClass(com.alibaba.dubbo.config.spring.ServiceBean.class);
			MutablePropertyValues values = beanDef.getPropertyValues();
			values.addPropertyValue("group", application);
			values.addPropertyValue("interface", TransactionCoordinator.class.getName());
			values.addPropertyValue("ref", new RuntimeBeanReference(beanName));
			values.addPropertyValue("retries", "0");
			values.addPropertyValue("timeout", String.valueOf(1000L * 6));
			registry.registerBeanDefinition(String.format("%s@%s", beanName, application), beanDef);
		} else {
			throw new FatalBeanException("No available(or redundant) org.bytesoft.bytetcc.TransactionCoordinator!");
		}

	}

}
