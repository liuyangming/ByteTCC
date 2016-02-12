/**
 * Copyright 2014-2015 yangming.liu<liuyangming@gmail.com>.
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

import java.io.Serializable;
import java.lang.reflect.Method;

import org.bytesoft.bytetcc.CompensableInvocation;
import org.bytesoft.bytetcc.CompensableTransactionManager;

public class CompensableNativeHandler implements java.lang.reflect.InvocationHandler, net.sf.cglib.proxy.InvocationHandler,
		org.springframework.cglib.proxy.InvocationHandler {

	private Object delegate;
	private String beanName;
	private Class<?> targetClass;
	private Class<?> interfaceClass;
	private String confirmableKey;
	private String cancellableKey;

	private transient CompensableTransactionManager transactionManager;

	private void checkCurrentCompensable(Object proxy, Method method, Object[] args) throws IllegalAccessException {

		Class<?> declaringClass = method.getDeclaringClass();
		if (declaringClass.equals(this.interfaceClass)) {
			// ignore
		} else {
			throw new IllegalAccessException();
		}

	}

	private void checkSerialization(Object proxy, Method method, Object[] args) throws IllegalArgumentException {
		for (int i = 0; i < args.length; i++) {
			Object obj = args[i];
			if (Serializable.class.isInstance(obj) == false) {
				String format = "The param(index= %s, type: %s) of method(%s) cannot be serialize.";
				throw new IllegalArgumentException(String.format(format, i, obj.getClass().getName(), method));
			}
		}
	}

	public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {

		Class<?> declaring = method.getDeclaringClass();
		if (Object.class.equals(declaring)) {
			return this.handleInvocation(proxy, method, args);
		}

		try {
			this.checkCurrentCompensable(proxy, method, args);
		} catch (IllegalAccessException ex) {
			return this.handleInvocation(proxy, method, args);
		}

		this.checkSerialization(proxy, method, args);

		SpringCompensableInvocation invocation = new SpringCompensableInvocation();
		invocation.setMethod(method);
		invocation.setArgs(args);
		invocation.setConfirmableKey(this.confirmableKey);
		invocation.setCancellableKey(this.cancellableKey);
		invocation.setInterfaceClass(this.interfaceClass);

		CompensableInvocation original = null;
		try {
			original = this.transactionManager.beforeCompensableExecution(invocation);
			return this.handleInvocation(proxy, method, args);
		} finally {
			this.transactionManager.afterCompensableCompletion(original);
		}

	}

	public Object handleInvocation(Object proxy, Method method, Object[] args) throws Throwable {
		if (java.lang.reflect.InvocationHandler.class.isInstance(this.delegate)) {
			return ((java.lang.reflect.InvocationHandler) this.delegate).invoke(proxy, method, args);
		} else if (net.sf.cglib.proxy.InvocationHandler.class.isInstance(this.delegate)) {
			return ((net.sf.cglib.proxy.InvocationHandler) this.delegate).invoke(proxy, method, args);
		} else if (org.springframework.cglib.proxy.InvocationHandler.class.isInstance(this.delegate)) {
			return ((org.springframework.cglib.proxy.InvocationHandler) this.delegate).invoke(proxy, method, args);
		} else {
			throw new RuntimeException();
		}
	}

	public Object getDelegate() {
		return delegate;
	}

	public void setDelegate(Object delegate) {
		this.delegate = delegate;
	}

	public String getBeanName() {
		return beanName;
	}

	public void setBeanName(String beanName) {
		this.beanName = beanName;
	}

	public Class<?> getInterfaceClass() {
		return interfaceClass;
	}

	public void setInterfaceClass(Class<?> interfaceClass) {
		this.interfaceClass = interfaceClass;
	}

	public String getConfirmableKey() {
		return confirmableKey;
	}

	public void setConfirmableKey(String confirmableKey) {
		this.confirmableKey = confirmableKey;
	}

	public String getCancellableKey() {
		return cancellableKey;
	}

	public void setCancellableKey(String cancellableKey) {
		this.cancellableKey = cancellableKey;
	}

	public Class<?> getTargetClass() {
		return targetClass;
	}

	public void setTargetClass(Class<?> targetClass) {
		this.targetClass = targetClass;
	}

	public CompensableTransactionManager getTransactionManager() {
		return transactionManager;
	}

	public void setTransactionManager(CompensableTransactionManager transactionManager) {
		this.transactionManager = transactionManager;
	}

}
