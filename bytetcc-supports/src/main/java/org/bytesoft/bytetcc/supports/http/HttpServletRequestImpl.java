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
package org.bytesoft.bytetcc.supports.http;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;

public class HttpServletRequestImpl extends HttpServletRequestWrapper {
	private final ByteArrayInputStream input;

	public HttpServletRequestImpl(HttpServletRequest request) {
		super(request);
		this.input = null;
	}

	public HttpServletRequestImpl(HttpServletRequest request, byte[] byteArray) {
		super(request);
		this.input = new ByteArrayInputStream(byteArray);
	}

	public ServletInputStream getInputStream() throws IOException {
		return this.input == null ? super.getInputStream() : new ServletInputStreamImpl(this.input);
	}

}
