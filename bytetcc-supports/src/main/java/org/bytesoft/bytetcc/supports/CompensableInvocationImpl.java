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
package org.bytesoft.bytetcc.supports;

import java.lang.reflect.Method;

import org.bytesoft.compensable.CompensableInvocation;

public class CompensableInvocationImpl implements CompensableInvocation {
	private static final long serialVersionUID = 1L;

	private Method method;
	private Object[] args;
	private String confirmableKey;
	private String cancellableKey;
	private boolean available = true;
	private Object identifier;

	public void markUnavailable() {
		this.available = false;
	}

	public Method getMethod() {
		return method;
	}

	public void setMethod(Method method) {
		this.method = method;
	}

	public Object[] getArgs() {
		return args;
	}

	public void setArgs(Object[] args) {
		this.args = args;
	}

	public String getConfirmableKey() {
		return confirmableKey;
	}

	public void setConfirmableKey(String confirmableKey) {
		this.confirmableKey = confirmableKey;
	}

	public String getCancellableKey() {
		return cancellableKey;
	}

	public void setCancellableKey(String cancellableKey) {
		this.cancellableKey = cancellableKey;
	}

	public boolean isAvailable() {
		return available;
	}

	public void setAvailable(boolean available) {
		this.available = available;
	}

	public Object getIdentifier() {
		return identifier;
	}

	public void setIdentifier(Object identifier) {
		this.identifier = identifier;
	}

}
