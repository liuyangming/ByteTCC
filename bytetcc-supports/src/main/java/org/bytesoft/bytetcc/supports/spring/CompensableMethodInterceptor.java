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

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.apache.commons.lang3.StringUtils;
import org.bytesoft.bytetcc.supports.CompensableInvocationImpl;
import org.bytesoft.bytetcc.supports.CompensableSynchronization;
import org.bytesoft.bytetcc.supports.spring.aware.CompensableBeanNameAware;
import org.bytesoft.compensable.Compensable;
import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.CompensableCancel;
import org.bytesoft.compensable.CompensableConfirm;
import org.bytesoft.compensable.CompensableInvocation;
import org.bytesoft.compensable.CompensableInvocationRegistry;
import org.bytesoft.compensable.CompensableManager;
import org.bytesoft.compensable.CompensableTransaction;
import org.bytesoft.compensable.TransactionContext;
import org.bytesoft.compensable.aware.CompensableBeanFactoryAware;
import org.bytesoft.transaction.Transaction;
import org.bytesoft.transaction.TransactionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public class CompensableMethodInterceptor
		implements MethodInterceptor, CompensableSynchronization, ApplicationContextAware, CompensableBeanFactoryAware {
	static final Logger logger = LoggerFactory.getLogger(CompensableMethodInterceptor.class);

	private CompensableBeanFactory beanFactory;
	private ApplicationContext applicationContext;

	public void afterBegin(Transaction transaction, boolean createFlag) {
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();
		CompensableInvocationRegistry registry = CompensableInvocationRegistry.getInstance();

		CompensableInvocation invocation = registry.getCurrent();
		CompensableTransaction compensable = compensableManager.getCompensableTransactionQuietly();

		TransactionContext transactionContext = compensable.getTransactionContext();
		if (transactionContext.isCompensating()) {
			return;
		}

		compensable.registerCompensable(invocation);
	}

	public Object invoke(MethodInvocation mi) throws Throwable {
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();
		CompensableTransaction compensable = compensableManager.getCompensableTransactionQuietly();
		if (compensable != null && compensable.getTransactionContext() != null
				&& compensable.getTransactionContext().isCompensating()) {
			return mi.proceed();
		}

		String identifier = null;
		Object bean = mi.getThis();
		if (CompensableBeanNameAware.class.isInstance(bean)) {
			CompensableBeanNameAware config = (CompensableBeanNameAware) bean;
			identifier = config.getBeanName();
			if (StringUtils.isBlank(identifier)) {
				logger.error("BeanId(class= {}) should not be null!", bean.getClass().getName());
				throw new IllegalStateException(
						String.format("BeanId(class= %s) should not be null!", bean.getClass().getName()));
			}
		} else {
			String[] beanNameArray = this.applicationContext.getBeanNamesForType(bean.getClass());
			if (beanNameArray.length == 1) {
				identifier = beanNameArray[0];
			} else {
				logger.error("Class {} does not implement interface {}, and there are multiple bean definitions!",
						bean.getClass().getName(), CompensableBeanNameAware.class.getName());
				throw new IllegalStateException(
						String.format("Class %s does not implement interface %s, and there are multiple bean definitions!",
								bean.getClass().getName(), CompensableBeanNameAware.class.getName()));
			}
		}

		return this.execute(identifier, mi);
	}

	public Object execute(String identifier, MethodInvocation mi) throws Throwable {
		Transactional transactional = mi.getMethod().getAnnotation(Transactional.class);

		CompensableInvocationRegistry registry = CompensableInvocationRegistry.getInstance();
		Compensable annotation = mi.getMethod().getDeclaringClass().getAnnotation(Compensable.class);

		TransactionManager transactionManager = this.beanFactory.getTransactionManager();
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();

		boolean desociateRequired = false;
		try {
			CompensableInvocationImpl invocation = new CompensableInvocationImpl();
			Method method = mi.getMethod();
			invocation.setArgs(mi.getArguments());

			invocation.setIdentifier(identifier);
			invocation.setSimplified(annotation.simplified());

			Class<?> interfaceClass = annotation.interfaceClass();
			String methodName = method.getName();
			Class<?>[] parameterTypes = method.getParameterTypes();
			if (annotation.simplified()) {
				invocation.setMethod(method); // class-method

				Class<?> currentClazz = mi.getThis().getClass();
				Method[] methodArray = currentClazz.getDeclaredMethods();
				boolean confirmFlag = false;
				boolean cancelFlag = false;

				for (int i = 0; (confirmFlag == false || cancelFlag == false) && i < methodArray.length; i++) {
					Method element = methodArray[i];
					if (element.getAnnotation(CompensableConfirm.class) != null) {
						confirmFlag = true;
						invocation.setConfirmableKey(identifier);
					}
					if (element.getAnnotation(CompensableCancel.class) != null) {
						cancelFlag = true;
						invocation.setCancellableKey(identifier);
					}
				}
			} else {
				invocation.setMethod(interfaceClass.getMethod(methodName, parameterTypes));
				invocation.setConfirmableKey(annotation.confirmableKey());
				invocation.setCancellableKey(annotation.cancellableKey());
			}

			Transaction transaction = transactionManager.getTransactionQuietly();
			CompensableTransaction compensable = compensableManager.getCompensableTransactionQuietly();
			Transaction existingTxn = (compensable == null) ? null : compensable.getTransaction();

			if (transaction == null && existingTxn != null) {
				transaction = existingTxn;
				transactionManager.associateThread(existingTxn);
				desociateRequired = true;
			}

			if (transactional != null && compensable != null && transaction != null) {
				Propagation propagation = transactional == null ? null : transactional.propagation();
				if (propagation == null) {
					compensable.registerCompensable(invocation);
				} else if (Propagation.REQUIRED.equals(propagation)) {
					compensable.registerCompensable(invocation);
				} else if (Propagation.MANDATORY.equals(propagation)) {
					compensable.registerCompensable(invocation);
				} else if (Propagation.SUPPORTS.equals(propagation)) {
					compensable.registerCompensable(invocation);
				}
			}

			registry.register(invocation);
			return mi.proceed();
		} finally {
			registry.unRegister();

			if (desociateRequired) {
				transactionManager.desociateThread();
			} // end-if (associated)

		}
	}

	public void setBeanFactory(CompensableBeanFactory tbf) {
		this.beanFactory = tbf;
	}

	public CompensableBeanFactory getBeanFactory() {
		return beanFactory;
	}

	public ApplicationContext getApplicationContext() {
		return applicationContext;
	}

	public void setApplicationContext(ApplicationContext applicationContext) {
		this.applicationContext = applicationContext;
	}

}
