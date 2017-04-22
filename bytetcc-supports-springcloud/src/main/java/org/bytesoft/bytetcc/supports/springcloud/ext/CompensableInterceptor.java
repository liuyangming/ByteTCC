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
package org.bytesoft.bytetcc.supports.springcloud.ext;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.bytesoft.bytetcc.supports.springcloud.CompensableBeanRegistry;
import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.transaction.supports.rpc.TransactionInterceptor;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import feign.InvocationHandlerFactory;
import feign.Target;

public class CompensableInterceptor
		implements HandlerInterceptor, ClientHttpRequestInterceptor, InvocationHandlerFactory, InvocationHandler {

	private Target<?> target;
	private Map<Method, MethodHandler> handlers;

	@SuppressWarnings("rawtypes")
	public InvocationHandler create(Target target, Map<Method, MethodHandler> dispatch) {
		CompensableInterceptor handler = new CompensableInterceptor();
		handler.setTarget(target);
		handler.setHandlers(dispatch);
		return handler;
	}

	public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
		if (Object.class.equals(method.getDeclaringClass())) {
			return method.invoke(this, args);
		} else {
			CompensableBeanRegistry beanRegistry = CompensableBeanRegistry.getInstance();
			CompensableBeanFactory beanFactory = beanRegistry.getBeanFactory();
			TransactionInterceptor transactionInterceptor = beanFactory.getTransactionInterceptor();
			try {
				transactionInterceptor.beforeSendRequest(null); // TODO
				MethodHandler methodHandler = this.handlers.get(method);
				return methodHandler.invoke(args);
			} finally {
				transactionInterceptor.afterReceiveResponse(null); // TODO
			}
		}
	}

	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {

		CompensableBeanRegistry beanRegistry = CompensableBeanRegistry.getInstance();
		CompensableBeanFactory beanFactory = beanRegistry.getBeanFactory();
		TransactionInterceptor transactionInterceptor = beanFactory.getTransactionInterceptor();
		transactionInterceptor.afterReceiveRequest(null); // TODO

		return true;
	}

	public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView modelAndView)
			throws Exception {

		CompensableBeanRegistry beanRegistry = CompensableBeanRegistry.getInstance();
		CompensableBeanFactory beanFactory = beanRegistry.getBeanFactory();
		TransactionInterceptor transactionInterceptor = beanFactory.getTransactionInterceptor();
		transactionInterceptor.beforeSendResponse(null); // TODO

	}

	public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex)
			throws Exception {
	}

	public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
			throws IOException {

		CompensableBeanRegistry beanRegistry = CompensableBeanRegistry.getInstance();
		CompensableBeanFactory beanFactory = beanRegistry.getBeanFactory();
		TransactionInterceptor transactionInterceptor = beanFactory.getTransactionInterceptor();
		transactionInterceptor.beforeSendRequest(null); // TODO
		try {
			return execution.execute(request, body);
		} finally {
			transactionInterceptor.afterReceiveResponse(null); // TODO
		}

	}

	public Target<?> getTarget() {
		return target;
	}

	public void setTarget(Target<?> target) {
		this.target = target;
	}

	public Map<Method, MethodHandler> getHandlers() {
		return handlers;
	}

	public void setHandlers(Map<Method, MethodHandler> handlers) {
		this.handlers = handlers;
	}

}