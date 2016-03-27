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

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.apache.log4j.Logger;
import org.bytesoft.compensable.Compensable;
import org.springframework.beans.BeansException;
import org.springframework.beans.FatalBeanException;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.beans.PropertyValue;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.RuntimeBeanReference;

public class CompensableDubboConfigValidator implements BeanFactoryPostProcessor {
	static final Logger logger = Logger.getLogger(CompensableDubboConfigValidator.class.getSimpleName());

	static final String KEY_TIMEOUT = "timeout";
	static final String KEY_FILTER_COMPENSABLE = "compensable";
	static final String KEY_GROUP_COMPENSABLE = "org.bytesoft.bytetcc";

	public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
		ClassLoader cl = Thread.currentThread().getContextClassLoader();

		final Map<String, Class<?>> beanClassMap = new HashMap<String, Class<?>>();
		final Map<String, BeanDefinition> serviceMap = new HashMap<String, BeanDefinition>();
		final Map<String, BeanDefinition> references = new HashMap<String, BeanDefinition>();

		String[] beanNameArray = beanFactory.getBeanDefinitionNames();
		for (int i = 0; i < beanNameArray.length; i++) {
			String beanName = beanNameArray[i];
			BeanDefinition beanDef = beanFactory.getBeanDefinition(beanName);
			String beanClassName = beanDef.getBeanClassName();

			Class<?> beanClass = null;
			try {
				beanClass = cl.loadClass(beanClassName);
				beanClassMap.put(beanName, beanClass);
			} catch (Exception ex) {
				continue;
			}

			if (com.alibaba.dubbo.config.spring.ServiceBean.class.equals(beanClass)) {
				serviceMap.put(beanName, beanDef);
			} else if (com.alibaba.dubbo.config.spring.ReferenceBean.class.equals(beanClass)) {
				references.put(beanName, beanDef);
			}

		}

		for (Iterator<Map.Entry<String, BeanDefinition>> itr = serviceMap.entrySet().iterator(); itr.hasNext();) {
			Map.Entry<String, BeanDefinition> entry = itr.next();
			String beanKey = entry.getKey();
			BeanDefinition beanDef = entry.getValue();
			MutablePropertyValues mpv = beanDef.getPropertyValues();
			PropertyValue ref = mpv.getPropertyValue("ref");
			PropertyValue filter = mpv.getPropertyValue("filter");
			PropertyValue group = mpv.getPropertyValue("group");
			if (ref == null || ref.getValue() == null || RuntimeBeanReference.class.equals(ref.getValue().getClass()) == false) {
				continue;
			}
			RuntimeBeanReference beanRef = (RuntimeBeanReference) ref.getValue();
			Class<?> refClass = beanClassMap.get(beanRef.getBeanName());
			if (refClass.getAnnotation(Compensable.class) == null) {
				continue;
			}

			if (group == null || group.getValue() == null || KEY_GROUP_COMPENSABLE.equals(group.getValue()) == false) {
				logger.warn(String.format("The value of attr 'group'(beanId= %s) should be 'org.bytesoft.bytetcc'.", beanKey));
				continue;
			} else if (filter == null || filter.getValue() == null || KEY_FILTER_COMPENSABLE.equals(filter.getValue()) == false) {
				logger.warn(String.format("The value of attr 'filter'(beanId= %s) should be 'compensable'.", beanKey));
				continue;
			}

			PropertyValue timeoutPv = mpv.getPropertyValue(KEY_TIMEOUT);
			Object value = timeoutPv == null ? null : timeoutPv.getValue();
			if (String.valueOf(Integer.MAX_VALUE).equals(value) == false) {
				throw new FatalBeanException(String.format("Timeout value(beanId= %s) must be %s." //
						, beanKey, Integer.MAX_VALUE));
			}
		}

		for (Iterator<Map.Entry<String, BeanDefinition>> itr = references.entrySet().iterator(); itr.hasNext();) {
			Map.Entry<String, BeanDefinition> entry = itr.next();
			String beanKey = entry.getKey();
			BeanDefinition beanDef = entry.getValue();
			MutablePropertyValues mpv = beanDef.getPropertyValues();
			PropertyValue filter = mpv.getPropertyValue("filter");
			PropertyValue group = mpv.getPropertyValue("group");

			if (group == null || group.getValue() == null || KEY_GROUP_COMPENSABLE.equals(group.getValue()) == false) {
				logger.warn(String.format("The value of attr 'group'(beanId= %s) should be 'org.bytesoft.bytetcc'.", beanKey));
				continue;
			} else if (filter == null || filter.getValue() == null || KEY_FILTER_COMPENSABLE.equals(filter.getValue()) == false) {
				logger.warn(String.format("The value of attr 'filter'(beanId= %s) should be 'compensable'.", beanKey));
				continue;
			}

			PropertyValue timeoutPv = mpv.getPropertyValue(KEY_TIMEOUT);
			Object value = timeoutPv == null ? null : timeoutPv.getValue();
			if (String.valueOf(Integer.MAX_VALUE).equals(value) == false) {
				throw new FatalBeanException(String.format("The value of attribute 'timeout' (beanId= %s) must be %s." //
						, beanKey, Integer.MAX_VALUE));
			}
		}
	}
}
