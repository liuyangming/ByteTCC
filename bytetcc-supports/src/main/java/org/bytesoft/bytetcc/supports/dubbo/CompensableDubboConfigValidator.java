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

import org.springframework.beans.BeansException;
import org.springframework.beans.FatalBeanException;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.beans.PropertyValue;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

public class CompensableDubboConfigValidator implements BeanFactoryPostProcessor {
	static final String KEY_TIMEOUT = "timeout";

	public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
		ClassLoader cl = Thread.currentThread().getContextClassLoader();

		String[] beanNameArray = beanFactory.getBeanDefinitionNames();
		for (int i = 0; i < beanNameArray.length; i++) {
			String beanName = beanNameArray[i];
			BeanDefinition beanDef = beanFactory.getBeanDefinition(beanName);
			String beanClassName = beanDef.getBeanClassName();

			Class<?> beanClass = null;
			try {
				beanClass = cl.loadClass(beanClassName);
			} catch (Exception ex) {
				continue;
			}

			if (com.alibaba.dubbo.config.spring.ServiceBean.class.equals(beanClass)) {
				MutablePropertyValues mpv = beanDef.getPropertyValues();
				PropertyValue pv = mpv.getPropertyValue(KEY_TIMEOUT);
				Object value = pv == null ? null : pv.getValue();
				if (String.valueOf(Integer.MAX_VALUE).equals(value) == false) {
					throw new FatalBeanException(String.format("Timeout value(beanId= %s) must be %s." //
							, beanName, Integer.MAX_VALUE));
				}
			} else if (com.alibaba.dubbo.config.spring.ReferenceBean.class.equals(beanClass)) {
				MutablePropertyValues mpv = beanDef.getPropertyValues();
				PropertyValue pv = mpv.getPropertyValue(KEY_TIMEOUT);
				Object value = pv == null ? null : pv.getValue();
				if (String.valueOf(Integer.MAX_VALUE).equals(value) == false) {
					throw new FatalBeanException(String.format("Timeout value(beanId= %s) must be %s." //
							, beanName, Integer.MAX_VALUE));
				}
			}

		}
	}

}
