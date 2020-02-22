/**
 * Copyright 2014-2019 yangming.liu<bytefox@126.com>.
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
package org.bytesoft.bytetcc.supports.svc.filter;

import java.io.IOException;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.bytesoft.bytetcc.supports.http.HttpServletResponseImpl;
import org.bytesoft.bytetcc.supports.svc.ServiceException;
import org.bytesoft.bytetcc.supports.svc.client.ServiceResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public class ServiceResponseWrapFilter implements Filter {
	static final Logger logger = LoggerFactory.getLogger(ServiceResponseWrapFilter.class);

	public static final String CONTENT_TYPE_JSON = "application/json";
	public static final String CONTENT_TYPE_TEXT_HTML = "text/html";
	public static final String CONTENT_TYPE_TEXT_PLAIN = "text/plain";
	public static final String CONTENT_LEN = "Content-Length";
	public static final String CONTENT_TYPE = "Content-Type";
	public static final String HEADER_DISABLE_FLAG = "X-BYTETCC-DISABLED";

	public static final String KEY_RETURN_STATUS = "status";
	public static final String KEY_RETURN_VALUE = "result";
	public static final String KEY_RETURN_MESSAGE = "message";

	public static final String KEY_PATTERN_NUMBER = "\\d+(\\.\\d+)?";

	public static final String KEY_OBJECT_NULL = "null";
	public static final String KEY_BOOLEAN_TRUE = "true";
	public static final String KEY_BOOLEAN_FALSE = "false";

	private final ObjectMapper mapper = //
			new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

	public void init(FilterConfig filterConfig) throws ServletException {
		mapper.setSerializationInclusion(Include.ALWAYS);
	}

	public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
			throws IOException, ServletException {
		String disableValue = ((HttpServletRequest) request).getHeader(HEADER_DISABLE_FLAG);
		if (StringUtils.equalsIgnoreCase(disableValue, KEY_BOOLEAN_TRUE)) {
			chain.doFilter(request, response);
		} else {
			this.invokeFilter((HttpServletRequest) request, (HttpServletResponse) response, chain);
		}
	}

	public void invokeFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws IOException, ServletException {
		HttpServletResponseImpl resp = new HttpServletResponseImpl(response);

		Throwable error = null;
		try {
			chain.doFilter(request, resp);
		} catch (ServletException ex) {
			error = (Throwable) ex.getCause();
		} catch (Exception ex) {
			error = ex;
		}

		int statusCode = resp.getStatusCode();
		String statusMesg = resp.getStatusMesg();

		if (statusCode >= HttpServletResponse.SC_MULTIPLE_CHOICES && statusCode < HttpServletResponse.SC_BAD_REQUEST) {
			response.sendRedirect(resp.getLocation());
			return; // response.setStatus(statusCode);
		} else if (resp.isRdrctFlag()) {
			response.sendRedirect(resp.getLocation());
			return; // response.setStatus(statusCode);
		}

		if (statusCode >= HttpServletResponse.SC_BAD_REQUEST
				&& statusCode < HttpServletResponse.SC_INTERNAL_SERVER_ERROR) {
			error = new IllegalStateException(
					String.format("Client request error: status= %s, message= %s", statusCode, statusMesg));
		} else if (error == null && statusCode >= HttpServletResponse.SC_INTERNAL_SERVER_ERROR) {
			error = new IllegalStateException(
					String.format("Server response error: status= %s, message= %s", statusCode, statusMesg));
		}

		String responseContentType = resp.getContentType();
		byte[] responseByteArray = resp.getByteArray();

		boolean responseTextFlag = this.contentTypeMatched(responseContentType, CONTENT_TYPE_TEXT_PLAIN)
				|| this.contentTypeMatched(responseContentType, CONTENT_TYPE_TEXT_HTML);

		boolean wrapDisabled = StringUtils.equalsIgnoreCase(resp.getHeader(HEADER_DISABLE_FLAG), "true");
		boolean wrapRequired = false;
		if (wrapDisabled) {
			wrapRequired = false;
		} else if (error == null) {
			wrapRequired = StringUtils.isBlank(responseContentType)
					? (responseByteArray == null || responseByteArray.length == 0)
					: (this.responseContentTypeIsJson(resp) || responseTextFlag);
		} else {
			wrapRequired = true;
		}

		ServletOutputStream output = response.getOutputStream();
		if (wrapRequired == false) {
			response.setContentLength(responseByteArray.length);
			response.setContentType(responseContentType);

			if (error == null) {
				this.copyResponseHeaders(resp, response);
				this.copyResponseCookies(resp, response);
				output.write(responseByteArray);
				return;
			} else if (IOException.class.isInstance(error)) {
				throw (IOException) error;
			} else if (ServletException.class.isInstance(error)) {
				throw (ServletException) error;
			} else if (RuntimeException.class.isInstance(error)) {
				throw (RuntimeException) error;
			} else {
				throw new RuntimeException(error);
			}
		}

		response.setContentType(StringUtils.isBlank(responseContentType) ? CONTENT_TYPE_JSON : responseContentType);

		if (error == null) {
			this.writeSuccessResponse(output, responseByteArray);
			this.copyResponseHeaders(resp, response);
			this.copyResponseCookies(resp, response);
		} else if (ServiceException.class.isInstance(error)) {
			Throwable cause = error.getCause();
			if (cause == null) {
				logger.debug("Service response failed: uri= {}", request.getRequestURI(), error);
			} else {
				logger.warn("Service response failed: uri= {}", request.getRequestURI(), cause);
			}
			this.writeFailureResponse(output, ((ServiceException) error).getErrCode(), error.getMessage());
		} else {
			logger.error("Service response error: uri= {}", request.getRequestURI(), error);
			this.writeFailureResponse(output, ServiceResponse.STATUS_SYSTEM_FAILURE, error.getMessage());
		}
	}

	protected void writeSuccessResponse(ServletOutputStream output, byte[] byteArray) throws IOException {
		StringBuilder ber = new StringBuilder();
		ber.append("\"").append(KEY_RETURN_STATUS).append("\"");
		ber.append(":");
		ber.append("\"").append(ServiceResponse.STATUS_HANDLE_SUCCESS).append("\"");
		output.write("{".getBytes());
		output.write(ber.toString().getBytes());
		output.write(",".getBytes());
		output.write("\"".getBytes());
		output.write(KEY_RETURN_VALUE.getBytes());
		output.write("\":".getBytes());

		Class<?> clazz = null;
		try {
			Object target = byteArray == null || byteArray.length == 0 ? null
					: this.mapper.readValue(byteArray, Object.class);
			clazz = target == null ? null : target.getClass();
		} catch (JsonParseException jpex) {
			clazz = String.class;
		} catch (JsonMappingException jpex) {
			clazz = String.class;
		}

		if (clazz == null) {
			output.write(KEY_OBJECT_NULL.getBytes());
		} else if (Number.class.isAssignableFrom(clazz)) {
			output.write(byteArray);
		} else if (Boolean.class.isAssignableFrom(clazz)) {
			output.write(byteArray);
		} else if (List.class.isAssignableFrom(clazz)) {
			output.write(byteArray);
		} else if (Map.class.isAssignableFrom(clazz)) {
			output.write(byteArray);
		} else {
			output.write("\"".getBytes());
			output.write(byteArray);
			output.write("\"".getBytes());
		}
		output.write("}".getBytes());
	}

	protected void writeFailureResponse(ServletOutputStream output, String status, String message) throws IOException {
		StringBuilder ber = new StringBuilder();
		ber.append("{");

		ber.append("\"").append(KEY_RETURN_STATUS).append("\"");
		ber.append(":");
		ber.append("\"").append(status).append("\"");

		ber.append(",");

		ber.append("\"").append(KEY_RETURN_MESSAGE).append("\"");
		ber.append(":");
		if (StringUtils.isBlank(message)) {
			ber.append("null");
		} else {
			ber.append("\"").append(message).append("\"");
		}

		ber.append("}");

		output.write(ber.toString().getBytes());
	}

	protected void copyResponseHeaders(HttpServletResponseImpl source, HttpServletResponse target) {
		Map<String, Object> headerMap = source.getHeaderMap();
		for (Iterator<Map.Entry<String, Object>> itr = headerMap.entrySet().iterator(); itr.hasNext();) {
			Map.Entry<String, Object> entry = itr.next();
			String headerKey = entry.getKey();
			Object headerVal = entry.getValue();

			if (StringUtils.equalsIgnoreCase(CONTENT_TYPE, headerKey)) {
				continue;
			} else if (StringUtils.equalsIgnoreCase(CONTENT_LEN, headerKey)) {
				continue;
			}

			if (Date.class.isInstance(headerVal)) {
				target.addDateHeader(headerKey, ((Date) headerVal).getTime());
			} else if (Integer.class.isInstance(headerVal) || Integer.TYPE.isInstance(headerVal)) {
				target.addIntHeader(headerKey, ((Integer) headerVal).intValue());
			} else {
				target.addHeader(headerKey, headerVal == null ? "" : String.valueOf(headerVal));
			}
		}
	}

	protected void copyResponseCookies(HttpServletResponseImpl source, HttpServletResponse target) {
		List<Cookie> cookies = source.getCookies();
		for (int i = 0; cookies != null && i < cookies.size(); i++) {
			Cookie cookie = cookies.get(i);
			target.addCookie(cookie);
		}
	}

	public void destroy() {
	}

	protected boolean requestContentTypeIsJson(HttpServletRequest request) {
		String requestContentType = request.getContentType();
		return this.contentTypeIsJson(requestContentType);
	}

	protected boolean responseContentTypeIsJson(HttpServletResponse response) {
		String responseContentType = response.getContentType();
		return this.contentTypeIsJson(responseContentType);
	}

	protected boolean contentTypeIsJson(String contentType) {
		if (StringUtils.isBlank(contentType)) {
			return false;
		}

		String[] values = contentType.split("\\s*;\\s*");
		return StringUtils.equalsIgnoreCase(values[0], CONTENT_TYPE_JSON);
	}

	protected boolean contentTypeMatched(String contentType, String targetContentType) {
		if (StringUtils.isBlank(contentType) || targetContentType == null) {
			return false;
		}

		String[] values = contentType.split("\\s*;\\s*");
		return StringUtils.equalsIgnoreCase(values[0], targetContentType);
	}

}
