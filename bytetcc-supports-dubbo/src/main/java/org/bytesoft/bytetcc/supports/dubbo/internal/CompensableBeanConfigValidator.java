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
package org.bytesoft.bytetcc.supports.dubbo.internal;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.bytesoft.compensable.Compensable;
import org.bytesoft.compensable.RemotingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.FatalBeanException;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import com.alibaba.dubbo.config.annotation.Reference;
import com.alibaba.dubbo.config.spring.ReferenceBean;
import com.alibaba.dubbo.config.spring.ServiceBean;

public class CompensableBeanConfigValidator
		implements SmartInitializingSingleton, BeanPostProcessor, ApplicationContextAware, BeanFactoryAware {
	static final Logger logger = LoggerFactory.getLogger(CompensableBeanConfigValidator.class);

	private ApplicationContext applicationContext;
	private BeanFactory beanFactory;
	private transient boolean statefully;

	@SuppressWarnings("rawtypes")
	public void afterSingletonsInstantiated() {
		BeanDefinitionRegistry registry = (BeanDefinitionRegistry) this.beanFactory;
		ClassLoader cl = Thread.currentThread().getContextClassLoader();

		Map<String, ServiceBean> serviceMap = this.applicationContext.getBeansOfType(ServiceBean.class);
		for (Iterator<Map.Entry<String, ServiceBean>> itr = serviceMap.entrySet().iterator(); itr.hasNext();) {
			Map.Entry<String, ServiceBean> entry = itr.next();
			String beanId = entry.getKey();
			ServiceBean serviceBean = entry.getValue();

			BeanDefinition beanDef = registry.getBeanDefinition(beanId);
			MutablePropertyValues properties = beanDef.getPropertyValues();
			RuntimeBeanReference reference = (RuntimeBeanReference) properties.get("ref");

			BeanDefinition referenceDef = registry.getBeanDefinition(reference.getBeanName());
			String beanClassName = referenceDef.getBeanClassName();

			Class<?> beanClass = null;
			try {
				beanClass = cl.loadClass(beanClassName);
			} catch (Exception ex) {
				logger.debug("Cannot load class {}, beanId= {}!", beanClassName, beanId, ex);
				continue;
			}

			if (beanClass.getAnnotation(Compensable.class) == null) {
				continue;
			}

			String group = serviceBean.getGroup();
			if (StringUtils.equals("x-bytetcc", group) == false
					&& StringUtils.trimToEmpty(group).startsWith("x-bytetcc-") == false) {
				continue;
			}

			this.validateServiceBean(beanId, serviceBean);
		}

		Map<String, ReferenceBean> referenceMap = this.applicationContext.getBeansOfType(ReferenceBean.class);
		for (Iterator<Map.Entry<String, ReferenceBean>> itr = referenceMap.entrySet().iterator(); itr.hasNext();) {
			Map.Entry<String, ReferenceBean> entry = itr.next();
			String beanId = entry.getKey();
			ReferenceBean referenceBean = entry.getValue();

			String group = referenceBean.getGroup();
			if (StringUtils.equals("x-bytetcc", group) == false
					&& StringUtils.trimToEmpty(group).startsWith("x-bytetcc-") == false) {
				continue;
			}

			this.validateReferenceBean(beanId, referenceBean);
		}
	}

	@SuppressWarnings("rawtypes")
	public void validateServiceBean(String beanId, ServiceBean serviceBean) throws BeansException {
		Integer retries = serviceBean.getRetries();
		String loadBalance = serviceBean.getLoadbalance();
		String cluster = serviceBean.getCluster();
		String filter = serviceBean.getFilter();
		String group = serviceBean.getGroup();

		if (StringUtils.equalsIgnoreCase("x-bytetcc", group) == false
				&& StringUtils.trimToEmpty(group).startsWith("x-bytetcc-") == false) {
			throw new FatalBeanException(String.format(
					"The value of attr 'group'(beanId= %s) should be 'x-bytetcc' or starts with 'x-bytetcc-'.", beanId));
		} else if (retries != null && retries >= 0) {
			throw new FatalBeanException(String.format("The value of attr 'retries'(beanId= %s) should be '-1'.", beanId));
		} else if (this.statefully && StringUtils.equals(loadBalance, "bytetcc") == false) {
			throw new FatalBeanException(
					String.format("The value of attr 'loadbalance'(beanId= %s) should be 'bytetcc'.", beanId));
		} else if (StringUtils.equals("failfast", cluster) == false) {
			throw new FatalBeanException(
					String.format("The value of attribute 'cluster' (beanId= %s) must be 'failfast'.", beanId));
		} else if (filter == null) {
			throw new FatalBeanException(String
					.format("The value of attr 'filter'(beanId= %s) must be java.lang.String and cannot be null.", beanId));
		} else {
			String filterValue = StringUtils.trimToEmpty(filter);
			String[] filterArray = filterValue.split("\\s*,\\s*");
			int filters = 0, index = -1;
			for (int i = 0; i < filterArray.length; i++) {
				String element = filterArray[i];
				boolean filterEquals = StringUtils.equalsIgnoreCase("bytetcc", element);
				index = filterEquals ? i : index;
				filters = filterEquals ? filters + 1 : filters;
			}

			if (filters != 1) {
				throw new FatalBeanException(
						String.format("The value of attr 'filter'(beanId= %s) should contains 'bytetcc'.", beanId));
			} else if (index != 0) {
				throw new FatalBeanException(
						String.format("The first filter of bean(beanId= %s) should be 'bytetcc'.", beanId));
			}
		}

	}

	@SuppressWarnings("rawtypes")
	public void validateReferenceBean(String beanId, ReferenceBean referenceBean) throws BeansException {
		Integer retries = referenceBean.getRetries();
		String loadBalance = referenceBean.getLoadbalance();
		String cluster = referenceBean.getCluster();
		String filter = referenceBean.getFilter();
		String group = referenceBean.getGroup();

		if (StringUtils.equals(group, "x-bytetcc") == false
				&& StringUtils.trimToEmpty(group).startsWith("x-bytetcc-") == false) {
			throw new FatalBeanException(String.format(
					"The value of attr 'group'(beanId= %s) should be 'x-bytetcc' or starts with 'x-bytetcc-'.", beanId));
		} else if (retries != null && retries >= 0) {
			throw new FatalBeanException(String.format("The value of attr 'retries'(beanId= %s) should be '-1'.", beanId));
		} else if (this.statefully && StringUtils.equals(loadBalance, "bytetcc") == false) {
			throw new FatalBeanException(
					String.format("The value of attr 'loadbalance'(beanId= %s) should be 'bytetcc'.", beanId));
		} else if (StringUtils.equals("failfast", cluster) == false) {
			throw new FatalBeanException(
					String.format("The value of attribute 'cluster' (beanId= %s) must be 'failfast'.", beanId));
		} else if (filter == null) {
			throw new FatalBeanException(String
					.format("The value of attr 'filter'(beanId= %s) must be java.lang.String and cannot be null.", beanId));
		} else {
			String filterValue = StringUtils.trimToEmpty(filter);
			String[] filterArray = filterValue.split("\\s*,\\s*");

			int filters = 0, index = -1;
			for (int i = 0; i < filterArray.length; i++) {
				String element = filterArray[i];
				boolean filterEquals = StringUtils.equalsIgnoreCase("bytetcc", element);
				index = filterEquals ? i : index;
				filters = filterEquals ? filters + 1 : filters;
			}

			if (filters != 1) {
				throw new FatalBeanException(
						String.format("The value of attr 'filter'(beanId= %s) should contains 'bytetcc'.", beanId));
			} else if (index != (filterArray.length - 1)) {
				throw new FatalBeanException(String.format("The last filter of bean(beanId= %s) should be 'bytetcc'.", beanId));
			}
		}

		String clazzName = referenceBean.getInterface();
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

	public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
		return bean;
	}

	@SuppressWarnings("rawtypes")
	public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
		Class<?> targetClass = AopUtils.getTargetClass(bean);
		if (targetClass == null) {
			return bean;
		}

		Compensable compensable = targetClass.getAnnotation(Compensable.class);
		if (compensable == null) {
			return bean;
		}

		Field[] fields = targetClass.getDeclaredFields();
		for (int i = 0; fields != null && i < fields.length; i++) {
			Field field = fields[i];
			Reference reference = field.getAnnotation(Reference.class);
			if (reference == null) {
				continue;
			}

			ReferenceBean referenceConfig = new ReferenceBean(reference);
			this.validateReferenceBean(beanName, referenceConfig);
		}

		return bean;
	}

	public boolean isStatefully() {
		return statefully;
	}

	public void setStatefully(boolean statefully) {
		this.statefully = statefully;
	}

	public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
		this.beanFactory = beanFactory;
	}

	public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
		this.applicationContext = applicationContext;
	}

}
