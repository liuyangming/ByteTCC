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

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.bytesoft.compensable.Compensable;
import org.springframework.aop.TargetClassAware;
import org.springframework.beans.BeansException;
import org.springframework.beans.FatalBeanException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public class CompensableAnnotationPostProcessor implements BeanFactoryPostProcessor, BeanPostProcessor,
		ApplicationContextAware {

	private ApplicationContext applicationContext;

	public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
		Map<String, Class<?>> otherServiceMap = new HashMap<String, Class<?>>();
		Map<String, Compensable> compensables = new HashMap<String, Compensable>();

		ClassLoader cl = Thread.currentThread().getContextClassLoader();
		String[] beanNameArray = beanFactory.getBeanDefinitionNames();
		for (int i = 0; beanNameArray != null && i < beanNameArray.length; i++) {
			String beanName = beanNameArray[i];
			BeanDefinition beanDef = beanFactory.getBeanDefinition(beanName);
			String className = beanDef.getBeanClassName();
			try {
				Class<?> clazz = cl.loadClass(className);
				Compensable compensable = clazz.getAnnotation(Compensable.class);
				if (compensable == null) {
					otherServiceMap.put(beanName, clazz);
					continue;
				} else {
					compensables.put(beanName, compensable);
				}

				Class<?> interfaceClass = compensable.interfaceClass();
				this.validateTransactionalAnnotation(interfaceClass, clazz);
			} catch (ClassNotFoundException ex) {
				throw new FatalBeanException(ex.getMessage(), ex);
			} catch (IllegalStateException ex) {
				throw new FatalBeanException(ex.getMessage(), ex);
			}
		}

		Iterator<Map.Entry<String, Compensable>> itr = compensables.entrySet().iterator();
		while (itr.hasNext()) {
			Map.Entry<String, Compensable> entry = itr.next();
			Compensable compensable = entry.getValue();
			Class<?> interfaceClass = compensable.interfaceClass();
			String confirmableKey = compensable.confirmableKey();
			String cancellableKey = compensable.cancellableKey();
			if (StringUtils.isNotBlank(confirmableKey)) {
				if (compensables.containsKey(confirmableKey)) {
					throw new FatalBeanException("The confirm bean cannot be a compensable service!");
				}
				Class<?> clazz = otherServiceMap.get(confirmableKey);
				if (clazz != null) {
					try {
						this.validateTransactionalAnnotation(interfaceClass, clazz);
					} catch (IllegalStateException ex) {
						throw new FatalBeanException(ex.getMessage(), ex);
					}
				}
			}

			if (StringUtils.isNotBlank(cancellableKey)) {
				if (compensables.containsKey(cancellableKey)) {
					throw new FatalBeanException("The cancel bean cannot be a compensable service!");
				}
				Class<?> clazz = otherServiceMap.get(cancellableKey);
				if (clazz != null) {
					try {
						this.validateTransactionalAnnotation(interfaceClass, clazz);
					} catch (IllegalStateException ex) {
						throw new FatalBeanException(ex.getMessage(), ex);
					}
				}
			}
		}
	}

	private void validateTransactionalAnnotation(Class<?> interfaceClass, Class<?> clazz) throws IllegalStateException {
		Method[] methodArray = interfaceClass.getDeclaredMethods();
		for (int i = 0; i < methodArray.length; i++) {
			Method interfaceMethod = methodArray[i];
			String methodName = interfaceMethod.getName();
			Class<?>[] parameterTypeArray = interfaceMethod.getParameterTypes();
			Method method = null;
			try {
				method = clazz.getMethod(methodName, parameterTypeArray);
			} catch (NoSuchMethodException ex) {
				throw new IllegalStateException(ex);
			} catch (SecurityException ex) {
				throw new IllegalStateException(ex);
			}
			Transactional transactional = method.getAnnotation(Transactional.class);
			if (transactional == null) {
				throw new IllegalStateException("Compensable service must specific a Transactional annotation!");
			}
			Propagation propagation = transactional.propagation();
			if (Propagation.REQUIRED.equals(propagation)) {
				// ingore
			} else if (Propagation.SUPPORTS.equals(propagation)) {
				throw new IllegalStateException("Compensable service not support propagation level: SUPPORTS!");
			} else if (Propagation.MANDATORY.equals(propagation)) {
				// ignore
			} else if (Propagation.REQUIRES_NEW.equals(propagation)) {
				// ignore
			} else if (Propagation.NOT_SUPPORTED.equals(propagation)) {
				throw new IllegalStateException("Compensable service not support propagation level: NOT_SUPPORTED!");
			} else if (Propagation.NEVER.equals(propagation)) {
				throw new IllegalStateException("Compensable service not support propagation level: NEVER!");
			} else if (Propagation.NESTED.equals(propagation)) {
				throw new IllegalStateException("Compensable service not support propagation level: NESTED!");
			} else {
				throw new IllegalStateException(String.format("Compensable service not support propagation level: %s!",
						propagation.name()));
			}
		}
	}

	public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
		return bean;
	}

	public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
		if (TargetClassAware.class.isInstance(bean)) {
			// TargetClassAware tca = (TargetClassAware) bean;
			// Class<?> targetClass = tca.getTargetClass();
			// Compensable compensable = targetClass.getAnnotation(Compensable.class);
			//
			// if (compensable != null) {
			// ProxyFactoryBean pfb = new ProxyFactoryBean();
			// CompensableNativeHandler handler = new CompensableNativeHandler();
			// handler.setBeanName(beanName);
			// Object target = null;
			// ClassLoader classLoader = targetClass.getClassLoader();
			// Class<?>[] interfaces = targetClass.getInterfaces();
			//
			// if (java.lang.reflect.Proxy.isProxyClass(bean.getClass())) {
			// java.lang.reflect.InvocationHandler delegate = java.lang.reflect.Proxy.getInvocationHandler(bean);
			// handler.setDelegate(delegate);
			// target = java.lang.reflect.Proxy.newProxyInstance(classLoader, interfaces, handler);
			// } else if (net.sf.cglib.proxy.Proxy.isProxyClass(bean.getClass())) {
			// net.sf.cglib.proxy.InvocationHandler delegate = net.sf.cglib.proxy.Proxy.getInvocationHandler(bean);
			// handler.setDelegate(delegate);
			// target = net.sf.cglib.proxy.Proxy.newProxyInstance(classLoader, interfaces, handler);
			// } else if (org.springframework.cglib.proxy.Proxy.isProxyClass(bean.getClass())) {
			// org.springframework.cglib.proxy.InvocationHandler delegate = org.springframework.cglib.proxy.Proxy
			// .getInvocationHandler(bean);
			// handler.setDelegate(delegate);
			// target = org.springframework.cglib.proxy.Proxy.newProxyInstance(classLoader, interfaces, handler);
			// } else {
			// return bean;
			// }
			//
			// Class<?> interfaceClass = compensable.interfaceClass();
			// String confirmableKey = compensable.confirmableKey();
			// String cancellableKey = compensable.cancellableKey();
			//
			// if (interfaceClass.isInterface() == false) {
			// throw new IllegalStateException("Compensable's interfaceClass must be a interface.");
			// }
			//
			// handler.setTargetClass(targetClass);
			// handler.setInterfaceClass(interfaceClass);
			// handler.setConfirmableKey(confirmableKey);
			// handler.setCancellableKey(cancellableKey);
			// handler.setTransactionManager(this.transactionManager);
			//
			// pfb.setTarget(target);
			// pfb.setInterfaces(interfaces);
			//
			// return pfb;
			// }

		}

		// if (ByteTccStubObject.class.isInstance(bean)) {
		// ByteTccStubObject stub = (ByteTccStubObject) bean;
		// Class<?> interfaceClass = stub.getInterfaceClass();
		// this.serviceFactory.putServiceObject(beanName, interfaceClass, stub);
		// return bean;
		// } else if (ByteTccSkeletonObject.class.isInstance(bean)) {
		// ByteTccSkeletonObject skeleton = (ByteTccSkeletonObject) bean;
		// Class<?> interfaceClass = skeleton.getInterfaceClass();
		// skeleton.setApplicationContext(this.applicationContext);
		// this.serviceFactory.putServiceObject(beanName, interfaceClass, skeleton);
		// return bean;
		// }

		return bean;
	}

	public ApplicationContext getApplicationContext() {
		return applicationContext;
	}

	public void setApplicationContext(ApplicationContext applicationContext) {
		this.applicationContext = applicationContext;
	}

}
