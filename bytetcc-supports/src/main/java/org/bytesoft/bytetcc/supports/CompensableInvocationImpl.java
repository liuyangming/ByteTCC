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

import java.io.ObjectStreamException;
import java.lang.reflect.Method;
import java.util.Arrays;

import org.bytesoft.compensable.CompensableInvocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CompensableInvocationImpl implements CompensableInvocation {
	private static final long serialVersionUID = 1L;
	static final Logger logger = LoggerFactory.getLogger(CompensableInvocationImpl.class);

	private transient Method method;
	private String declaringClass;
	private String methodName;
	private String[] parameterTypeArray;

	private Object[] args;
	private String confirmableKey;
	private String cancellableKey;
	private Object identifier;

	protected Object readResolve() throws ObjectStreamException {
		Class<?> clazz = null;
		ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
		try {
			clazz = classLoader.loadClass(this.declaringClass);
		} catch (ClassNotFoundException ex) {
			logger.error("Error occurred while loading class: {}", this.declaringClass, ex);
			return this;
		}

		Class<?>[] parameterTypes = new Class<?>[this.parameterTypeArray == null ? 0 : this.parameterTypeArray.length];
		for (int i = 0; this.parameterTypeArray != null && i < this.parameterTypeArray.length; i++) {
			String className = this.parameterTypeArray[i];
			try {
				parameterTypes[i] = classLoader.loadClass(className);
			} catch (ClassNotFoundException ex) {
				logger.error("Error occurred while loading class: {}", className, ex);
				return this;
			}
		}

		try {
			this.setMethod(clazz.getDeclaredMethod(this.methodName, parameterTypes));
		} catch (NoSuchMethodException ex) {
			logger.error("Error occurred: class= {}, method= {}, parameters= {}" //
					, this.declaringClass, this.methodName, Arrays.toString(this.parameterTypeArray), ex);
		} catch (SecurityException ex) {
			logger.error("Error occurred: class= {}, method= {}, parameters= {}" //
					, this.declaringClass, this.methodName, Arrays.toString(this.parameterTypeArray), ex);
		}

		return this;
	}

	protected Object writeReplace() throws ObjectStreamException {

		this.declaringClass = this.method.getDeclaringClass().getCanonicalName();
		this.methodName = this.method.getName();

		Class<?>[] parameterTypes = this.method.getParameterTypes();
		this.parameterTypeArray = new String[parameterTypes.length];
		for (int i = 0; i < parameterTypes.length; i++) {
			Class<?> parameterType = parameterTypes[i];
			this.parameterTypeArray[i] = parameterType.getCanonicalName();
		}

		return this;
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

	public Object getIdentifier() {
		return identifier;
	}

	public void setIdentifier(Object identifier) {
		this.identifier = identifier;
	}

}
