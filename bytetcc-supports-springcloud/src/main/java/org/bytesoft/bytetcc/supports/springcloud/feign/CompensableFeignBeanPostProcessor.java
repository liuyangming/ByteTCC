/**
 * Copyright 2014-2018 yangming.liu<bytefox@126.com>.
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
package org.bytesoft.bytetcc.supports.springcloud.feign;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

import org.apache.commons.lang3.StringUtils;
import org.springframework.aop.TargetSource;
import org.springframework.aop.target.SingletonTargetSource;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.cloud.openfeign.FeignClient;

public class CompensableFeignBeanPostProcessor implements BeanPostProcessor, InitializingBean {
	static final String FEIGN_CLAZZ_NAME = "feign.ReflectiveFeign$FeignInvocationHandler";

	private Field singletonTargetSourceTargetField = null;

	private volatile boolean statefully;

	public void afterPropertiesSet() throws Exception {
		Field field = SingletonTargetSource.class.getDeclaredField("target");
		field.setAccessible(true);
		this.singletonTargetSourceTargetField = field;
	}

	public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
		return bean;
	}

	public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
		if (Proxy.isProxyClass(bean.getClass()) == false) {
			return bean;
		}

		TargetSource targetSource = null;
		Object object = bean;
		if (org.springframework.aop.framework.Advised.class.isInstance(bean)) {
			org.springframework.aop.framework.Advised advised = (org.springframework.aop.framework.Advised) bean;

			Class<?>[] interfaces = advised.getProxiedInterfaces();

			for (int i = 0; i < interfaces.length; i++) {
				Class<?> intf = interfaces[i];
				if (intf.getAnnotation(FeignClient.class) != null) {
					targetSource = advised.getTargetSource();
					try {
						object = targetSource.getTarget();
						break;
					} catch (Exception error) {
						throw new IllegalStateException();
					}
				} // end-if (intf.getAnnotation(FeignClient.class) != null)
			} // end-for (int i = 0; i < interfaces.length; i++)
		} // end-if (org.springframework.aop.framework.Advised.class.isInstance(bean))

		InvocationHandler handler = Proxy.getInvocationHandler(object);

		if (targetSource == null //
				&& StringUtils.equals(FEIGN_CLAZZ_NAME, handler.getClass().getName()) == false) {
			return bean;
		}

		Object proxied = this.createProxiedObject(object);
		if (targetSource == null) {
			return proxied;
		}

		if (SingletonTargetSource.class.isInstance(targetSource)) {
			try {
				this.singletonTargetSourceTargetField.set(targetSource, proxied);
			} catch (IllegalArgumentException error) {
				throw new IllegalStateException("Error occurred!");
			} catch (IllegalAccessException error) {
				throw new IllegalStateException("Error occurred!");
			}
		} else {
			throw new IllegalStateException("Not supported yet!");
		}

		return bean;
	}

	private Object createProxiedObject(Object origin) {
		InvocationHandler handler = Proxy.getInvocationHandler(origin);

		CompensableFeignHandler feignHandler = new CompensableFeignHandler();
		feignHandler.setDelegate(handler);
		feignHandler.setStatefully(this.statefully);

		Class<?> clazz = origin.getClass();
		Class<?>[] interfaces = clazz.getInterfaces();
		ClassLoader loader = clazz.getClassLoader();
		return Proxy.newProxyInstance(loader, interfaces, feignHandler);
	}

	public boolean isStatefully() {
		return statefully;
	}

	public void setStatefully(boolean statefully) {
		this.statefully = statefully;
	}

}
