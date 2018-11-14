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

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;

public class CompensableFeignBeanPostProcessor implements BeanPostProcessor {
	static final String FEIGN_CLAZZ_NAME = "feign.ReflectiveFeign$FeignInvocationHandler";

	private volatile boolean statefully;

	public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
		return bean;
	}

	public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {

		if (Proxy.isProxyClass(bean.getClass()) == false) {
			return bean;
		}

		InvocationHandler handler = Proxy.getInvocationHandler(bean);

		if (StringUtils.equals(FEIGN_CLAZZ_NAME, handler.getClass().getName()) == false) {
			return bean;
		}

		CompensableFeignHandler feignHandler = new CompensableFeignHandler();
		feignHandler.setDelegate(handler);
		feignHandler.setStatefully(this.statefully);

		Class<?> clazz = bean.getClass();
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
