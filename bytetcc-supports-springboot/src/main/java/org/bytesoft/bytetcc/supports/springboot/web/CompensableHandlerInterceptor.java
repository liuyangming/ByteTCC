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
package org.bytesoft.bytetcc.supports.springboot.web;

import java.lang.reflect.Method;
import java.util.Base64;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.bytesoft.bytejta.supports.rpc.TransactionRequestImpl;
import org.bytesoft.bytejta.supports.rpc.TransactionResponseImpl;
import org.bytesoft.bytetcc.supports.springboot.SpringBootBeanRegistry;
import org.bytesoft.bytetcc.supports.springboot.controller.CompensableCoordinatorController;
import org.bytesoft.common.utils.SerializeUtils;
import org.bytesoft.compensable.Compensable;
import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.CompensableManager;
import org.bytesoft.compensable.CompensableTransaction;
import org.bytesoft.compensable.TransactionContext;
import org.bytesoft.compensable.aware.CompensableEndpointAware;
import org.bytesoft.transaction.supports.rpc.TransactionInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

public class CompensableHandlerInterceptor implements HandlerInterceptor, CompensableEndpointAware, ApplicationContextAware {
	static final Logger logger = LoggerFactory.getLogger(CompensableHandlerInterceptor.class);
	static final String HEADER_TRANCACTION_KEY = "X-BYTETCC-TRANSACTION";
	static final String HEADER_PROPAGATION_KEY = "X-BYTETCC-PROPAGATION";
	static final String HEADER_RECURSIVELY_KEY = "X-BYTETCC-RECURSIVELY";

	private String identifier;
	private ApplicationContext applicationContext;

	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
		String transactionStr = request.getHeader(HEADER_TRANCACTION_KEY);
		if (StringUtils.isBlank(transactionStr)) {
			return true;
		}

		if (HandlerMethod.class.isInstance(handler) == false) {
			return true;
		}

		HandlerMethod hm = (HandlerMethod) handler;
		Class<?> clazz = hm.getBeanType();
		Method method = hm.getMethod();
		if (CompensableCoordinatorController.class.equals(clazz)) {
			return true;
		} else if (ErrorController.class.isInstance(hm.getBean())) {
			return true;
		}

		String propagationStr = request.getHeader(HEADER_PROPAGATION_KEY);

		String transactionText = StringUtils.trimToNull(transactionStr);
		String propagationText = StringUtils.trimToNull(propagationStr);

		Transactional globalTransactional = clazz.getAnnotation(Transactional.class);
		Transactional methodTransactional = method.getAnnotation(Transactional.class);
		boolean transactionalDefined = globalTransactional != null || methodTransactional != null;
		Compensable annotation = clazz.getAnnotation(Compensable.class);
		if (transactionalDefined && annotation == null) {
			logger.warn("Invalid transaction definition(uri={}, handler= {})!", request.getRequestURI(), handler,
					new IllegalStateException());
			return true;
		}

		SpringBootBeanRegistry beanRegistry = SpringBootBeanRegistry.getInstance();
		CompensableBeanFactory beanFactory = beanRegistry.getBeanFactory();
		TransactionInterceptor transactionInterceptor = beanFactory.getTransactionInterceptor();

		byte[] byteArray = transactionText == null ? new byte[0] : Base64.getDecoder().decode(transactionText);

		TransactionContext transactionContext = null;
		if (byteArray != null && byteArray.length > 0) {
			transactionContext = (TransactionContext) SerializeUtils.deserializeObject(byteArray);
			transactionContext.setPropagated(true);
			transactionContext.setPropagatedBy(propagationText);
		}

		TransactionRequestImpl req = new TransactionRequestImpl();
		req.setTransactionContext(transactionContext);
		req.setTargetTransactionCoordinator(beanRegistry.getConsumeCoordinator(propagationText));

		transactionInterceptor.afterReceiveRequest(req);

		CompensableManager compensableManager = beanFactory.getCompensableManager();
		CompensableTransaction compensable = compensableManager.getCompensableTransactionQuietly();
		String propagatedBy = (String) compensable.getTransactionContext().getPropagatedBy();

		byte[] responseByteArray = SerializeUtils.serializeObject(compensable.getTransactionContext());
		String compensableStr = Base64.getEncoder().encodeToString(responseByteArray);
		response.setHeader(HEADER_TRANCACTION_KEY, compensableStr);
		response.setHeader(HEADER_PROPAGATION_KEY, this.identifier);
		response.setHeader(HEADER_RECURSIVELY_KEY,
				String.valueOf(StringUtils.equalsIgnoreCase(propagatedBy, propagationText) == false));

		return true;
	}

	public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView modelAndView)
			throws Exception {
	}

	public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex)
			throws Exception {
		String transactionStr = request.getHeader(HEADER_TRANCACTION_KEY);
		if (StringUtils.isBlank(transactionStr)) {
			return;
		}

		if (HandlerMethod.class.isInstance(handler) == false) {
			return;
		}

		HandlerMethod hm = (HandlerMethod) handler;
		Class<?> clazz = hm.getBeanType();
		Method method = hm.getMethod();
		if (CompensableCoordinatorController.class.equals(clazz)) {
			return;
		} else if (ErrorController.class.isInstance(hm.getBean())) {
			return;
		}

		Transactional globalTransactional = clazz.getAnnotation(Transactional.class);
		Transactional methodTransactional = method.getAnnotation(Transactional.class);
		boolean transactionalDefined = globalTransactional != null || methodTransactional != null;
		Compensable annotation = clazz.getAnnotation(Compensable.class);
		if (transactionalDefined && annotation == null) {
			return;
		}

		SpringBootBeanRegistry beanRegistry = SpringBootBeanRegistry.getInstance();
		CompensableBeanFactory beanFactory = beanRegistry.getBeanFactory();
		CompensableManager compensableManager = beanFactory.getCompensableManager();
		TransactionInterceptor transactionInterceptor = beanFactory.getTransactionInterceptor();

		CompensableTransaction compensable = compensableManager.getCompensableTransactionQuietly();
		TransactionContext transactionContext = compensable.getTransactionContext();

		byte[] byteArray = SerializeUtils.serializeObject(transactionContext);
		String compensableStr = Base64.getEncoder().encodeToString(byteArray);
		response.setHeader(HEADER_TRANCACTION_KEY, compensableStr);
		response.setHeader(HEADER_PROPAGATION_KEY, this.identifier);

		TransactionResponseImpl resp = new TransactionResponseImpl();
		resp.setTransactionContext(transactionContext);
		resp.setSourceTransactionCoordinator(beanRegistry.getConsumeCoordinator(null));

		transactionInterceptor.beforeSendResponse(resp);

	}

	public String getEndpoint() {
		return this.identifier;
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