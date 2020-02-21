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
package org.bytesoft.bytetcc.supports.svc.client;

public class ServiceResponse<T> {
	public static final String STATUS_HANDLE_SUCCESS = "000000";
	public static final String STATUS_SYSTEM_FAILURE = "999999";

	private String status;
	private T result;
	private String message;

	public ServiceResponse() {
		this(STATUS_HANDLE_SUCCESS, null, null);
	}

	public ServiceResponse(T result) {
		this(result, null);
	}

	public ServiceResponse(T result, String message) {
		this(STATUS_HANDLE_SUCCESS, result, message);
	}

	public ServiceResponse(String status, T result, String message) {
		this.status = status;
		this.result = result;
		this.message = message;
	}

	public ServiceResponse(String status, String message) {
		this(status, null, message);
	}

	public String getStatus() {
		return status;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	public String getMessage() {
		return message;
	}

	public void setMessage(String message) {
		this.message = message;
	}

	public T getResult() {
		return result;
	}

	public void setResult(T result) {
		this.result = result;
	}

}
