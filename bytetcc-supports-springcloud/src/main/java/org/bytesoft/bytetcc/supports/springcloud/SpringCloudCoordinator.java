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
package org.bytesoft.bytetcc.supports.springcloud;

import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;

import org.apache.commons.lang3.StringUtils;
import org.bytesoft.bytejta.supports.wire.RemoteCoordinator;
import org.bytesoft.common.utils.ByteUtils;
import org.bytesoft.common.utils.CommonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

public class SpringCloudCoordinator implements InvocationHandler {
	static final Logger logger = LoggerFactory.getLogger(SpringCloudCoordinator.class);

	private String identifier;

	public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
		Class<?> clazz = method.getDeclaringClass();
		String methodName = method.getName();
		if (Object.class.equals(clazz)) {
			return method.invoke(this, args);
		} else if (RemoteCoordinator.class.equals(clazz)) {
			if ("getIdentifier".equals(methodName)) {
				return this.identifier;
			} else if ("getApplication".equals(methodName)) {
				int firstIndex = this.identifier.indexOf(":");
				int lastIndex = this.identifier.lastIndexOf(":");
				return firstIndex <= 0 || lastIndex <= 0 || firstIndex > lastIndex //
						? null : this.identifier.subSequence(firstIndex + 1, lastIndex);
			} else {
				throw new XAException(XAException.XAER_RMFAIL);
			}
		} else if (XAResource.class.equals(clazz)) {
			if ("prepare".equals(methodName)) {
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
			RestTemplate transactionRestTemplate = SpringCloudBeanRegistry.getInstance().getRestTemplate();
			RestTemplate restTemplate = transactionRestTemplate == null ? new RestTemplate() : transactionRestTemplate;

			StringBuilder ber = new StringBuilder();

			int firstIndex = this.identifier.indexOf(":");
			int lastIndex = this.identifier.lastIndexOf(":");
			String prefix = firstIndex <= 0 ? null : this.identifier.substring(0, firstIndex);
			String suffix = lastIndex <= 0 ? null : this.identifier.substring(lastIndex + 1);

			ber.append("http://");
			ber.append(prefix == null || suffix == null ? null : prefix + ":" + suffix);
			ber.append("/org/bytesoft/bytetcc/");
			ber.append(method.getName());
			for (int i = 0; i < args.length; i++) {
				Serializable arg = (Serializable) args[i];
				ber.append("/").append(this.serialize(arg));
			}

			ResponseEntity<?> response = restTemplate.postForEntity(ber.toString(), null, returnType, new Object[0]);

			return response.getBody();
		} catch (HttpClientErrorException ex) {
			throw new XAException(XAException.XAER_RMFAIL);
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
				throw new XAException(errorCode);
			} else {
				throw new XAException(XAException.XAER_RMERR);
			}
		} catch (Exception ex) {
			throw new XAException(XAException.XAER_RMERR);
		}

	}

	public Object invokeGetCoordinator(Object proxy, Method method, Object[] args) throws Throwable {

		Class<?> returnType = method.getReturnType();
		try {
			RestTemplate transactionRestTemplate = SpringCloudBeanRegistry.getInstance().getRestTemplate();
			RestTemplate restTemplate = transactionRestTemplate == null ? new RestTemplate() : transactionRestTemplate;

			StringBuilder ber = new StringBuilder();

			int firstIndex = this.identifier.indexOf(":");
			int lastIndex = this.identifier.lastIndexOf(":");
			String prefix = firstIndex <= 0 ? null : this.identifier.substring(0, firstIndex);
			String suffix = lastIndex <= 0 ? null : this.identifier.substring(lastIndex + 1);

			ber.append("http://");
			ber.append(prefix == null || suffix == null ? null : prefix + ":" + suffix);
			ber.append("/org/bytesoft/bytetcc/");
			ber.append(method.getName());
			for (int i = 0; i < args.length; i++) {
				Serializable arg = (Serializable) args[i];
				ber.append("/").append(this.serialize(arg));
			}

			ResponseEntity<?> response = restTemplate.getForEntity(ber.toString(), returnType, new Object[0]);

			return response.getBody();
		} catch (HttpClientErrorException ex) {
			throw new XAException(XAException.XAER_RMFAIL);
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
				throw new XAException(errorCode);
			} else {
				throw new XAException(XAException.XAER_RMERR);
			}
		} catch (Exception ex) {
			throw new XAException(XAException.XAER_RMERR);
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
			byte[] byteArray = CommonUtils.serializeObject(arg);
			return ByteUtils.byteArrayToString(byteArray);
		}
	}

	public String getIdentifier() {
		return identifier;
	}

	public void setIdentifier(String identifier) {
		this.identifier = identifier;
	}

}
