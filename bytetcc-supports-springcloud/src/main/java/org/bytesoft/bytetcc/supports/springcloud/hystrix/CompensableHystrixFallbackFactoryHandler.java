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

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import feign.hystrix.FallbackFactory;

public class CompensableHystrixFallbackFactoryHandler implements InvocationHandler {
	private final FallbackFactory<?> fallbackFactory;
	private final Class<?> fallbackType;

	public CompensableHystrixFallbackFactoryHandler(FallbackFactory<?> fallbackFactory, Class<?> fallbackType) {
		this.fallbackFactory = fallbackFactory;
		this.fallbackType = fallbackType;
	}

	public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
		Object fallback = method.invoke(this.fallbackFactory, args);
		CompensableHystrixFallbackHandler fallbackHandler = new CompensableHystrixFallbackHandler(fallback);
		ClassLoader classLoader = fallback.getClass().getClassLoader();
		Class<?>[] interfaces = new Class<?>[] { CompensableHystrixInvocationHandler.class, this.fallbackType };
		return Proxy.newProxyInstance(classLoader, interfaces, fallbackHandler);
	}

	public FallbackFactory<?> getFallbackFactory() {
		return fallbackFactory;
	}

}
