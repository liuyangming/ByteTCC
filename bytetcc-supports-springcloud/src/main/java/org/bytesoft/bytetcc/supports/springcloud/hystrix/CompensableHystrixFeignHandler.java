/**
 * Copyright 2014-2017 yangming.liu<bytefox@126.com>.
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

import org.bytesoft.bytetcc.CompensableTransactionImpl;
import org.bytesoft.bytetcc.supports.springcloud.SpringCloudBeanRegistry;
import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.CompensableManager;
import org.bytesoft.compensable.TransactionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CompensableHystrixFeignHandler implements InvocationHandler {
	static final Logger logger = LoggerFactory.getLogger(CompensableHystrixFeignHandler.class);

	private InvocationHandler delegate;

	public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
		if (Object.class.equals(method.getDeclaringClass())) {
			return this.delegate.invoke(proxy, method, args);
		} else {
			final SpringCloudBeanRegistry beanRegistry = SpringCloudBeanRegistry.getInstance();
			CompensableBeanFactory beanFactory = beanRegistry.getBeanFactory();
			CompensableManager compensableManager = beanFactory.getCompensableManager();

			CompensableTransactionImpl compensable = //
					(CompensableTransactionImpl) compensableManager.getCompensableTransactionQuietly();
			if (compensable == null) {
				return this.delegate.invoke(proxy, method, args);
			}

			final TransactionContext transactionContext = compensable.getTransactionContext();
			if (transactionContext.isCompensable() == false) {
				return this.delegate.invoke(proxy, method, args);
			}

			Method targetMethod = CompensableHystrixInvocationHandler.class.getDeclaredMethod(
					CompensableHystrixBeanPostProcessor.HYSTRIX_INVOKER_NAME,
					new Class<?>[] { Thread.class, Method.class, Object[].class });
			Object[] targetArgs = new Object[] { Thread.currentThread(), method, args };
			return this.delegate.invoke(proxy, targetMethod, targetArgs);
		}
	}

	public InvocationHandler getDelegate() {
		return delegate;
	}

	public void setDelegate(InvocationHandler delegate) {
		this.delegate = delegate;
	}

}
