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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;

import org.bytesoft.compensable.CompensableCancel;
import org.bytesoft.compensable.CompensableConfirm;
import org.bytesoft.compensable.CompensableInvocation;
import org.bytesoft.compensable.ContainerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

public class SpringContainerContextImpl implements ContainerContext, ApplicationContextAware {
	static Logger logger = LoggerFactory.getLogger(SpringContainerContextImpl.class);

	private ApplicationContext applicationContext;

	public void confirm(CompensableInvocation invocation) throws RuntimeException {
		String identifier = (String) invocation.getIdentifier();
		String confirmableKey = invocation.getConfirmableKey();
		Method method = this.getCompensableMethod(invocation); // invocation.getMethod();
		Object[] args = invocation.getArgs();

		if (invocation.isSimplified()) {
			Object instance = this.applicationContext.getBean(identifier);
			this.confirmSimplified(method, instance, args);
		} else {
			Object instance = this.applicationContext.getBean(confirmableKey);
			this.confirmComplicated(method, instance, args);
		}
	}

	private void confirmSimplified(Method method, Object instance, Object[] args) throws RuntimeException {
		Class<?> clazz = instance.getClass();
		Class<?> targetClazz = method.getDeclaringClass();

		Method[] methodArray = clazz.getMethods();

		Method confirmable = null;
		for (int i = 0; confirmable == null && i < methodArray.length; i++) {
			Method element = methodArray[i];
			Class<?>[] paramTypes = element.getParameterTypes();
			Method targetMethod = null;
			try {
				targetMethod = targetClazz.getDeclaredMethod(element.getName(), paramTypes);
			} catch (NoSuchMethodException ex) {
				continue;
			}

			CompensableConfirm annotation = targetMethod.getAnnotation(CompensableConfirm.class);
			confirmable = annotation == null ? confirmable : element;
		}

		if (confirmable == null) {
			throw new RuntimeException("Not supported yet!");
		}

		try {
			confirmable.invoke(instance, args);
		} catch (InvocationTargetException itex) {
			throw new RuntimeException(itex.getTargetException());
		} catch (RuntimeException rex) {
			throw rex;
		} catch (Throwable throwable) {
			throw new RuntimeException(throwable);
		}

	}

	public void confirmComplicated(Method method, Object instance, Object[] args) throws RuntimeException {
		try {
			method.invoke(instance, args);
		} catch (InvocationTargetException itex) {
			throw new RuntimeException(itex.getTargetException());
		} catch (RuntimeException rex) {
			throw rex;
		} catch (Throwable throwable) {
			throw new RuntimeException(throwable);
		}
	}

	public void cancel(CompensableInvocation invocation) throws RuntimeException {
		String identifier = (String) invocation.getIdentifier();
		String cancellableKey = invocation.getCancellableKey();

		Method method = this.getCompensableMethod(invocation); // invocation.getMethod();
		Object[] args = invocation.getArgs();

		if (invocation.isSimplified()) {
			Object instance = this.applicationContext.getBean(identifier);
			this.cancelSimplified(method, instance, args);
		} else {
			Object instance = this.applicationContext.getBean(cancellableKey);
			this.cancelComplicated(method, instance, args);
		}

	}

	private void cancelSimplified(Method method, Object instance, Object[] args) throws RuntimeException {
		Class<?> clazz = instance.getClass();
		Class<?> targetClazz = method.getDeclaringClass();

		Method[] methodArray = clazz.getDeclaredMethods();

		Method cancellable = null;
		for (int i = 0; cancellable == null && i < methodArray.length; i++) {
			Method element = methodArray[i];
			Class<?>[] paramTypes = element.getParameterTypes();
			Method targetMethod = null;
			try {
				targetMethod = targetClazz.getDeclaredMethod(element.getName(), paramTypes);
			} catch (NoSuchMethodException ex) {
				continue;
			}

			CompensableCancel annotation = targetMethod.getAnnotation(CompensableCancel.class);
			cancellable = annotation == null ? cancellable : element;
		}

		if (cancellable == null) {
			throw new RuntimeException("Not supported yet!");
		}

		try {
			cancellable.invoke(instance, args);
		} catch (InvocationTargetException itex) {
			throw new RuntimeException(itex.getTargetException());
		} catch (RuntimeException rex) {
			throw rex;
		} catch (Throwable throwable) {
			throw new RuntimeException(throwable);
		}

	}

	public void cancelComplicated(Method method, Object instance, Object[] args) throws RuntimeException {
		try {
			method.invoke(instance, args);
		} catch (InvocationTargetException itex) {
			throw new RuntimeException(itex.getTargetException());
		} catch (RuntimeException rex) {
			throw rex;
		} catch (Throwable throwable) {
			throw new RuntimeException(throwable);
		}
	}

	private Method getCompensableMethod(CompensableInvocation invocation) {
		if (invocation.getMethod() == null) {
			this.initCompensableMethod(invocation);
		} // end-if (invocation.getMethod() == null)

		return invocation.getMethod();
	}

	private void initCompensableMethod(CompensableInvocation invocation) {
		String declaringClass = invocation.getDeclaringClass();
		String methodName = invocation.getMethodName();
		String[] parameterTypeArray = invocation.getParameterTypeArray();

		ClassLoader classLoader = Thread.currentThread().getContextClassLoader();

		Class<?> clazz = null;
		try {
			clazz = classLoader.loadClass(declaringClass);
		} catch (ClassNotFoundException ex) {
			throw new RuntimeException(String.format("Error occurred while loading class: %s", declaringClass), ex);
		}

		Method targetMethod = null;
		Class<?>[] parameterTypes = new Class<?>[parameterTypeArray == null ? 0 : parameterTypeArray.length];
		for (int i = 0; parameterTypeArray != null && i < parameterTypeArray.length; i++) {
			String className = parameterTypeArray[i];
			if (Double.TYPE.getName().equals(className)) {
				parameterTypes[i] = Double.TYPE;
			} else if (Long.TYPE.getName().equals(className)) {
				parameterTypes[i] = Long.TYPE;
			} else if (Integer.TYPE.getName().equals(className)) {
				parameterTypes[i] = Integer.TYPE;
			} else if (Float.TYPE.getName().equals(className)) {
				parameterTypes[i] = Float.TYPE;
			} else if (Short.TYPE.getName().equals(className)) {
				parameterTypes[i] = Short.TYPE;
			} else if (Character.TYPE.getName().equals(className)) {
				parameterTypes[i] = Character.TYPE;
			} else if (Boolean.TYPE.getName().equals(className)) {
				parameterTypes[i] = Boolean.TYPE;
			} else if (Byte.TYPE.getName().equals(className)) {
				parameterTypes[i] = Byte.TYPE;
			} else {
				try {
					parameterTypes[i] = Class.forName(className, false, classLoader); // classLoader.loadClass(className);
				} catch (ClassNotFoundException ex) {
					throw new RuntimeException(String.format("Error occurred while loading class: %s", declaringClass), ex);
				}
			}
		}

		try {
			targetMethod = clazz.getDeclaredMethod(methodName, parameterTypes);
		} catch (NoSuchMethodException ex) {
			throw new RuntimeException(String.format("Error occurred: class= %s, method= %s, parameters= %s" //
					, declaringClass, methodName, Arrays.toString(parameterTypeArray)), ex);
		} catch (SecurityException ex) {
			throw new RuntimeException(String.format("Error occurred: class= %s, method= %s, parameters= %s" //
					, declaringClass, methodName, Arrays.toString(parameterTypeArray)), ex);
		}

		invocation.setMethod(targetMethod);
	}

	public ApplicationContext getApplicationContext() {
		return applicationContext;
	}

	public void setApplicationContext(ApplicationContext applicationContext) {
		this.applicationContext = applicationContext;
	}
}
