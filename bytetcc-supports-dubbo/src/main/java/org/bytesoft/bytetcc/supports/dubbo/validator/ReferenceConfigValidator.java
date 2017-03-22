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

import java.lang.reflect.Method;

import org.bytesoft.bytetcc.supports.dubbo.DubboConfigValidator;
import org.bytesoft.compensable.RemotingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.FatalBeanException;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.beans.PropertyValue;
import org.springframework.beans.factory.config.BeanDefinition;

public class ReferenceConfigValidator implements DubboConfigValidator {
	static final Logger logger = LoggerFactory.getLogger(ReferenceConfigValidator.class);

	private String beanName;
	private BeanDefinition beanDefinition;

	public void validate() throws BeansException {
		MutablePropertyValues mpv = this.beanDefinition.getPropertyValues();
		PropertyValue group = mpv.getPropertyValue("group");
		PropertyValue retries = mpv.getPropertyValue("retries");
		PropertyValue loadbalance = mpv.getPropertyValue("loadbalance");
		PropertyValue cluster = mpv.getPropertyValue("cluster");
		PropertyValue filter = mpv.getPropertyValue("filter");

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
		} else if (filter == null || filter.getValue() == null || "compensable".equals(filter.getValue()) == false) {
			throw new FatalBeanException(
					String.format("The value of attr 'filter'(beanId= %s) should be 'compensable'.", this.beanName));
		}

		PropertyValue pv = mpv.getPropertyValue("interface");
		String clazzName = String.valueOf(pv.getValue());
		ClassLoader cl = Thread.currentThread().getContextClassLoader();

		Class<?> clazz = null;
		try {
			clazz = cl.loadClass(clazzName);
		} catch (Exception ex) {
			throw new FatalBeanException(String.format("Cannot load class %s.", clazzName));
		}

		Method[] methodArray = clazz.getMethods();
		for (int i = 0; i < methodArray.length; i++) {
			Method method = methodArray[i];
			boolean declared = false;
			Class<?>[] exceptionTypeArray = method.getExceptionTypes();
			for (int j = 0; j < exceptionTypeArray.length; j++) {
				Class<?> exceptionType = exceptionTypeArray[j];
				if (RemotingException.class.isAssignableFrom(exceptionType)) {
					declared = true;
					break;
				}
			}

			if (declared == false) {
				// throw new FatalBeanException(String.format(
				// "The remote call method(%s) must be declared to throw a remote exception:
				// org.bytesoft.compensable.RemotingException!",
				// method));
				logger.warn("The remote call method({}) should be declared to throw a remote exception: {}!", method,
						RemotingException.class.getName());
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
