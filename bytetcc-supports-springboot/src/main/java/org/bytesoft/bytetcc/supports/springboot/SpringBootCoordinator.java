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
package org.bytesoft.bytetcc.supports.springboot;

import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;

import org.apache.commons.lang3.StringUtils;
import org.bytesoft.bytejta.supports.internal.RemoteCoordinatorRegistry;
import org.bytesoft.common.utils.ByteUtils;
import org.bytesoft.common.utils.CommonUtils;
import org.bytesoft.common.utils.SerializeUtils;
import org.bytesoft.transaction.TransactionParticipant;
import org.bytesoft.transaction.remote.RemoteAddr;
import org.bytesoft.transaction.remote.RemoteCoordinator;
import org.bytesoft.transaction.remote.RemoteNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

public class SpringBootCoordinator implements InvocationHandler {
	static final Logger logger = LoggerFactory.getLogger(SpringBootCoordinator.class);
	static final String CONSTANT_CONTENT_PATH = "org.bytesoft.bytetcc.contextpath";
	static final String HEADER_PROPAGATION_KEY = "X-PROPAGATION-KEY";

	private String identifier;
	private Environment environment;

	public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
		Class<?> clazz = method.getDeclaringClass();
		String methodName = method.getName();
		Class<?> returnType = method.getReturnType();
		if (Object.class.equals(clazz)) {
			return method.invoke(this, args);
		} else if (TransactionParticipant.class.equals(clazz)) {
			throw new XAException(XAException.XAER_RMFAIL);
		} else if (RemoteCoordinator.class.equals(clazz)) {
			if ("getIdentifier".equals(methodName)) {
				return this.getParticipantsIdentifier(proxy, method, args);
			} else if ("getApplication".equals(methodName)) {
				return this.getParticipantsApplication(proxy, method, args);
			} else if ("getRemoteAddr".equals(methodName) && RemoteAddr.class.equals(returnType)) {
				String identifier = this.getParticipantsIdentifier(proxy, method, args);
				return identifier == null ? null : CommonUtils.getRemoteAddr(identifier);
			} else if ("getRemoteNode".equals(methodName) && RemoteNode.class.equals(returnType)) {
				String identifier = this.getParticipantsIdentifier(proxy, method, args);
				return identifier == null ? null : CommonUtils.getRemoteNode(identifier);
			} else {
				throw new XAException(XAException.XAER_RMFAIL);
			}
		} else if (XAResource.class.equals(clazz)) {
			if ("start".equals(methodName)) {
				return null;
			} else if ("prepare".equals(methodName)) {
				return this.invokePostCoordinator(proxy, method, args);
			} else if ("commit".equals(methodName)) {
				return this.invokePostCoordinator(proxy, method, args);
			} else if ("rollback".equals(methodName)) {
				return this.invokePostCoordinator(proxy, method, args);
			} else if ("recover".equals(methodName)) {
				return this.invokeGetCoordinator(proxy, method, args);
			} else if ("forget".equals(methodName)) {
				return this.invokePostCoordinator(proxy, method, args);
			} else {
				throw new XAException(XAException.XAER_RMFAIL);
			}
		} else {
			throw new IllegalAccessException();
		}
	}

	public Object invokePostCoordinator(Object proxy, Method method, Object[] args) throws Throwable {

		Class<?> returnType = method.getReturnType();
		try {
			RestTemplate transactionRestTemplate = SpringBootBeanRegistry.getInstance().getRestTemplate();
			RestTemplate restTemplate = transactionRestTemplate == null ? new RestTemplate() : transactionRestTemplate;

			RemoteAddr remoteAddr = CommonUtils.getRemoteAddr(this.identifier);

			StringBuilder ber = new StringBuilder();
			ber.append("http://");
			ber.append(remoteAddr.getServerHost()).append(":").append(remoteAddr.getServerPort());
			ber.append("/org/bytesoft/bytetcc/");

			ber.append(method.getName());
			for (int i = 0; i < args.length; i++) {
				Serializable arg = (Serializable) args[i];
				ber.append("/").append(this.serialize(arg));
			}

			ResponseEntity<?> response = restTemplate.postForEntity(ber.toString(), null, returnType, new Object[0]);

			return response.getBody();
		} catch (HttpClientErrorException ex) {
			XAException xaEx = new XAException(XAException.XAER_RMFAIL);
			xaEx.initCause(ex);
			throw xaEx;
		} catch (HttpServerErrorException ex) {
			// int statusCode = ex.getRawStatusCode();
			HttpHeaders headers = ex.getResponseHeaders();
			String failureText = StringUtils.trimToNull(headers.getFirst("failure"));
			String errorText = StringUtils.trimToNull(headers.getFirst("XA_XAER"));

			Boolean failure = failureText == null ? null : Boolean.parseBoolean(failureText);
			Integer errorCode = null;
			try {
				errorCode = errorText == null ? null : Integer.parseInt(errorText);
			} catch (Exception ignore) {
				logger.debug(ignore.getMessage());
			}

			if (failure != null && errorCode != null) {
				XAException xaEx = new XAException(errorCode);
				xaEx.initCause(ex);
				throw xaEx;
			} else {
				XAException xaEx = new XAException(XAException.XAER_RMERR);
				xaEx.initCause(ex);
				throw xaEx;
			}
		} catch (Exception ex) {
			XAException xaEx = new XAException(XAException.XAER_RMERR);
			xaEx.initCause(ex);
			throw xaEx;
		}

	}

	public Object invokeGetCoordinator(Object proxy, Method method, Object[] args) throws Throwable {

		Class<?> returnType = method.getReturnType();
		try {
			RestTemplate transactionRestTemplate = SpringBootBeanRegistry.getInstance().getRestTemplate();
			RestTemplate restTemplate = transactionRestTemplate == null ? new RestTemplate() : transactionRestTemplate;

			RemoteAddr remoteAddr = CommonUtils.getRemoteAddr(this.identifier);

			StringBuilder ber = new StringBuilder();
			ber.append("http://");
			ber.append(remoteAddr.getServerHost()).append(":").append(remoteAddr.getServerPort());
			ber.append("/org/bytesoft/bytetcc/");

			ber.append(method.getName());
			for (int i = 0; i < args.length; i++) {
				Serializable arg = (Serializable) args[i];
				ber.append("/").append(this.serialize(arg));
			}

			ResponseEntity<?> response = restTemplate.getForEntity(ber.toString(), returnType, new Object[0]);

			return response.getBody();
		} catch (HttpClientErrorException ex) {
			XAException xaEx = new XAException(XAException.XAER_RMFAIL);
			xaEx.initCause(ex);
			throw xaEx;
		} catch (HttpServerErrorException ex) {
			// int statusCode = ex.getRawStatusCode();
			HttpHeaders headers = ex.getResponseHeaders();
			String failureText = StringUtils.trimToNull(headers.getFirst("failure"));
			String errorText = StringUtils.trimToNull(headers.getFirst("XA_XAER"));

			Boolean failure = failureText == null ? null : Boolean.parseBoolean(failureText);
			Integer errorCode = null;
			try {
				errorCode = errorText == null ? null : Integer.parseInt(errorText);
			} catch (Exception ignore) {
				logger.debug(ignore.getMessage());
			}

			if (failure != null && errorCode != null) {
				XAException xaEx = new XAException(errorCode);
				xaEx.initCause(ex);
				throw xaEx;
			} else {
				XAException xaEx = new XAException(XAException.XAER_RMERR);
				xaEx.initCause(ex);
				throw xaEx;
			}
		} catch (Exception ex) {
			XAException xaEx = new XAException(XAException.XAER_RMERR);
			xaEx.initCause(ex);
			throw xaEx;
		}

	}

	private String serialize(Serializable arg) throws IOException {
		if (Xid.class.isInstance(arg)) {
			Xid xid = (Xid) arg;
			byte[] globalTransactionId = xid.getGlobalTransactionId();
			return ByteUtils.byteArrayToString(globalTransactionId);
		} else if (Integer.class.isInstance(arg) || Integer.TYPE.isInstance(arg)) {
			return String.valueOf(arg);
		} else if (Boolean.class.isInstance(arg) || Boolean.TYPE.isInstance(arg)) {
			return String.valueOf(arg);
		} else {
			byte[] byteArray = SerializeUtils.serializeObject(arg);
			return ByteUtils.byteArrayToString(byteArray);
		}
	}

	private String getParticipantsIdentifier(Object proxy, Method method, Object[] args) throws Throwable {
		if (StringUtils.isBlank(this.identifier)) {
			return null;
		}

		RemoteNode remoteNode = CommonUtils.getRemoteNode(this.identifier);
		if (remoteNode == null) {
			return null;
		}

		String serverHost = remoteNode.getServerHost();
		String serviceKey = remoteNode.getServiceKey();
		int serverPort = remoteNode.getServerPort();
		if (StringUtils.isNotBlank(serviceKey) && StringUtils.equalsIgnoreCase(serviceKey, "null") == false) {
			return this.identifier;
		}

		Object application = this.getParticipantsApplication(proxy, method, args);
		return String.format("%s:%s:%s", serverHost, application, serverPort);
	}

	private Object getParticipantsApplication(Object proxy, Method method, Object[] args) throws Throwable {
		if (StringUtils.isBlank(this.identifier)) {
			return null;
		}

		RemoteCoordinatorRegistry participantRegistry = RemoteCoordinatorRegistry.getInstance();
		SpringBootBeanRegistry beanRegistry = SpringBootBeanRegistry.getInstance();

		RemoteNode instance = CommonUtils.getRemoteNode(this.identifier);
		if (instance == null) {
			return null;
		} else if (StringUtils.isNotBlank(instance.getServiceKey())
				&& StringUtils.equalsIgnoreCase(instance.getServiceKey(), "null") == false) {
			return instance.getServiceKey();
		}

		String serverHost = instance.getServerHost();
		int serverPort = instance.getServerPort();
		RemoteAddr targetAddr = new RemoteAddr();
		targetAddr.setServerHost(serverHost);
		targetAddr.setServerPort(serverPort);
		RemoteNode targetNode = participantRegistry.getRemoteNode(targetAddr);
		if (targetNode != null) {
			this.identifier = String.format("%s:%s:%s", serverHost, targetNode.getServiceKey(), serverPort);
			return targetNode.getServiceKey();
		}

		StringBuilder ber = new StringBuilder();
		ber.append("http://");
		ber.append(serverHost).append(":").append(serverPort);
		ber.append("/org/bytesoft/bytetcc/getIdentifier");

		RestTemplate restTemplate = beanRegistry.getRestTemplate();
		HttpHeaders headers = restTemplate.headForHeaders(ber.toString());
		String instanceId = headers.getFirst(HEADER_PROPAGATION_KEY);

		RemoteNode remoteNode = CommonUtils.getRemoteNode(instanceId);
		if (remoteNode == null) {
			return null;
		}

		this.identifier = instanceId;
		return CommonUtils.getApplication(instanceId);
	}

	public String toString() {
		return String.format("<remote-resource| id= %s>", this.identifier);
	}

	public String getIdentifier() {
		return identifier;
	}

	public void setIdentifier(String identifier) {
		this.identifier = identifier;
	}

	public Environment getEnvironment() {
		return environment;
	}

	public void setEnvironment(Environment environment) {
		this.environment = environment;
	}

}
