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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.bytesoft.bytejta.supports.rpc.TransactionRequestImpl;
import org.bytesoft.bytejta.supports.rpc.TransactionResponseImpl;
import org.bytesoft.bytetcc.CompensableTransactionImpl;
import org.bytesoft.bytetcc.supports.springcloud.SpringCloudBeanRegistry;
import org.bytesoft.bytetcc.supports.springcloud.loadbalancer.CompensableLoadBalancerInterceptor;
import org.bytesoft.common.utils.CommonUtils;
import org.bytesoft.common.utils.SerializeUtils;
import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.CompensableManager;
import org.bytesoft.compensable.TransactionContext;
import org.bytesoft.compensable.aware.CompensableEndpointAware;
import org.bytesoft.transaction.archive.XAResourceArchive;
import org.bytesoft.transaction.remote.RemoteCoordinator;
import org.bytesoft.transaction.remote.RemoteSvc;
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

import com.netflix.appinfo.InstanceInfo;
import com.netflix.loadbalancer.Server;
import com.netflix.loadbalancer.Server.MetaInfo;
import com.netflix.niws.loadbalancer.DiscoveryEnabledServer;

public class CompensableRequestInterceptor
		implements ClientHttpRequestInterceptor, CompensableEndpointAware, ApplicationContextAware {
	static final Logger logger = LoggerFactory.getLogger(CompensableRequestInterceptor.class);

	static final String HEADER_TRANCACTION_KEY = "X-BYTETCC-TRANSACTION"; // org.bytesoft.bytetcc.transaction
	static final String HEADER_PROPAGATION_KEY = "X-BYTETCC-PROPAGATION"; // org.bytesoft.bytetcc.propagation
	static final String PREFIX_TRANSACTION_KEY = "/org/bytesoft/bytetcc";

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

		// final String serviceId = uri.getAuthority();

		final Map<RemoteSvc, XAResourceArchive> participants = compensable.getParticipantArchiveMap();
		beanRegistry.setLoadBalancerInterceptor(new CompensableLoadBalancerInterceptor() {
			public List<Server> beforeCompletion(List<Server> servers) {
				final List<Server> readyServerList = new ArrayList<Server>();
				final List<Server> unReadyServerList = new ArrayList<Server>();

				for (int i = 0; servers != null && i < servers.size(); i++) {
					Server server = servers.get(i);

					// String instanceId = metaInfo.getInstanceId();
					String instanceId = null;

					if (DiscoveryEnabledServer.class.isInstance(server)) {
						DiscoveryEnabledServer discoveryEnabledServer = (DiscoveryEnabledServer) server;
						InstanceInfo instanceInfo = discoveryEnabledServer.getInstanceInfo();
						String addr = instanceInfo.getIPAddr();
						String appName = instanceInfo.getAppName();
						int port = instanceInfo.getPort();

						instanceId = String.format("%s:%s:%s", addr, appName, port);
					} else {
						MetaInfo metaInfo = server.getMetaInfo();

						String host = server.getHost();
						String addr = host.matches("\\d+(\\.\\d+){3}") ? host : CommonUtils.getInetAddress(host);
						String appName = metaInfo.getAppName();
						int port = server.getPort();
						instanceId = String.format("%s:%s:%s", addr, appName, port);
					}

					if (participants.containsKey(instanceId)) {
						List<Server> serverList = new ArrayList<Server>();
						serverList.add(server);
						return serverList;
					} // end-if (participants.containsKey(instanceId))

					if (server.isReadyToServe()) {
						readyServerList.add(server);
					} else {
						unReadyServerList.add(server);
					}

				}

				logger.warn("There is no suitable server: expect= {}, actual= {}!", participants.keySet(), servers);
				return readyServerList.isEmpty() ? unReadyServerList : readyServerList;
			}

			public void afterCompletion(Server server) {
				if (server == null) {
					logger.warn(
							"There is no suitable server, the TransactionInterceptor.beforeSendRequest() operation is not executed!");
					return;
				} else {
					try {
						// String instanceId = metaInfo.getInstanceId();
						String instanceId = null;

						if (DiscoveryEnabledServer.class.isInstance(server)) {
							DiscoveryEnabledServer discoveryEnabledServer = (DiscoveryEnabledServer) server;
							InstanceInfo instanceInfo = discoveryEnabledServer.getInstanceInfo();
							String addr = instanceInfo.getIPAddr();
							String appName = instanceInfo.getAppName();
							int port = instanceInfo.getPort();

							instanceId = String.format("%s:%s:%s", addr, appName, port);
						} else {
							MetaInfo metaInfo = server.getMetaInfo();

							String host = server.getHost();
							String addr = host.matches("\\d+(\\.\\d+){3}") ? host : CommonUtils.getInetAddress(host);
							String appName = metaInfo.getAppName();
							int port = server.getPort();
							instanceId = String.format("%s:%s:%s", addr, appName, port);
						}

						invokeBeforeSendRequest(httpRequest, instanceId);
					} catch (IOException ex) {
						throw new RuntimeException(ex);
					}
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
			beanRegistry.removeLoadBalancerInterceptor();

			if (httpResponse != null) {
				this.invokeAfterRecvResponse(httpResponse, serverFlag);
			} // end-if (httpResponse != null)

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

		byte[] reqByteArray = SerializeUtils.serializeObject(transactionContext);
		String reqTransactionStr = Base64.getEncoder().encodeToString(reqByteArray);

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

		byte[] byteArray = Base64.getDecoder().decode(StringUtils.trimToNull(respTransactionStr));
		TransactionContext serverContext = (TransactionContext) SerializeUtils.deserializeObject(byteArray);

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