/**
 * Copyright 2014-2016 yangming.liu<bytefox@126.com>.
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
package org.bytesoft.compensable;

public class CompensableException extends RuntimeException {
	private static final long serialVersionUID = 1L;

	public CompensableException() {
		super();
	}

	public CompensableException(String message) {
		super(message);
	}

	public CompensableException(String message, Throwable cause) {
		super(message, cause);
	}

	public CompensableException(Throwable cause) {
		super(cause);
	}

}
