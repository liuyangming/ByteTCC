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
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.bytesoft.bytejta.supports.rpc.TransactionRequestImpl;
import org.bytesoft.bytejta.supports.rpc.TransactionResponseImpl;
import org.bytesoft.bytejta.supports.wire.RemoteCoordinator;
import org.bytesoft.bytetcc.CompensableTransactionImpl;
import org.bytesoft.bytetcc.supports.springcloud.CompensableRibbonInterceptor;
import org.bytesoft.bytetcc.supports.springcloud.SpringCloudBeanRegistry;
import org.bytesoft.common.utils.ByteUtils;
import org.bytesoft.common.utils.CommonUtils;
import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.CompensableManager;
import org.bytesoft.compensable.TransactionContext;
import org.bytesoft.compensable.aware.CompensableEndpointAware;
import org.bytesoft.transaction.archive.XAResourceArchive;
import org.bytesoft.transaction.supports.resource.XAResourceDescriptor;
import org.bytesoft.transaction.supports.rpc.TransactionInterceptor;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.HttpClientErrorException;

import com.netflix.loadbalancer.Server;

public class CompensableRequestInterceptor
		implements ClientHttpRequestInterceptor, CompensableEndpointAware, ApplicationContextAware {
	static final String HEADER_TRANCACTION_KEY = "org.bytesoft.bytetcc.transaction";
	static final String HEADER_PROPAGATION_KEY = "org.bytesoft.bytetcc.propagation";

	private String identifier;
	private ApplicationContext applicationContext;

	public ClientHttpResponse intercept(final HttpRequest httpRequest, byte[] body, ClientHttpRequestExecution execution)
			throws IOException {

		SpringCloudBeanRegistry beanRegistry = SpringCloudBeanRegistry.getInstance();
		CompensableBeanFactory beanFactory = beanRegistry.getBeanFactory();
		CompensableManager compensableManager = beanFactory.getCompensableManager();

		CompensableTransactionImpl compensable = //
				(CompensableTransactionImpl) compensableManager.getCompensableTransactionQuietly();

		String path = httpRequest.getURI().getPath();
		if (path.startsWith("/org/bytesoft/bytetcc")) {
			return execution.execute(httpRequest, body);
		} else if (compensable == null) {
			return execution.execute(httpRequest, body);
		} else if (compensable.getTransactionContext().isCompensable() == false) {
			return execution.execute(httpRequest, body);
		}

		final List<XAResourceArchive> participantList = compensable.getParticipantArchiveList();
		beanRegistry.setRibbonInterceptor(new CompensableRibbonInterceptor() {
			public Server beforeCompletion(List<Server> servers) {
				for (int i = 0; servers != null && participantList != null && i < servers.size(); i++) {
					Server server = servers.get(i);
					String instanceId = server.getMetaInfo().getInstanceId();
					for (int j = 0; participantList != null && j < participantList.size(); j++) {
						XAResourceArchive archive = participantList.get(j);
						XAResourceDescriptor descriptor = archive.getDescriptor();
						String identifier = descriptor.getIdentifier();
						if (StringUtils.equals(instanceId, identifier)) {
							return server;
						}
					}
				}
				return null;
			}

			public void afterCompletion(Server server) {
				try {
					invokeBeforeSendRequest(httpRequest, server.getMetaInfo().getInstanceId());
				} catch (IOException ex) {
					throw new RuntimeException(ex);
				}
			}
		});

		ClientHttpResponse httpResponse = null;
		boolean serverFlag = true;
		try {
			httpResponse = execution.execute(httpRequest, body);
			return httpResponse;
		} catch (HttpClientErrorException clientEx) {
			serverFlag = false;
			throw clientEx;
		} finally {
			beanRegistry.removeRibbonInterceptor();

			this.invokeAfterRecvResponse(httpResponse, serverFlag);
		}

	}

	private void invokeBeforeSendRequest(HttpRequest httpRequest, String identifier) throws IOException {
		SpringCloudBeanRegistry beanRegistry = SpringCloudBeanRegistry.getInstance();
		CompensableBeanFactory beanFactory = beanRegistry.getBeanFactory();
		CompensableManager compensableManager = beanFactory.getCompensableManager();
		TransactionInterceptor transactionInterceptor = beanFactory.getTransactionInterceptor();

		CompensableTransactionImpl compensable = //
				(CompensableTransactionImpl) compensableManager.getCompensableTransactionQuietly();

		TransactionContext transactionContext = compensable.getTransactionContext();

		byte[] reqByteArray = CommonUtils.serializeObject(transactionContext);
		String reqTransactionStr = ByteUtils.byteArrayToString(reqByteArray);

		HttpHeaders reqHeaders = httpRequest.getHeaders();
		reqHeaders.add(HEADER_TRANCACTION_KEY, reqTransactionStr);
		reqHeaders.add(HEADER_PROPAGATION_KEY, this.identifier);

		TransactionRequestImpl request = new TransactionRequestImpl();
		request.setTransactionContext(transactionContext);
		RemoteCoordinator coordinator = beanRegistry.getConsumeCoordinator(identifier);
		request.setTargetTransactionCoordinator(coordinator);

		transactionInterceptor.beforeSendRequest(request);
	}

	private void invokeAfterRecvResponse(ClientHttpResponse httpResponse, boolean serverFlag) throws IOException {
		SpringCloudBeanRegistry beanRegistry = SpringCloudBeanRegistry.getInstance();
		CompensableBeanFactory beanFactory = beanRegistry.getBeanFactory();
		TransactionInterceptor transactionInterceptor = beanFactory.getTransactionInterceptor();

		HttpHeaders respHeaders = httpResponse.getHeaders();
		String respTransactionStr = respHeaders.getFirst(HEADER_TRANCACTION_KEY);
		String respPropagationStr = respHeaders.getFirst(HEADER_PROPAGATION_KEY);

		byte[] byteArray = ByteUtils.stringToByteArray(StringUtils.trimToNull(respTransactionStr));
		TransactionContext serverContext = (TransactionContext) CommonUtils.deserializeObject(byteArray);

		TransactionResponseImpl txResp = new TransactionResponseImpl();
		txResp.setTransactionContext(serverContext);
		RemoteCoordinator serverCoordinator = beanRegistry.getConsumeCoordinator(respPropagationStr);
		txResp.setSourceTransactionCoordinator(serverCoordinator);
		txResp.setParticipantDelistFlag(serverFlag ? false : true);

		transactionInterceptor.afterReceiveResponse(txResp);
	}

	public ApplicationContext getApplicationContext() {
		return applicationContext;
	}

	public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
		this.applicationContext = applicationContext;
	}

	public void setEndpoint(String identifier) {
		this.identifier = identifier;
	}

}