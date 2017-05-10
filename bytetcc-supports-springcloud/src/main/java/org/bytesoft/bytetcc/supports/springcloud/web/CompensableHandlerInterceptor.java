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
package org.bytesoft.bytetcc.supports.springcloud.web;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang.StringUtils;
import org.bytesoft.bytejta.supports.rpc.TransactionRequestImpl;
import org.bytesoft.bytejta.supports.rpc.TransactionResponseImpl;
import org.bytesoft.bytetcc.supports.springcloud.SpringCloudBeanRegistry;
import org.bytesoft.bytetcc.supports.springcloud.controller.CompensableCoordinatorController;
import org.bytesoft.common.utils.ByteUtils;
import org.bytesoft.common.utils.CommonUtils;
import org.bytesoft.compensable.Compensable;
import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.CompensableManager;
import org.bytesoft.compensable.CompensableTransaction;
import org.bytesoft.compensable.TransactionContext;
import org.bytesoft.compensable.aware.CompensableEndpointAware;
import org.bytesoft.transaction.supports.rpc.TransactionInterceptor;
import org.springframework.beans.BeansException;
import org.springframework.boot.autoconfigure.web.ErrorController;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

public class CompensableHandlerInterceptor implements HandlerInterceptor, CompensableEndpointAware, ApplicationContextAware {
	static final String HEADER_TRANCACTION_KEY = "org.bytesoft.bytetcc.transaction";
	static final String HEADER_PROPAGATION_KEY = "org.bytesoft.bytetcc.propagation";

	private String identifier;
	private ApplicationContext applicationContext;

	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
		HandlerMethod hm = (HandlerMethod) handler;
		Class<?> clazz = hm.getBeanType();
		if (CompensableCoordinatorController.class.equals(clazz)) {
			return true;
		} else if (ErrorController.class.isInstance(hm.getBean())) {
			return true;
		}

		String transactionStr = request.getHeader(HEADER_TRANCACTION_KEY);
		if (StringUtils.isBlank(transactionStr)) {
			return true;
		}

		String propagationStr = request.getHeader(HEADER_PROPAGATION_KEY);

		String transactionText = StringUtils.trimToNull(transactionStr);
		String propagationText = StringUtils.trimToNull(propagationStr);

		Compensable annotation = clazz.getAnnotation(Compensable.class);
		if (annotation == null) {
			return true;
		}

		SpringCloudBeanRegistry beanRegistry = SpringCloudBeanRegistry.getInstance();
		CompensableBeanFactory beanFactory = beanRegistry.getBeanFactory();
		TransactionInterceptor transactionInterceptor = beanFactory.getTransactionInterceptor();

		byte[] byteArray = transactionText == null ? new byte[0] : ByteUtils.stringToByteArray(transactionText);

		TransactionContext transactionContext = null;
		if (byteArray != null && byteArray.length > 0) {
			transactionContext = (TransactionContext) CommonUtils.deserializeObject(byteArray);
		}

		TransactionRequestImpl req = new TransactionRequestImpl();
		req.setTransactionContext(transactionContext);
		req.setTargetTransactionCoordinator(beanRegistry.getConsumeCoordinator(propagationText));

		transactionInterceptor.afterReceiveRequest(req);

		return true;
	}

	public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView modelAndView)
			throws Exception {
	}

	public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex)
			throws Exception {
		HandlerMethod hm = (HandlerMethod) handler;
		Class<?> clazz = hm.getBeanType();
		if (CompensableCoordinatorController.class.equals(clazz)) {
			return;
		} else if (ErrorController.class.isInstance(hm.getBean())) {
			return;
		}

		String transactionStr = request.getHeader(HEADER_TRANCACTION_KEY);
		if (StringUtils.isBlank(transactionStr)) {
			return;
		}

		Compensable annotation = clazz.getAnnotation(Compensable.class);
		if (annotation == null) {
			return;
		}

		SpringCloudBeanRegistry beanRegistry = SpringCloudBeanRegistry.getInstance();
		CompensableBeanFactory beanFactory = beanRegistry.getBeanFactory();
		CompensableManager compensableManager = beanFactory.getCompensableManager();
		TransactionInterceptor transactionInterceptor = beanFactory.getTransactionInterceptor();

		CompensableTransaction compensable = compensableManager.getCompensableTransactionQuietly();
		TransactionContext transactionContext = compensable.getTransactionContext();

		byte[] byteArray = CommonUtils.serializeObject(transactionContext);
		String compensableStr = ByteUtils.byteArrayToString(byteArray);
		response.addHeader(HEADER_TRANCACTION_KEY, compensableStr);
		response.addHeader(HEADER_PROPAGATION_KEY, this.identifier);

		TransactionResponseImpl resp = new TransactionResponseImpl();
		resp.setTransactionContext(transactionContext);
		resp.setSourceTransactionCoordinator(beanRegistry.getConsumeCoordinator(null));

		transactionInterceptor.beforeSendResponse(resp);

	}

	public void setEndpoint(String identifier) {
		this.identifier = identifier;
	}

	public ApplicationContext getApplicationContext() {
		return applicationContext;
	}

	public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
		this.applicationContext = applicationContext;
	}

}