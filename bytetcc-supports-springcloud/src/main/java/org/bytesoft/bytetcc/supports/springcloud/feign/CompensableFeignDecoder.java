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
import java.util.Collection;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.bytesoft.bytejta.supports.rpc.TransactionResponseImpl;
import org.bytesoft.bytejta.supports.wire.RemoteCoordinator;
import org.bytesoft.bytetcc.supports.springcloud.SpringCloudBeanRegistry;
import org.bytesoft.common.utils.ByteUtils;
import org.bytesoft.common.utils.CommonUtils;
import org.bytesoft.compensable.TransactionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import feign.FeignException;
import feign.Request;
import feign.Response;
import feign.codec.DecodeException;

public class CompensableFeignDecoder implements feign.codec.Decoder {
	static Logger logger = LoggerFactory.getLogger(CompensableFeignDecoder.class);

	static final String HEADER_TRANCACTION_KEY = "org.bytesoft.bytetcc.transaction";
	static final String HEADER_PROPAGATION_KEY = "org.bytesoft.bytetcc.propagation";

	private feign.codec.Decoder delegate;

	public CompensableFeignDecoder() {
	}

	public CompensableFeignDecoder(feign.codec.Decoder decoder) {
		this.delegate = decoder;
	}

	public Object decode(Response resp, Type type) throws IOException, DecodeException, FeignException {
		Request request = resp.request();

		String reqTransactionStr = this.getHeaderValue(request, HEADER_TRANCACTION_KEY);
		String reqPropagationStr = this.getHeaderValue(request, HEADER_PROPAGATION_KEY);

		String respTransactionStr = this.getHeaderValue(resp, HEADER_TRANCACTION_KEY);
		String respPropagationStr = this.getHeaderValue(resp, HEADER_PROPAGATION_KEY);

		if (StringUtils.isBlank(reqTransactionStr)) {
			return this.delegate.decode(resp, type);
		} else if (StringUtils.isBlank(reqPropagationStr)) {
			return this.delegate.decode(resp, type);
		}

		try {
			String transactionStr = StringUtils.isBlank(respTransactionStr) ? reqTransactionStr : respTransactionStr;
			String propagationStr = StringUtils.isBlank(respPropagationStr) ? reqPropagationStr : respPropagationStr;

			byte[] byteArray = ByteUtils.stringToByteArray(transactionStr);
			TransactionContext transactionContext = (TransactionContext) CommonUtils.deserializeObject(byteArray);

			SpringCloudBeanRegistry beanRegistry = SpringCloudBeanRegistry.getInstance();
			RemoteCoordinator remoteCoordinator = beanRegistry.getConsumeCoordinator(propagationStr);

			TransactionResponseImpl response = new TransactionResponseImpl();
			response.setTransactionContext(transactionContext);
			response.setSourceTransactionCoordinator(remoteCoordinator);
		} catch (IOException ex) {
			logger.error("Error occurred while decoding response({})!", resp, ex);
		}

		return this.delegate.decode(resp, type);
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

	public feign.codec.Decoder getDelegate() {
		return delegate;
	}

	public void setDelegate(feign.codec.Decoder delegate) {
		this.delegate = delegate;
	}

}
