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
		Method method = invocation.getMethod();
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

		Method method = invocation.getMethod();
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

	public ApplicationContext getApplicationContext() {
		return applicationContext;
	}

	public void setApplicationContext(ApplicationContext applicationContext) {
		this.applicationContext = applicationContext;
	}
}
