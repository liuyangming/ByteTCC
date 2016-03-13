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

import org.bytesoft.bytetcc.supports.CompensableInvocationImpl;
import org.bytesoft.compensable.CompensableInvocationRegistry;
import org.bytesoft.compensable.CompensableTransaction;
import org.bytesoft.transaction.TransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public class CompensableInvocationHandler implements java.lang.reflect.InvocationHandler, net.sf.cglib.proxy.InvocationHandler,
		org.springframework.cglib.proxy.InvocationHandler {

	private String beanName;
	private Object delegate;
	private Class<?> targetClass;
	private Class<?> interfaceClass;
	private String confirmableKey;
	private String cancellableKey;
	private TransactionManager transactionManager;

	public Object invoke(Object object, Method method, Object[] args) throws Throwable {

		CompensableInvocationRegistry registry = CompensableInvocationRegistry.getInstance();

		CompensableInvocationImpl invocation = new CompensableInvocationImpl();
		invocation.setMethod(method);
		invocation.setArgs(args);
		invocation.setCancellableKey(cancellableKey);
		invocation.setConfirmableKey(confirmableKey);
		invocation.setIdentifier(this.beanName);

		Method methodImpl = this.targetClass.getMethod(method.getName(), method.getParameterTypes());
		Transactional transactional = methodImpl.getAnnotation(Transactional.class);
		Propagation propagation = transactional.propagation();

		CompensableTransaction transaction = (CompensableTransaction) this.transactionManager.getTransactionQuietly();
		try {
			if (transaction == null) {
				registry.register(invocation);
			} else if (Propagation.REQUIRED.equals(propagation)) {
				transaction.registerCompensableInvocation(invocation);
			} else if (Propagation.REQUIRES_NEW.equals(propagation)) {
				registry.register(invocation);
			} else if (Propagation.MANDATORY.equals(propagation)) {
				transaction.registerCompensableInvocation(invocation);
			}
			return this.doInvoke(object, method, args);
		} finally {
			if (transaction == null) {
				registry.unegister();
			} else if (Propagation.REQUIRES_NEW.equals(propagation)) {
				registry.unegister();
			}
		}
	}

	private Object doInvoke(Object object, Method method, Object[] args) throws Throwable {
		if (java.lang.reflect.InvocationHandler.class.isInstance(this.delegate)) {
			return ((java.lang.reflect.InvocationHandler) this.delegate).invoke(object, method, args);
		} else if (net.sf.cglib.proxy.InvocationHandler.class.isInstance(this.delegate)) {
			return ((net.sf.cglib.proxy.InvocationHandler) this.delegate).invoke(object, method, args);
		} else if (org.springframework.cglib.proxy.InvocationHandler.class.isInstance(this.delegate)) {
			return ((org.springframework.cglib.proxy.InvocationHandler) this.delegate).invoke(object, method, args);
		} else {
			throw new IllegalStateException("Invalid invocation!");
		}
	}

	public String getBeanName() {
		return beanName;
	}

	public void setBeanName(String beanName) {
		this.beanName = beanName;
	}

	public Object getDelegate() {
		return delegate;
	}

	public void setDelegate(Object delegate) {
		this.delegate = delegate;
	}

	public Class<?> getTargetClass() {
		return targetClass;
	}

	public void setTargetClass(Class<?> targetClass) {
		this.targetClass = targetClass;
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

	public TransactionManager getTransactionManager() {
		return transactionManager;
	}

	public void setTransactionManager(TransactionManager transactionManager) {
		this.transactionManager = transactionManager;
	}

}
