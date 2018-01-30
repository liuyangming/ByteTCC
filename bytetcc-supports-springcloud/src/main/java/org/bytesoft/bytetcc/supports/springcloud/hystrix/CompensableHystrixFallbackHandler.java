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

public class CompensableHystrixFallbackHandler implements InvocationHandler {
	private final Object fallback;

	public CompensableHystrixFallbackHandler(Object fallback) {
		this.fallback = fallback;
	}

	public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
		Method targetMethod = (Method) args[1];
		Object[] targetArgs = (Object[]) args[2];
		return targetMethod.invoke(this.fallback, targetArgs);
	}

	public Object getFallback() {
		return fallback;
	}

}
