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
package org.bytesoft.bytetcc.supports.dubbo.validator;

import org.apache.commons.lang3.StringUtils;
import org.bytesoft.bytetcc.supports.dubbo.DubboConfigValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.FatalBeanException;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.beans.PropertyValue;
import org.springframework.beans.factory.config.BeanDefinition;

public class ServiceConfigValidator implements DubboConfigValidator {
	static final Logger logger = LoggerFactory.getLogger(ServiceConfigValidator.class);

	private String beanName;
	private BeanDefinition beanDefinition;

	public void validate() throws BeansException {
		MutablePropertyValues mpv = this.beanDefinition.getPropertyValues();
		PropertyValue retries = mpv.getPropertyValue("retries");
		PropertyValue loadbalance = mpv.getPropertyValue("loadbalance");
		PropertyValue cluster = mpv.getPropertyValue("cluster");
		PropertyValue filter = mpv.getPropertyValue("filter");
		PropertyValue group = mpv.getPropertyValue("group");

		if (group == null || group.getValue() == null //
				|| ("org.bytesoft.bytetcc".equals(group.getValue())
						|| String.valueOf(group.getValue()).startsWith("org.bytesoft.bytetcc-")) == false) {
			throw new FatalBeanException(String.format(
					"The value of attr 'group'(beanId= %s) should be 'org.bytesoft.bytetcc' or starts with 'org.bytesoft.bytetcc-'.",
					this.beanName));
		} else if (retries == null || retries.getValue() == null || "0".equals(retries.getValue()) == false) {
			throw new FatalBeanException(
					String.format("The value of attr 'retries'(beanId= %s) should be '0'.", this.beanName));
		} else if (loadbalance == null || loadbalance.getValue() == null
				|| "compensable".equals(loadbalance.getValue()) == false) {
			throw new FatalBeanException(
					String.format("The value of attr 'loadbalance'(beanId= %s) should be 'compensable'.", this.beanName));
		} else if (cluster == null || cluster.getValue() == null || "failfast".equals(cluster.getValue()) == false) {
			throw new FatalBeanException(
					String.format("The value of attribute 'cluster' (beanId= %s) must be 'failfast'.", this.beanName));
		} else if (filter == null || filter.getValue() == null || String.class.isInstance(filter.getValue()) == false) {
			throw new FatalBeanException(String.format(
					"The value of attr 'filter'(beanId= %s) must be java.lang.String and cannot be null.", this.beanName));
		} else {
			String filterValue = String.valueOf(filter.getValue());
			String[] filterArray = filterValue.split("\\s*,\\s*");
			int filters = 0, index = -1;
			for (int i = 0; i < filterArray.length; i++) {
				String element = filterArray[i];
				boolean filterEquals = StringUtils.equalsIgnoreCase("compensable", element);
				index = filterEquals ? i : index;
				filters = filterEquals ? filters + 1 : filters;
			}

			if (filters != 1) {
				throw new FatalBeanException(
						String.format("The value of attr 'filter'(beanId= %s) should contains 'compensable'.", this.beanName));
			} else if (index != 0) {
				throw new FatalBeanException(
						String.format("The first filter of bean(beanId= %s) should be 'compensable'.", this.beanName));
			}
		}

	}

	public String getBeanName() {
		return beanName;
	}

	public void setBeanName(String beanName) {
		this.beanName = beanName;
	}

	public BeanDefinition getBeanDefinition() {
		return beanDefinition;
	}

	public void setBeanDefinition(BeanDefinition beanDefinition) {
		this.beanDefinition = beanDefinition;
	}

}
