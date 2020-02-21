/**
 * Copyright 2014-2020 yangming.liu<bytefox@126.com>.
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
package org.bytesoft.bytetcc.supports.svc.mvc;

import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.bytesoft.bytetcc.supports.svc.ServiceException;
import org.bytesoft.bytetcc.supports.svc.client.ServiceResponse;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

public class ServiceErrorResolver implements HandlerExceptionResolver, EnvironmentAware {
	static final String KEY_RESPONSE_WRAP_ENABLED = "org.bytesoft.bytetcc.service.wrap.enabled";

	private Environment environment;

	public ModelAndView resolveException(HttpServletRequest request, HttpServletResponse response, Object handler,
			Exception ex) {
		Boolean wrapEnabled = this.environment.getProperty(KEY_RESPONSE_WRAP_ENABLED, Boolean.class, false);
		if (wrapEnabled == null || wrapEnabled == false) {
			if (RuntimeException.class.isInstance(ex)) {
				throw (RuntimeException) ex;
			} else {
				throw new RuntimeException(ex);
			}
		} // end-if (wrapEnabled == null || wrapEnabled == false)

		if (MethodArgumentNotValidException.class.isInstance(ex)) {
			this.handleError((MethodArgumentNotValidException) ex);
		}

		if (ServiceException.class.isInstance(ex)) {
			throw (ServiceException) ex;
		} else {
			ServiceException error = new ServiceException(ServiceResponse.STATUS_SYSTEM_FAILURE,
					"System error, please try again later!");
			error.initCause(ex);
			throw error;
		}
	}

	private void handleError(MethodArgumentNotValidException error) throws ServiceException {
		if (error.getBindingResult().hasErrors()) {
			List<ObjectError> errors = error.getBindingResult().getAllErrors();
			StringBuilder ber = new StringBuilder();
			for (int i = 0; errors != null && i < errors.size(); i++) {
				ObjectError err = errors.get(i);
				ber.append(i == 0 ? "" : "; ");
				ber.append(err.getDefaultMessage());
			}
			throw new ServiceException(ServiceResponse.STATUS_SYSTEM_FAILURE, ber.toString());
		}
	}

	public Environment getEnvironment() {
		return environment;
	}

	public void setEnvironment(Environment environment) {
		this.environment = environment;
	}

}
