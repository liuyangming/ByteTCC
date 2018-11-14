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
package org.bytesoft.bytetcc.supports.springcloud.hystrix;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Iterator;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;

import com.netflix.hystrix.HystrixCommand.Setter;
import com.netflix.hystrix.HystrixCommandGroupKey;
import com.netflix.hystrix.HystrixCommandKey;

import feign.InvocationHandlerFactory.MethodHandler;
import feign.Target;
import feign.hystrix.FallbackFactory;

public class CompensableHystrixBeanPostProcessor implements BeanPostProcessor {
	static final String HYSTRIX_COMMAND_NAME = "CompensableHystrixInvocationHandler#invoke(CompensableHystrixInvocation)";
	static final String HYSTRIX_INVOKER_NAME = "invoke";

	static final String HYSTRIX_FIELD_CONSTANT = "constant";
	static final String HYSTRIX_FIELD_TARGET = "target";
	static final String HYSTRIX_FIELD_FACTORY = "fallbackFactory";
	static final String HYSTRIX_FIELD_FALLBACK = "fallbackMethodMap";
	static final String HYSTRIX_FIELD_DISPATH = "dispatch";
	static final String HYSTRIX_FIELD_SETTERS = "setterMethodMap";
	static final String HYSTRIX_SETTER_GRPKEY = "groupKey";
	static final String HYSTRIX_CLAZZ_NAME = "feign.hystrix.HystrixInvocationHandler";

	private volatile boolean statefully;

	public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
		return bean;
	}

	@SuppressWarnings("unchecked")
	public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {

		if (Proxy.isProxyClass(bean.getClass()) == false) {
			return bean;
		}

		InvocationHandler handler = Proxy.getInvocationHandler(bean);

		if (StringUtils.equals(HYSTRIX_CLAZZ_NAME, handler.getClass().getName()) == false) {
			return bean;
		}

		CompensableHystrixFeignHandler feignHandler = new CompensableHystrixFeignHandler();
		feignHandler.setDelegate(handler);

		Class<?> clazz = bean.getClass();
		Class<?>[] interfaces = clazz.getInterfaces();
		ClassLoader loader = clazz.getClassLoader();

		try {
			Field dispatchField = handler.getClass().getDeclaredField(HYSTRIX_FIELD_DISPATH);
			dispatchField.setAccessible(true);
			Map<Method, MethodHandler> dispatch = (Map<Method, MethodHandler>) dispatchField.get(handler);

			Field setterField = handler.getClass().getDeclaredField(HYSTRIX_FIELD_SETTERS);
			setterField.setAccessible(true);
			Map<Method, Setter> setterMap = (Map<Method, Setter>) setterField.get(handler);

			Field groupKeyField = Setter.class.getDeclaredField(HYSTRIX_SETTER_GRPKEY);
			groupKeyField.setAccessible(true);

			Field fallbackField = handler.getClass().getDeclaredField(HYSTRIX_FIELD_FALLBACK);
			fallbackField.setAccessible(true);
			Map<Method, Method> fallbackMap = (Map<Method, Method>) fallbackField.get(handler);

			Field targetField = handler.getClass().getDeclaredField(HYSTRIX_FIELD_TARGET);
			targetField.setAccessible(true);
			Target<?> target = (Target<?>) targetField.get(handler);

			Field factoryField = handler.getClass().getDeclaredField(HYSTRIX_FIELD_FACTORY);
			factoryField.setAccessible(true);
			FallbackFactory<?> factory = (FallbackFactory<?>) factoryField.get(handler);
			if (factory != null) {
				if (FallbackFactory.Default.class.isInstance(factory)) {
					Field constantField = FallbackFactory.Default.class.getDeclaredField(HYSTRIX_FIELD_CONSTANT);
					constantField.setAccessible(true);
					Object constant = constantField.get(factory);
					CompensableHystrixFallbackHandler fallback = new CompensableHystrixFallbackHandler(constant);
					Object proxy = Proxy.newProxyInstance(constant.getClass().getClassLoader(),
							new Class<?>[] { CompensableHystrixInvocationHandler.class, target.type() }, fallback);
					constantField.set(factory, proxy);
				} else {
					CompensableHystrixFallbackFactoryHandler factoryHandler = new CompensableHystrixFallbackFactoryHandler(
							factory, target.type());
					FallbackFactory<?> proxy = (FallbackFactory<?>) Proxy.newProxyInstance(factory.getClass().getClassLoader(),
							new Class<?>[] { FallbackFactory.class }, factoryHandler);
					factoryField.set(handler, proxy);
				}
			} // end-if (factory != null)

			HystrixCommandGroupKey hystrixCommandGroupKey = null;
			for (Iterator<Map.Entry<Method, Setter>> itr = setterMap.entrySet().iterator(); hystrixCommandGroupKey == null
					&& itr.hasNext();) {
				Map.Entry<Method, Setter> entry = itr.next();
				Setter setter = entry.getValue();

				hystrixCommandGroupKey = setter == null ? hystrixCommandGroupKey
						: (HystrixCommandGroupKey) groupKeyField.get(setter);
			}

			final String commandGroupKeyName = hystrixCommandGroupKey == null ? null : hystrixCommandGroupKey.name();
			HystrixCommandGroupKey groupKey = new HystrixCommandGroupKey() {
				public String name() {
					return commandGroupKeyName;
				}
			};

			HystrixCommandKey commandKey = new HystrixCommandKey() {
				public String name() {
					return HYSTRIX_COMMAND_NAME;
				}
			};

			CompensableHystrixMethodHandler hystrixHandler = new CompensableHystrixMethodHandler(dispatch);
			hystrixHandler.setStatefully(this.statefully);

			Setter setter = Setter.withGroupKey(groupKey).andCommandKey(commandKey);

			Method key = CompensableHystrixInvocationHandler.class.getDeclaredMethod(HYSTRIX_INVOKER_NAME,
					new Class<?>[] { CompensableHystrixInvocation.class });
			setterMap.put(key, setter);
			dispatch.put(key, hystrixHandler);
			fallbackMap.put(key, key);
		} catch (Exception ex) {
			throw new IllegalStateException("Error occurred!");
		}

		return Proxy.newProxyInstance(loader, interfaces, feignHandler);
	}

	public boolean isStatefully() {
		return statefully;
	}

	public void setStatefully(boolean statefully) {
		this.statefully = statefully;
	}

}
