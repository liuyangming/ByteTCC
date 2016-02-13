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

import javax.transaction.xa.XAResource;

import org.bytesoft.byterpc.svc.ServiceFactory;
import org.bytesoft.bytetcc.Compensable;
import org.bytesoft.compensable.CompensableManager;
import org.springframework.aop.TargetClassAware;
import org.springframework.aop.framework.ProxyFactoryBean;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

public class CompensablePostProcessor implements BeanFactoryPostProcessor, BeanPostProcessor, ApplicationContextAware {

	private ApplicationContext applicationContext;
	private CompensableManager transactionManager;
	private ServiceFactory serviceFactory;
	private XAResource transactionSkeleton;

	public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
		this.serviceFactory.putServiceObject(XAResource.class.getName(), XAResource.class, this.transactionSkeleton);
	}

	public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
		return bean;
	}

	public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
		if (TargetClassAware.class.isInstance(bean)) {
			TargetClassAware tca = (TargetClassAware) bean;
			Class<?> targetClass = tca.getTargetClass();
			Compensable compensable = targetClass.getAnnotation(Compensable.class);

			if (compensable != null) {
				ProxyFactoryBean pfb = new ProxyFactoryBean();
				CompensableNativeHandler handler = new CompensableNativeHandler();
				handler.setBeanName(beanName);
				Object target = null;
				ClassLoader classLoader = targetClass.getClassLoader();
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

				if (interfaceClass.isInterface() == false) {
					throw new IllegalStateException("Compensable's interfaceClass must be a interface.");
				}

				handler.setTargetClass(targetClass);
				handler.setInterfaceClass(interfaceClass);
				handler.setConfirmableKey(confirmableKey);
				handler.setCancellableKey(cancellableKey);
				handler.setTransactionManager(this.transactionManager);

				pfb.setTarget(target);
				pfb.setInterfaces(interfaces);

				return pfb;
			}

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

	public CompensableManager getTransactionManager() {
		return transactionManager;
	}

	public void setTransactionManager(CompensableManager transactionManager) {
		this.transactionManager = transactionManager;
	}

	public ServiceFactory getServiceFactory() {
		return serviceFactory;
	}

	public void setServiceFactory(ServiceFactory serviceFactory) {
		this.serviceFactory = serviceFactory;
	}

	public XAResource getTransactionSkeleton() {
		return transactionSkeleton;
	}

	public void setTransactionSkeleton(XAResource transactionSkeleton) {
		this.transactionSkeleton = transactionSkeleton;
	}

}
