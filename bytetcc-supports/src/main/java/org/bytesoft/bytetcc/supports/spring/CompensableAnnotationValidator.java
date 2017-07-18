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
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.bytesoft.compensable.Compensable;
import org.bytesoft.compensable.CompensableCancel;
import org.bytesoft.compensable.CompensableConfirm;
import org.bytesoft.compensable.RemotingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.FatalBeanException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public class CompensableAnnotationValidator implements BeanFactoryPostProcessor {
	static final Logger logger = LoggerFactory.getLogger(CompensableAnnotationValidator.class);

	public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
		Map<String, Class<?>> otherServiceMap = new HashMap<String, Class<?>>();
		Map<String, Compensable> compensables = new HashMap<String, Compensable>();

		ClassLoader cl = Thread.currentThread().getContextClassLoader();
		String[] beanNameArray = beanFactory.getBeanDefinitionNames();
		for (int i = 0; beanNameArray != null && i < beanNameArray.length; i++) {
			String beanName = beanNameArray[i];
			BeanDefinition beanDef = beanFactory.getBeanDefinition(beanName);
			String className = beanDef.getBeanClassName();
			Class<?> clazz = null;

			try {
				clazz = cl.loadClass(className);
			} catch (Exception ex) {
				logger.debug("Cannot load class {}, beanId= {}!", className, beanName, ex);
				continue;
			}

			Compensable compensable = null;
			try {
				compensable = clazz.getAnnotation(Compensable.class);
			} catch (RuntimeException rex) {
				logger.warn("Error occurred while getting @Compensable annotation, class= {}!", clazz, rex);
			}

			if (compensable == null) {
				otherServiceMap.put(beanName, clazz);
				continue;
			} else {
				compensables.put(beanName, compensable);
			}

			try {
				Class<?> interfaceClass = compensable.interfaceClass();
				if (interfaceClass.isInterface() == false) {
					throw new IllegalStateException("Compensable's interfaceClass must be a interface.");
				}
				Method[] methodArray = interfaceClass.getDeclaredMethods();
				for (int j = 0; j < methodArray.length; j++) {
					Method interfaceMethod = methodArray[j];
					Method method = clazz.getMethod(interfaceMethod.getName(), interfaceMethod.getParameterTypes());
					this.validateSimplifiedCompensable(method, clazz);
					this.validateDeclaredRemotingException(method, clazz);
					this.validateTransactionalPropagation(method, clazz);
				}
			} catch (IllegalStateException ex) {
				throw new FatalBeanException(ex.getMessage(), ex);
			} catch (NoSuchMethodException ex) {
				throw new FatalBeanException(ex.getMessage(), ex);
			} catch (SecurityException ex) {
				throw new FatalBeanException(ex.getMessage(), ex);
			} catch (RuntimeException ex) {
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
					throw new FatalBeanException(
							String.format("The confirm bean(id= %s) cannot be a compensable service!", confirmableKey));
				}
				Class<?> clazz = otherServiceMap.get(confirmableKey);
				if (clazz == null) {
					throw new IllegalStateException(String.format("The confirm bean(id= %s) is not exists!", confirmableKey));
				}

				try {
					Method[] methodArray = interfaceClass.getDeclaredMethods();
					for (int j = 0; j < methodArray.length; j++) {
						Method interfaceMethod = methodArray[j];
						Method method = clazz.getMethod(interfaceMethod.getName(), interfaceMethod.getParameterTypes());
						this.validateDeclaredRemotingException(method, clazz);
						this.validateTransactionalPropagation(method, clazz);
						this.validateTransactionalRollbackFor(method, clazz, confirmableKey);
					}
				} catch (IllegalStateException ex) {
					throw new FatalBeanException(ex.getMessage(), ex);
				} catch (NoSuchMethodException ex) {
					throw new FatalBeanException(ex.getMessage(), ex);
				} catch (SecurityException ex) {
					throw new FatalBeanException(ex.getMessage(), ex);
				}
			}

			if (StringUtils.isNotBlank(cancellableKey)) {
				if (compensables.containsKey(cancellableKey)) {
					throw new FatalBeanException(
							String.format("The cancel bean(id= %s) cannot be a compensable service!", confirmableKey));
				}
				Class<?> clazz = otherServiceMap.get(cancellableKey);
				if (clazz == null) {
					throw new IllegalStateException(String.format("The cancel bean(id= %s) is not exists!", cancellableKey));
				}

				try {
					Method[] methodArray = interfaceClass.getDeclaredMethods();
					for (int j = 0; j < methodArray.length; j++) {
						Method interfaceMethod = methodArray[j];
						Method method = clazz.getMethod(interfaceMethod.getName(), interfaceMethod.getParameterTypes());
						this.validateDeclaredRemotingException(method, clazz);
						this.validateTransactionalPropagation(method, clazz);
						this.validateTransactionalRollbackFor(method, clazz, cancellableKey);
					}
				} catch (IllegalStateException ex) {
					throw new FatalBeanException(ex.getMessage(), ex);
				} catch (NoSuchMethodException ex) {
					throw new FatalBeanException(ex.getMessage(), ex);
				} catch (SecurityException ex) {
					throw new FatalBeanException(ex.getMessage(), ex);
				}

			}
		}
	}

	private void validateSimplifiedCompensable(Method method, Class<?> clazz) throws IllegalStateException {
		Compensable compensable = clazz.getAnnotation(Compensable.class);
		Class<?> interfaceClass = compensable.interfaceClass();
		Method[] methods = interfaceClass.getDeclaredMethods();
		if (compensable.simplified() == false) {
			return;
		} else if (method.getAnnotation(CompensableConfirm.class) != null) {
			throw new FatalBeanException(
					String.format("The try method(%s) can not be the same as the confirm method!", method));
		} else if (method.getAnnotation(CompensableCancel.class) != null) {
			throw new FatalBeanException(String.format("The try method(%s) can not be the same as the cancel method!", method));
		} else if (methods != null && methods.length > 1) {
			throw new FatalBeanException(String.format(
					"The interface bound by @Compensable(simplified= true) supports only one method, class= %s!", clazz));
		}

		Class<?>[] parameterTypes = method.getParameterTypes();
		Method[] methodArray = clazz.getDeclaredMethods();

		CompensableConfirm confirmable = null;
		CompensableCancel cancellable = null;
		for (int i = 0; i < methodArray.length; i++) {
			Method element = methodArray[i];
			Class<?>[] paramTypes = element.getParameterTypes();
			CompensableConfirm confirm = element.getAnnotation(CompensableConfirm.class);
			CompensableCancel cancel = element.getAnnotation(CompensableCancel.class);
			if (confirm == null && cancel == null) {
				continue;
			} else if (Arrays.equals(parameterTypes, paramTypes) == false) {
				throw new FatalBeanException(
						String.format("The parameter types of confirm/cancel method({}) is different from the try method({})!",
								element, method));
			} else if (confirm != null) {
				if (confirmable != null) {
					throw new FatalBeanException(
							String.format("There are more than one confirm method specified, class= %s!", clazz));
				} else {
					confirmable = confirm;
				}
			} else if (cancel != null) {
				if (cancellable != null) {
					throw new FatalBeanException(
							String.format("There are more than one cancel method specified, class= %s!", clazz));
				} else {
					cancellable = cancel;
				}
			}

		}

	}

	private void validateDeclaredRemotingException(Method method, Class<?> clazz) throws IllegalStateException {
		Class<?>[] exceptionTypeArray = method.getExceptionTypes();

		boolean located = false;
		for (int i = 0; i < exceptionTypeArray.length; i++) {
			Class<?> exceptionType = exceptionTypeArray[i];
			if (RemotingException.class.isAssignableFrom(exceptionType)) {
				located = true;
				break;
			}
		}

		if (located) {
			throw new FatalBeanException(String.format(
					"The method(%s) shouldn't be declared to throw a remote exception: org.bytesoft.compensable.RemotingException!",
					method));
		}

	}

	private void validateTransactionalPropagation(Method method, Class<?> clazz) throws IllegalStateException {
		Transactional transactional = method.getAnnotation(Transactional.class);
		if (transactional == null) {
			Class<?> declaringClass = method.getDeclaringClass();
			transactional = declaringClass.getAnnotation(Transactional.class);
		}

		if (transactional == null) {
			throw new IllegalStateException(String.format("Method(%s) must be specificed a Transactional annotation!", method));
		}
		Propagation propagation = transactional.propagation();
		if (Propagation.REQUIRED.equals(propagation) == false //
				&& Propagation.MANDATORY.equals(propagation) == false //
				&& Propagation.REQUIRES_NEW.equals(propagation) == false) {
			throw new IllegalStateException(
					String.format("Method(%s) not support propagation level: %s!", method, propagation.name()));
		}
	}

	private void validateTransactionalRollbackFor(Method method, Class<?> clazz, String beanName) throws IllegalStateException {
		Transactional transactional = method.getAnnotation(Transactional.class);
		if (transactional == null) {
			Class<?> declaringClass = method.getDeclaringClass();
			transactional = declaringClass.getAnnotation(Transactional.class);
		}

		if (transactional == null) {
			throw new IllegalStateException(String.format("Method(%s) must be specificed a Transactional annotation!", method));
		}

		String[] rollbackForClassNameArray = transactional.rollbackForClassName();
		if (rollbackForClassNameArray != null && rollbackForClassNameArray.length > 0) {
			throw new IllegalStateException(String.format(
					"The transactional annotation on the confirm/cancel class does not support the property rollbackForClassName yet(beanId= %s)!",
					beanName));
		}

		Class<?>[] rollErrorArray = transactional.rollbackFor();

		Class<?>[] errorTypeArray = method.getExceptionTypes();
		for (int j = 0; errorTypeArray != null && j < errorTypeArray.length; j++) {
			Class<?> errorType = errorTypeArray[j];
			if (RuntimeException.class.isAssignableFrom(errorType)) {
				continue;
			}

			boolean matched = false;
			for (int k = 0; rollErrorArray != null && k < rollErrorArray.length; k++) {
				Class<?> rollbackError = rollErrorArray[k];
				if (rollbackError.isAssignableFrom(errorType)) {
					matched = true;
					break;
				}
			}

			if (matched == false) {
				throw new IllegalStateException(
						String.format("The value of Transactional.rollbackFor annotated on method(%s) must includes %s!",
								method, errorType.getName()));
			}
		}
	}

}
