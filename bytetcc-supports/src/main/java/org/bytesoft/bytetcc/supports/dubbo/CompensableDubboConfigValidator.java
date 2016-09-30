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

import org.bytesoft.compensable.Compensable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.FatalBeanException;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.beans.PropertyValue;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.RuntimeBeanReference;

public class CompensableDubboConfigValidator implements BeanFactoryPostProcessor {
	static final Logger logger = LoggerFactory.getLogger(CompensableDubboConfigValidator.class.getSimpleName());

	static final String CONSTANTS_CLUSTER_FAILFAST = "failfast";

	static final String KEY_CLUSTER = "cluster";
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
			if (ref == null || ref.getValue() == null
					|| RuntimeBeanReference.class.equals(ref.getValue().getClass()) == false) {
				continue;
			}
			RuntimeBeanReference beanRef = (RuntimeBeanReference) ref.getValue();
			Class<?> refClass = beanClassMap.get(beanRef.getBeanName());
			if (refClass.getAnnotation(Compensable.class) == null) {
				continue;
			}

			if (group == null || group.getValue() == null || KEY_GROUP_COMPENSABLE.equals(group.getValue()) == false) {
				logger.warn("The value of attr 'group'(beanId= {}) should be 'org.bytesoft.bytetcc'.", beanKey);
				continue;
			} else if (filter == null || filter.getValue() == null
					|| KEY_FILTER_COMPENSABLE.equals(filter.getValue()) == false) {
				logger.warn("The value of attr 'filter'(beanId= {}) should be 'compensable'.", beanKey);
				continue;
			}

			PropertyValue cluster = mpv.getPropertyValue(KEY_CLUSTER);
			Object value = cluster == null ? null : cluster.getValue();
			if (CONSTANTS_CLUSTER_FAILFAST.equalsIgnoreCase(String.valueOf(value)) == false) {
				throw new FatalBeanException(
						String.format("The value of attribute 'cluster' (beanId= %s) must be 'failfast'.", beanKey));
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
				logger.warn("The value of attr 'group'(beanId= {}) should be 'org.bytesoft.bytetcc'.", beanKey);
				continue;
			} else if (filter == null || filter.getValue() == null
					|| KEY_FILTER_COMPENSABLE.equals(filter.getValue()) == false) {
				logger.warn("The value of attr 'filter'(beanId= {}) should be 'compensable'.", beanKey);
				continue;
			}

			PropertyValue cluster = mpv.getPropertyValue(KEY_CLUSTER);
			Object value = cluster == null ? null : cluster.getValue();
			if (CONSTANTS_CLUSTER_FAILFAST.equalsIgnoreCase(String.valueOf(value)) == false) {
				throw new FatalBeanException(
						String.format("The value of attribute 'cluster' (beanId= %s) must be 'failfast'.", beanKey));
			}
		}
	}
}
