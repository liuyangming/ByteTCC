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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.bytesoft.compensable.CompensableInvocation;
import org.bytesoft.compensable.CompensableInvocationExecutor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

public class CompensableInvocationExecutorImpl implements CompensableInvocationExecutor, ApplicationContextAware {
	private ApplicationContext applicationContext;

	public void confirm(CompensableInvocation invocation) throws RuntimeException {
		Method method = invocation.getMethod();
		Object[] args = invocation.getArgs();
		String beanName = invocation.getConfirmableKey();
		Object instance = this.applicationContext.getBean(beanName);
		try {
			method.invoke(instance, args);
		} catch (InvocationTargetException itex) {
			throw new RuntimeException(itex.getTargetException());
		} catch (Throwable throwable) {
			throw new RuntimeException(throwable);
		}
	}

	public void cancel(CompensableInvocation invocation) throws RuntimeException {
		Method method = invocation.getMethod();
		Object[] args = invocation.getArgs();
		String beanName = invocation.getCancellableKey();
		Object instance = this.applicationContext.getBean(beanName);
		try {
			method.invoke(instance, args);
		} catch (InvocationTargetException itex) {
			throw new RuntimeException(itex.getTargetException());
		} catch (Throwable throwable) {
			throw new RuntimeException(throwable);
		}
	}

	public ApplicationContext getApplicationContext() {
		return applicationContext;
	}

	public void setApplicationContext(ApplicationContext applicationContext) {
		this.applicationContext = applicationContext;
	}
}
