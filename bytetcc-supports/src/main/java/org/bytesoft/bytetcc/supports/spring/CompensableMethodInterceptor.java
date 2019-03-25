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

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Method;

import org.aopalliance.intercept.Joinpoint;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.apache.commons.lang3.StringUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
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
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@org.aspectj.lang.annotation.Aspect
public class CompensableMethodInterceptor
		implements MethodInterceptor, CompensableSynchronization, ApplicationContextAware, CompensableBeanFactoryAware {
	static final Logger logger = LoggerFactory.getLogger(CompensableMethodInterceptor.class);

	@javax.inject.Inject
	private CompensableBeanFactory beanFactory;
	private ApplicationContext applicationContext;

	public void afterBegin(Transaction transaction, boolean createFlag) {
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();
		CompensableInvocationRegistry registry = CompensableInvocationRegistry.getInstance();

		CompensableInvocation invocation = registry.getCurrent();
		CompensableTransaction compensable = compensableManager.getCompensableTransactionQuietly();

		TransactionContext transactionContext = compensable.getTransactionContext();
		if (invocation == null) /* non-Compensable operation in CompensableService */ {
			return;
		} else if (invocation.isEnlisted()) {
			return;
		} else if (transactionContext.isCompensating()) {
			return;
		}

		compensable.registerCompensable(invocation);
	}

	// <!-- <aop:config proxy-target-class="true"> -->
	// <!-- <aop:pointcut id="compensableMethodPointcut" expression="@within(org.bytesoft.compensable.Compensable)" /> -->
	// <!-- <aop:advisor advice-ref="compensableMethodInterceptor" pointcut-ref="compensableMethodPointcut" /> -->
	// <!-- </aop:config> -->
	@org.aspectj.lang.annotation.Around("@within(org.bytesoft.compensable.Compensable)")
	public Object invoke(final ProceedingJoinPoint pjp) throws Throwable {
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();
		CompensableTransaction compensable = compensableManager.getCompensableTransactionQuietly();
		if (compensable != null && compensable.getTransactionContext() != null
				&& compensable.getTransactionContext().isCompensating()) {
			return pjp.proceed();
		}

		Object bean = pjp.getThis();
		String identifier = this.getBeanName(bean);

		MethodSignature signature = (MethodSignature) pjp.getSignature();
		Method method = signature.getMethod();
		Object[] args = pjp.getArgs();
		AspectJoinpoint point = new AspectJoinpoint(pjp);
		return this.execute(identifier, method, args, point);
	}

	public Object invoke(MethodInvocation mi) throws Throwable {
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();
		CompensableTransaction compensable = compensableManager.getCompensableTransactionQuietly();
		if (compensable != null && compensable.getTransactionContext() != null
				&& compensable.getTransactionContext().isCompensating()) {
			return mi.proceed();
		}

		Object bean = mi.getThis();
		String identifier = this.getBeanName(bean);

		Method method = mi.getMethod();
		Object[] args = mi.getArguments();
		return this.execute(identifier, method, args, mi);
	}

	public Object execute(String identifier, Method method, Object[] args, Joinpoint point) throws Throwable {
		CompensableInvocationRegistry registry = CompensableInvocationRegistry.getInstance();
		TransactionManager transactionManager = this.beanFactory.getTransactionManager();
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();

		Compensable annotation = method.getDeclaringClass().getAnnotation(Compensable.class);
		Class<?> interfaceClass = annotation.interfaceClass();
		String methodName = method.getName();
		Class<?>[] parameterTypes = method.getParameterTypes();

		Method interfaceMethod = null;
		try {
			interfaceMethod = interfaceClass.getMethod(methodName, parameterTypes);
		} catch (NoSuchMethodException ex) {
			logger.warn("Current compensable-service {} is invoking a non-TCC operation!", method);
			return point.proceed(); // ignore
		}

		Transactional clazzAnnotation = method.getDeclaringClass().getAnnotation(Transactional.class);
		Transactional methodAnnotation = method.getAnnotation(Transactional.class);
		Transactional transactional = methodAnnotation == null ? clazzAnnotation : methodAnnotation;
		if (transactional == null) {
			throw new IllegalStateException(
					String.format("Compensable-service(%s) does not have a Transactional annotation!", method));
		}

		CompensableInvocation invocation = null;
		if (annotation.simplified()) {
			invocation = this.getCompensableInvocation(identifier, method, args, annotation, point.getThis());
		} else {
			invocation = this.getCompensableInvocation(identifier, interfaceMethod, args, annotation);
		}

		Transaction transaction = transactionManager.getTransactionQuietly();
		CompensableTransaction compensable = compensableManager.getCompensableTransactionQuietly();
		Transaction existingTxn = (compensable == null) ? null : compensable.getTransaction();

		boolean desociateRequired = false;
		try {
			if (transaction == null && existingTxn != null) {
				transaction = existingTxn;
				transactionManager.associateThread(existingTxn);
				desociateRequired = true;
			}

			if (transaction != null && compensable == null) {
				logger.warn("Compensable-service {} is participanting in a non-TCC transaction which was created at:", method,
						transaction.getCreatedAt());
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
			return point.proceed();
		} finally {
			registry.unRegister();

			if (compensable != null && invocation != null && invocation.isEnlisted()) {
				compensable.completeCompensable(invocation);
			} // end-if (compensable != null && invocation != null && invocation.isEnlisted())

			if (desociateRequired) {
				transactionManager.desociateThread();
			} // end-if (desociateRequired)

		}
	}

	/* simplified. */
	private CompensableInvocation getCompensableInvocation(String identifier, Method method, Object[] args,
			Compensable annotation, Object target) {
		CompensableInvocationImpl invocation = new CompensableInvocationImpl();
		invocation.setArgs(args);

		invocation.setIdentifier(identifier);
		invocation.setSimplified(annotation.simplified());

		invocation.setMethod(method); // class-method

		Class<?> currentClazz = AopUtils.getTargetClass(target);

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

		return invocation;
	}

	/* default. */
	private CompensableInvocation getCompensableInvocation(String identifier, Method interfaceMethod, Object[] args,
			Compensable annotation) {
		CompensableInvocationImpl invocation = new CompensableInvocationImpl();
		invocation.setArgs(args);

		invocation.setIdentifier(identifier);
		invocation.setSimplified(annotation.simplified());

		invocation.setMethod(interfaceMethod);
		invocation.setConfirmableKey(annotation.confirmableKey());
		invocation.setCancellableKey(annotation.cancellableKey());

		return invocation;
	}

	private String getBeanName(Object bean) throws IllegalStateException {
		String identifier = null;
		if (CompensableBeanNameAware.class.isInstance(bean)) {
			CompensableBeanNameAware config = (CompensableBeanNameAware) bean;
			identifier = config.getBeanName();
			if (StringUtils.isBlank(identifier)) {
				logger.error("BeanId(class= {}) should not be null!", bean.getClass().getName());
				throw new IllegalStateException(
						String.format("BeanId(class= %s) should not be null!", bean.getClass().getName()));
			}
		} else {
			Class<?> targetClass = AopUtils.getTargetClass(bean);

			String[] beanNameArray = this.applicationContext.getBeanNamesForType(targetClass);
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
		return identifier;
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

	static class AspectJoinpoint implements Joinpoint {
		private final ProceedingJoinPoint delegate;

		public AspectJoinpoint(ProceedingJoinPoint joinPoint) {
			this.delegate = joinPoint;
		}

		public Object proceed() throws Throwable {
			return this.delegate.proceed();
		}

		public Object getThis() {
			return this.delegate.getThis();
		}

		public AccessibleObject getStaticPart() {
			throw new IllegalStateException("Not supported yet!");
		}
	}

}
