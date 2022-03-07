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

import java.io.IOException;
import java.io.InputStream;

import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;

public class ServletInputStreamImpl extends ServletInputStream {
	private InputStream input;

	public ServletInputStreamImpl(InputStream input) {
		this.input = input;
	}

	public int readLine(byte[] b, int off, int len) throws IOException {
		return this.input.read(b, off, len);
	}

	public int hashCode() {
		return input.hashCode();
	}

	public int read(byte[] b) throws IOException {
		return input.read(b);
	}

	public boolean equals(Object obj) {
		return input.equals(obj);
	}

	public int read(byte[] b, int off, int len) throws IOException {
		return input.read(b, off, len);
	}

	public long skip(long n) throws IOException {
		return input.skip(n);
	}

	public String toString() {
		return input.toString();
	}

	public int available() throws IOException {
		return input.available();
	}

	public void close() throws IOException {
		input.close();
	}

	public void mark(int readlimit) {
		input.mark(readlimit);
	}

	public void reset() throws IOException {
		input.reset();
	}

	public boolean markSupported() {
		return input.markSupported();
	}

	public boolean isFinished() {
		return false;
	}

	public boolean isReady() {
		return false;
	}

	public void setReadListener(ReadListener listener) {
	}

	public int read() throws IOException {
		return this.input.read();
	}

}
