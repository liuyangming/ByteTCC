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
import java.lang.reflect.Type;
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
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.boot.autoconfigure.http.HttpMessageConverters;
import org.springframework.cloud.openfeign.support.ResponseEntityDecoder;
import org.springframework.cloud.openfeign.support.SpringDecoder;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import feign.FeignException;
import feign.Request;
import feign.Response;
import feign.codec.DecodeException;

public class CompensableFeignDecoder implements feign.codec.Decoder, InitializingBean, ApplicationContextAware {
	static Logger logger = LoggerFactory.getLogger(CompensableFeignDecoder.class);

	static final String HEADER_TRANCACTION_KEY = "X-BYTETCC-TRANSACTION"; // org.bytesoft.bytetcc.transaction
	static final String HEADER_PROPAGATION_KEY = "X-BYTETCC-PROPAGATION"; // org.bytesoft.bytetcc.propagation
	static final String HEADER_RECURSIVELY_KEY = "X-BYTETCC-RECURSIVELY"; // org.bytesoft.bytetcc.recursively

	private ApplicationContext applicationContext;
	private feign.codec.Decoder delegate;

	private ObjectFactory<HttpMessageConverters> objectFactory;

	public CompensableFeignDecoder() {
	}

	public CompensableFeignDecoder(feign.codec.Decoder decoder) {
		this.delegate = decoder;
	}

	public void afterPropertiesSet() throws Exception {
		if (this.delegate == null) {
			this.invokeAfterPropertiesSet();
		} // end-if (this.delegate == null)
	}

	public void invokeAfterPropertiesSet() throws Exception {
		feign.codec.Decoder feignDecoder = null;

		String[] beanNameArray = this.applicationContext.getBeanNamesForType(feign.codec.Decoder.class);
		for (int i = 0; beanNameArray != null && i < beanNameArray.length; i++) {
			String beanName = beanNameArray[i];
			Object beanInst = this.applicationContext.getBean(beanName);
			if (CompensableFeignDecoder.class.isInstance(beanInst)) {
				continue;
			} else if (feignDecoder != null) {
				throw new RuntimeException("There are more than one feign.codec.Decoder exists!");
			} else {
				feignDecoder = (feign.codec.Decoder) beanInst;
			}
		}

		if (feignDecoder == null) {
			feignDecoder = new ResponseEntityDecoder(new SpringDecoder(this.objectFactory));
		} // end-if (feignDecoder == null)

		this.delegate = feignDecoder;
	}

	public Object decode(Response resp, Type type) throws IOException, DecodeException, FeignException {
		Request request = resp.request();

		String reqTransactionStr = this.getHeaderValue(request, HEADER_TRANCACTION_KEY);
		String reqPropagationStr = this.getHeaderValue(request, HEADER_PROPAGATION_KEY);

		String respTransactionStr = this.getHeaderValue(resp, HEADER_TRANCACTION_KEY);
		String respPropagationStr = this.getHeaderValue(resp, HEADER_PROPAGATION_KEY);
		String respRecursivelyStr = this.getHeaderValue(resp, HEADER_RECURSIVELY_KEY);

		if (StringUtils.isBlank(reqTransactionStr)) {
			return this.delegate.decode(resp, type);
		} else if (StringUtils.isBlank(reqPropagationStr)) {
			return this.delegate.decode(resp, type);
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
			logger.error("Error occurred while decoding response({})!", resp, ex);
		}

		Object value = this.delegate.decode(resp, type);
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

	public ObjectFactory<HttpMessageConverters> getObjectFactory() {
		return objectFactory;
	}

	public void setObjectFactory(ObjectFactory<HttpMessageConverters> objectFactory) {
		this.objectFactory = objectFactory;
	}

	public feign.codec.Decoder getDelegate() {
		return delegate;
	}

	public void setDelegate(feign.codec.Decoder delegate) {
		this.delegate = delegate;
	}

	public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
		this.applicationContext = applicationContext;
	}

}
