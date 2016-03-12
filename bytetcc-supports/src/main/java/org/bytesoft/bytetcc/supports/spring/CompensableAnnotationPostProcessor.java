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

import org.bytesoft.compensable.Compensable;
import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.aware.CompensableBeanFactoryAware;
import org.bytesoft.transaction.TransactionManager;
import org.springframework.aop.TargetClassAware;
import org.springframework.aop.framework.ProxyFactoryBean;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

public class CompensableAnnotationPostProcessor implements BeanPostProcessor, ApplicationContextAware,
		CompensableBeanFactoryAware {

	private CompensableBeanFactory beanFactory;
	private ApplicationContext applicationContext;

	public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
		return bean;
	}

	public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
		if (TargetClassAware.class.isInstance(bean)) {
			TargetClassAware tca = (TargetClassAware) bean;
			Class<?> targetClass = tca.getTargetClass();
			Compensable compensable = targetClass.getAnnotation(Compensable.class);
			if (compensable == null) {
				return bean;
			}

			ProxyFactoryBean pfb = new ProxyFactoryBean();
			CompensableInvocationHandler handler = new CompensableInvocationHandler();
			handler.setBeanName(beanName);
			Object target = null;
			ClassLoader classLoader = bean.getClass().getClassLoader();
			Class<?>[] interfaces = targetClass.getInterfaces();

			if (java.lang.reflect.Proxy.isProxyClass(bean.getClass())) {
				java.lang.reflect.InvocationHandler delegate = java.lang.reflect.Proxy.getInvocationHandler(bean);
				handler.setDelegate(delegate);
				target = java.lang.reflect.Proxy.newProxyInstance(classLoader, interfaces, handler);
			} else if (net.sf.cglib.proxy.Proxy.isProxyClass(bean.getClass())) {
				net.sf.cglib.proxy.InvocationHandler delegate = net.sf.cglib.proxy.Proxy.getInvocationHandler(bean);
				handler.setDelegate(delegate);
				target = net.sf.cglib.proxy.Proxy.newProxyInstance(classLoader, interfaces, handler);
			} else if (org.springframework.cglib.proxy.Proxy.isProxyClass(bean.getClass())) {
				org.springframework.cglib.proxy.InvocationHandler delegate = org.springframework.cglib.proxy.Proxy
						.getInvocationHandler(bean);
				handler.setDelegate(delegate);
				target = org.springframework.cglib.proxy.Proxy.newProxyInstance(classLoader, interfaces, handler);
			} else {
				return bean;
			}

			Class<?> interfaceClass = compensable.interfaceClass();
			String confirmableKey = compensable.confirmableKey();
			String cancellableKey = compensable.cancellableKey();

			TransactionManager transactionManager = this.beanFactory.getTransactionManager();

			handler.setTargetClass(targetClass);
			handler.setInterfaceClass(interfaceClass);
			handler.setConfirmableKey(confirmableKey);
			handler.setCancellableKey(cancellableKey);
			handler.setTransactionManager(transactionManager);

			pfb.setTarget(target);
			pfb.setInterfaces(interfaces);

			return pfb;

		}

		return bean;
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
