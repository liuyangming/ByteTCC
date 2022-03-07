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

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;

import org.apache.commons.io.IOUtils;

public class ServletOutputStreamImpl extends ServletOutputStream {
	private final ByteArrayOutputStream baos = new ByteArrayOutputStream();
	private final DataOutputStream output = new DataOutputStream(baos);

	private byte[] byteArray = null;

	public byte[] getByteArray() {
		if (this.byteArray != null) {
			return byteArray;
		} else {
			byte[] sourceByteArray = this.baos.toByteArray();
			byte[] targetByteArray = new byte[sourceByteArray.length];
			System.arraycopy(sourceByteArray, 0, targetByteArray, 0, sourceByteArray.length);

			this.byteArray = targetByteArray;
			return this.byteArray;
		}
	}

	public boolean isReady() {
		return true;
	}

	public void setWriteListener(WriteListener listener) {
	}

	public void write(int b) throws IOException {
		this.output.write(b);
	}

	public void print(String s) throws IOException {
		this.output.writeChars(s);
	}

	public void print(boolean b) throws IOException {
		this.output.writeBoolean(b);
	}

	public void print(char c) throws IOException {
		this.output.writeChar(c);
	}

	public void print(int i) throws IOException {
		this.output.writeInt(i);
	}

	public void print(long l) throws IOException {
		this.output.writeLong(l);
	}

	public void print(float f) throws IOException {
		this.output.writeFloat(f);
	}

	public void print(double d) throws IOException {
		this.output.writeDouble(d);
	}

	public void println() throws IOException {
		this.output.writeChars("\r\n");
	}

	public void println(String s) throws IOException {
		this.print(s);
		this.println();
	}

	public void println(boolean b) throws IOException {
		this.print(b);
		this.println();
	}

	public void println(char c) throws IOException {
		this.print(c);
		this.println();
	}

	public void println(int i) throws IOException {
		this.print(i);
		this.println();
	}

	public void println(long l) throws IOException {
		this.print(l);
		this.println();
	}

	public void println(float f) throws IOException {
		this.print(f);
		this.println();
	}

	public void println(double d) throws IOException {
		this.print(d);
		this.println();
	}

	public void write(byte[] b) throws IOException {
		this.output.write(b);
	}

	public void write(byte[] b, int off, int len) throws IOException {
		this.output.write(b, off, len);
	}

	public void flush() throws IOException {
		this.output.flush();
	}

	public void close() throws IOException {
		IOUtils.closeQuietly(this.output);
		IOUtils.closeQuietly(this.baos);
	}

}
