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
package org.bytesoft.bytetcc.supports.spring.beans;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import net.sf.cglib.proxy.MethodInterceptor;
import net.sf.cglib.proxy.MethodProxy;

import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

public class ByteTccSkeletonInvocationHandler implements ByteTccSkeletonObject, InvocationHandler, MethodInterceptor {
	private Class<?> interfaceClass;
	private String serviceId;
	private Object target;
	private ApplicationContext applicationContext;

	public Object intercept(Object obj, Method method, Object[] args, MethodProxy proxy) throws Throwable {
		// System.out.printf("method: %s, args: %s, proxy: %s%n", method, Arrays.toString(args), proxy);
		return this.invoke(proxy, method, args);
	}

	public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {

		try {
			if (Object.class.equals(method.getDeclaringClass())) {
				return method.invoke(this, args);
			} else if (ByteTccSkeletonObject.class.equals(method.getDeclaringClass())) {
				return method.invoke(this, args);
			} else if (ApplicationContextAware.class.equals(method.getDeclaringClass())) {
				return method.invoke(this, args);
			}

			if (this.target == null) {
				this.target = this.applicationContext.getBean(this.serviceId);
			}

			return method.invoke(this.target, args);
		} catch (IllegalAccessException ex) {
			throw new RuntimeException(ex);
		} catch (InvocationTargetException ex) {
			throw ex.getTargetException();
		}

	}

	public String getTargetId() {
		return this.serviceId;
	}

	public Class<?> getInterfaceClass() {
		return interfaceClass;
	}

	public void setInterfaceClass(Class<?> interfaceClass) {
		this.interfaceClass = interfaceClass;
	}

	public String getServiceId() {
		return serviceId;
	}

	public void setServiceId(String serviceId) {
		this.serviceId = serviceId;
	}

	public Object getTarget() {
		return target;
	}

	public void setTarget(Object target) {
		this.target = target;
	}

	public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
		this.applicationContext = applicationContext;
	}

}
