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
package org.bytesoft.bytetcc.supports.springcloud.feign;

import java.io.IOException;
import java.util.Base64;
import java.util.Collection;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.bytesoft.bytetcc.supports.springcloud.SpringCloudBeanRegistry;
import org.bytesoft.common.utils.SerializeUtils;
import org.bytesoft.compensable.TransactionContext;
import org.bytesoft.transaction.remote.RemoteCoordinator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import feign.Request;
import feign.Response;
import feign.codec.ErrorDecoder;

public class CompensableFeignErrorDecoder implements feign.codec.ErrorDecoder, InitializingBean, ApplicationContextAware {
	static Logger logger = LoggerFactory.getLogger(CompensableFeignErrorDecoder.class);

	static final String HEADER_TRANCACTION_KEY = "X-BYTETCC-TRANSACTION"; // org.bytesoft.bytetcc.transaction
	static final String HEADER_PROPAGATION_KEY = "X-BYTETCC-PROPAGATION"; // org.bytesoft.bytetcc.propagation
	static final String HEADER_RECURSIVELY_KEY = "X-BYTETCC-RECURSIVELY"; // org.bytesoft.bytetcc.recursively

	private ApplicationContext applicationContext;
	private feign.codec.ErrorDecoder delegate;

	public CompensableFeignErrorDecoder() {
	}

	public CompensableFeignErrorDecoder(feign.codec.ErrorDecoder decoder) {
		this.delegate = decoder;
	}

	public void afterPropertiesSet() throws Exception {
		if (this.delegate == null) {
			this.invokeAfterPropertiesSet();
		} // end-if (this.delegate == null)
	}

	public void invokeAfterPropertiesSet() throws Exception {
		feign.codec.ErrorDecoder errorDecoder = null;

		String[] beanNameArray = this.applicationContext.getBeanNamesForType(feign.codec.ErrorDecoder.class);
		for (int i = 0; beanNameArray != null && i < beanNameArray.length; i++) {
			String beanName = beanNameArray[i];
			Object beanInst = this.applicationContext.getBean(beanName);
			if (CompensableFeignErrorDecoder.class.isInstance(beanInst)) {
				continue;
			} else if (errorDecoder != null) {
				throw new RuntimeException("There are more than one feign.codec.ErrorDecoder exists!");
			} else {
				errorDecoder = (feign.codec.ErrorDecoder) beanInst;
			}
		}

		if (errorDecoder == null) {
			errorDecoder = new ErrorDecoder.Default();
		} // end-if (errorDecoder == null)

		this.delegate = errorDecoder;
	}

	public Exception decode(String methodKey, Response resp) {
		Request request = resp.request();

		String reqTransactionStr = this.getHeaderValue(request, HEADER_TRANCACTION_KEY);
		String reqPropagationStr = this.getHeaderValue(request, HEADER_PROPAGATION_KEY);

		String respTransactionStr = this.getHeaderValue(resp, HEADER_TRANCACTION_KEY);
		String respPropagationStr = this.getHeaderValue(resp, HEADER_PROPAGATION_KEY);
		String respRecursivelyStr = this.getHeaderValue(resp, HEADER_RECURSIVELY_KEY);

		if (StringUtils.isBlank(reqTransactionStr)) {
			return this.delegate.decode(methodKey, resp);
		} else if (StringUtils.isBlank(reqPropagationStr)) {
			return this.delegate.decode(methodKey, resp);
		}

		CompensableFeignResult result = new CompensableFeignResult();
		try {
			String transactionStr = StringUtils.isBlank(respTransactionStr) ? reqTransactionStr : respTransactionStr;
			String propagationStr = StringUtils.isBlank(respPropagationStr) ? reqPropagationStr : respPropagationStr;

			byte[] byteArray = Base64.getDecoder().decode(transactionStr); // ByteUtils.stringToByteArray(transactionStr);
			TransactionContext transactionContext = (TransactionContext) SerializeUtils.deserializeObject(byteArray);

			SpringCloudBeanRegistry beanRegistry = SpringCloudBeanRegistry.getInstance();
			RemoteCoordinator remoteCoordinator = beanRegistry.getConsumeCoordinator(propagationStr);

//			TransactionResponseImpl response = new TransactionResponseImpl();
//			response.setSourceTransactionCoordinator(remoteCoordinator);
//			response.setParticipantDelistFlag(StringUtils.equalsIgnoreCase(respRecursivelyStr, "TRUE"));

			result.setTransactionContext(transactionContext);
			result.setRemoteParticipant(remoteCoordinator);
			result.setParticipantValidFlag(StringUtils.equalsIgnoreCase(respRecursivelyStr, "TRUE") == false);
		} catch (IOException ex) {
			logger.error("Error occurred while decoding response: methodKey= {}, response= {}", methodKey, resp, ex);
		}

		Object value = this.delegate.decode(methodKey, resp);
		result.setError(true);
		result.setResult(value);
		return result;
	}

	private String getHeaderValue(Request req, String headerName) {
		Map<String, Collection<String>> headers = req.headers();
		Collection<String> values = headers.get(headerName);
		String value = null;
		if (values != null && values.isEmpty() == false) {
			String[] array = new String[values.size()];
			values.toArray(array);
			value = array[0];
		}
		return value;
	}

	private String getHeaderValue(Response resp, String headerName) {
		Map<String, Collection<String>> headers = resp.headers();
		Collection<String> values = headers.get(headerName);
		String value = null;
		if (values != null && values.isEmpty() == false) {
			String[] array = new String[values.size()];
			values.toArray(array);
			value = array[0];
		}
		return value;
	}

	public feign.codec.ErrorDecoder getDelegate() {
		return delegate;
	}

	public void setDelegate(feign.codec.ErrorDecoder delegate) {
		this.delegate = delegate;
	}

	public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
		this.applicationContext = applicationContext;
	}

}
