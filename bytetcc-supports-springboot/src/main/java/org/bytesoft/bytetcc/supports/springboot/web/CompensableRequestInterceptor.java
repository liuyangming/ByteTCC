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

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.Base64;

import org.apache.commons.lang3.StringUtils;
import org.bytesoft.bytejta.supports.internal.RemoteCoordinatorRegistry;
import org.bytesoft.bytejta.supports.rpc.TransactionRequestImpl;
import org.bytesoft.bytejta.supports.rpc.TransactionResponseImpl;
import org.bytesoft.bytetcc.CompensableTransactionImpl;
import org.bytesoft.bytetcc.supports.springboot.SpringBootBeanRegistry;
import org.bytesoft.bytetcc.supports.springboot.SpringBootCoordinator;
import org.bytesoft.common.utils.CommonUtils;
import org.bytesoft.common.utils.SerializeUtils;
import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.CompensableManager;
import org.bytesoft.compensable.TransactionContext;
import org.bytesoft.compensable.aware.CompensableEndpointAware;
import org.bytesoft.transaction.remote.RemoteAddr;
import org.bytesoft.transaction.remote.RemoteCoordinator;
import org.bytesoft.transaction.remote.RemoteNode;
import org.bytesoft.transaction.supports.rpc.TransactionInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.HttpClientErrorException;

public class CompensableRequestInterceptor
		implements ClientHttpRequestInterceptor, CompensableEndpointAware, ApplicationContextAware {
	static final Logger logger = LoggerFactory.getLogger(CompensableRequestInterceptor.class);

	static final String HEADER_TRANCACTION_KEY = "X-BYTETCC-TRANSACTION";
	static final String HEADER_PROPAGATION_KEY = "X-BYTETCC-PROPAGATION";
	static final String HEADER_RECURSIVELY_KEY = "X-BYTETCC-RECURSIVELY";
	static final String PREFIX_TRANSACTION_KEY = "/org/bytesoft/bytetcc";

	private String identifier;
	private ApplicationContext applicationContext;

	public ClientHttpResponse intercept(final HttpRequest httpRequest, byte[] body, ClientHttpRequestExecution execution)
			throws IOException {

		SpringBootBeanRegistry beanRegistry = SpringBootBeanRegistry.getInstance();
		CompensableBeanFactory beanFactory = beanRegistry.getBeanFactory();
		CompensableManager compensableManager = beanFactory.getCompensableManager();

		CompensableTransactionImpl compensable = //
				(CompensableTransactionImpl) compensableManager.getCompensableTransactionQuietly();

		String path = httpRequest.getURI().getPath();
		int position = path.startsWith("/") ? path.indexOf("/", 1) : -1;
		String pathWithoutContextPath = position > 0 ? path.substring(position) : null;
		if (StringUtils.startsWith(path, PREFIX_TRANSACTION_KEY) //
				|| StringUtils.startsWith(pathWithoutContextPath, PREFIX_TRANSACTION_KEY)) {
			return execution.execute(httpRequest, body);
		} else if (compensable == null) {
			return execution.execute(httpRequest, body);
		} else if (compensable.getTransactionContext().isCompensable() == false) {
			return execution.execute(httpRequest, body);
		}

		ClientHttpResponse httpResponse = null;
		boolean serverFlag = true;
		try {
			this.invokeBeforeSendRequest(httpRequest);

			httpResponse = execution.execute(httpRequest, body);
			return httpResponse;
		} catch (HttpClientErrorException clientEx) {
			serverFlag = false;
			throw clientEx;
		} finally {
			if (httpResponse != null) {
				this.invokeAfterRecvResponse(httpResponse, serverFlag);
			} // end-if (httpResponse != null)

		}

	}

	private void invokeBeforeSendRequest(HttpRequest httpRequest) throws IOException {
		RemoteCoordinatorRegistry registry = RemoteCoordinatorRegistry.getInstance();
		SpringBootBeanRegistry beanRegistry = SpringBootBeanRegistry.getInstance();
		CompensableBeanFactory beanFactory = beanRegistry.getBeanFactory();
		CompensableManager compensableManager = beanFactory.getCompensableManager();
		TransactionInterceptor transactionInterceptor = beanFactory.getTransactionInterceptor();

		CompensableTransactionImpl compensable = //
				(CompensableTransactionImpl) compensableManager.getCompensableTransactionQuietly();

		TransactionContext transactionContext = compensable.getTransactionContext();

		byte[] reqByteArray = SerializeUtils.serializeObject(transactionContext);
		String reqTransactionStr = Base64.getEncoder().encodeToString(reqByteArray);

		HttpHeaders reqHeaders = httpRequest.getHeaders();
		reqHeaders.add(HEADER_TRANCACTION_KEY, reqTransactionStr);
		reqHeaders.add(HEADER_PROPAGATION_KEY, this.identifier);

		TransactionRequestImpl request = new TransactionRequestImpl();
		request.setTransactionContext(transactionContext);

		String targetHost = httpRequest.getURI().getHost();
		int targetPort = httpRequest.getURI().getPort();

		RemoteAddr remoteAddr = new RemoteAddr();
		remoteAddr.setServerHost(targetHost);
		remoteAddr.setServerPort(targetPort);

		RemoteCoordinator participant = registry.getPhysicalInstance(remoteAddr);
		if (participant == null) {
			SpringBootCoordinator handler = new SpringBootCoordinator();
			handler.setIdentifier(String.format("%s:%s:%s", targetHost, null, targetPort));
			handler.setEnvironment(beanRegistry.getEnvironment());
			participant = (RemoteCoordinator) Proxy.newProxyInstance(SpringBootCoordinator.class.getClassLoader(),
					new Class[] { RemoteCoordinator.class }, handler);
		}

		request.setTargetTransactionCoordinator(participant);

		transactionInterceptor.beforeSendRequest(request);
	}

	private void invokeAfterRecvResponse(ClientHttpResponse httpResponse, boolean serverFlag) throws IOException {
		RemoteCoordinatorRegistry participantRegistry = RemoteCoordinatorRegistry.getInstance();
		SpringBootBeanRegistry beanRegistry = SpringBootBeanRegistry.getInstance();
		CompensableBeanFactory beanFactory = beanRegistry.getBeanFactory();
		TransactionInterceptor transactionInterceptor = beanFactory.getTransactionInterceptor();

		HttpHeaders respHeaders = httpResponse.getHeaders();
		String respTransactionStr = respHeaders.getFirst(HEADER_TRANCACTION_KEY);
		String respPropagationStr = respHeaders.getFirst(HEADER_PROPAGATION_KEY);
		String respRecursivelyStr = respHeaders.getFirst(HEADER_RECURSIVELY_KEY);

		String instanceId = StringUtils.trimToEmpty(respPropagationStr);

		RemoteAddr remoteAddr = CommonUtils.getRemoteAddr(instanceId);
		RemoteNode remoteNode = CommonUtils.getRemoteNode(instanceId);
		if (remoteAddr != null && remoteNode != null) {
			participantRegistry.putRemoteNode(remoteAddr, remoteNode);
		}

		String transactionText = StringUtils.trimToNull(respTransactionStr);
		byte[] byteArray = StringUtils.isBlank(transactionText) ? null : Base64.getDecoder().decode(transactionText);
		TransactionContext serverContext = byteArray == null || byteArray.length == 0 //
				? null
				: (TransactionContext) SerializeUtils.deserializeObject(byteArray);

		TransactionResponseImpl txResp = new TransactionResponseImpl();
		txResp.setTransactionContext(serverContext);
		RemoteCoordinator serverCoordinator = beanRegistry.getConsumeCoordinator(instanceId);
		txResp.setSourceTransactionCoordinator(serverCoordinator);
		txResp.setParticipantDelistFlag(serverFlag ? StringUtils.equalsIgnoreCase(respRecursivelyStr, "TRUE") : true);

		transactionInterceptor.afterReceiveResponse(txResp);
	}

	public ApplicationContext getApplicationContext() {
		return applicationContext;
	}

	public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
		this.applicationContext = applicationContext;
	}

	public String getEndpoint() {
		return this.identifier;
	}

	public void setEndpoint(String identifier) {
		this.identifier = identifier;
	}

}