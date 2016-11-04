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
import java.io.Serializable;
import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CompensableInvocationInfo implements Serializable {
	private static final long serialVersionUID = 1L;
	static final Logger logger = LoggerFactory.getLogger(CompensableInvocationInfo.class);

	private String declaringClass;
	private String methodName;
	private String[] parameterTypeArray;
	private Object[] args;
	private String confirmableKey;
	private String cancellableKey;
	private Object identifier;

	protected Object readResolve() throws ObjectStreamException {
		CompensableInvocationImpl that = new CompensableInvocationImpl();

		that.setArgs(this.args);
		that.setConfirmableKey(this.confirmableKey);
		that.setCancellableKey(this.cancellableKey);
		that.setIdentifier(this.identifier);

		Class<?> clazz = null;
		ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
		try {
			clazz = classLoader.loadClass(this.declaringClass);
		} catch (ClassNotFoundException ex) {
			logger.error("Error occurred while loading class: {}", this.declaringClass, ex);
			return that;
		}

		Class<?>[] parameterTypes = new Class<?>[this.parameterTypeArray == null ? 0 : this.parameterTypeArray.length];
		for (int i = 0; this.parameterTypeArray != null && i < this.parameterTypeArray.length; i++) {
			String className = this.parameterTypeArray[i];
			if (Double.TYPE.getName().equals(className)) {
				parameterTypes[i] = Double.TYPE;
			} else if (Long.TYPE.getName().equals(className)) {
				parameterTypes[i] = Long.TYPE;
			} else if (Integer.TYPE.getName().equals(className)) {
				parameterTypes[i] = Integer.TYPE;
			} else if (Float.TYPE.getName().equals(className)) {
				parameterTypes[i] = Float.TYPE;
			} else if (Short.TYPE.getName().equals(className)) {
				parameterTypes[i] = Short.TYPE;
			} else if (Character.TYPE.getName().equals(className)) {
				parameterTypes[i] = Character.TYPE;
			} else if (Boolean.TYPE.getName().equals(className)) {
				parameterTypes[i] = Boolean.TYPE;
			} else if (Byte.TYPE.getName().equals(className)) {
				parameterTypes[i] = Byte.TYPE;
			} else {
				try {
					parameterTypes[i] = Class.forName(className, false, classLoader); // classLoader.loadClass(className);
				} catch (ClassNotFoundException ex) {
					logger.error("Error occurred while loading class: {}", className, ex);
					return that;
				}
			}
		}

		try {
			that.setMethod(clazz.getDeclaredMethod(this.methodName, parameterTypes));
		} catch (NoSuchMethodException ex) {
			logger.error("Error occurred: class= {}, method= {}, parameters= {}" //
					, this.declaringClass, this.methodName, Arrays.toString(this.parameterTypeArray), ex);
		} catch (SecurityException ex) {
			logger.error("Error occurred: class= {}, method= {}, parameters= {}" //
					, this.declaringClass, this.methodName, Arrays.toString(this.parameterTypeArray), ex);
		}

		return that;
	}

	public String getDeclaringClass() {
		return declaringClass;
	}

	public void setDeclaringClass(String declaringClass) {
		this.declaringClass = declaringClass;
	}

	public String getMethodName() {
		return methodName;
	}

	public void setMethodName(String methodName) {
		this.methodName = methodName;
	}

	public String[] getParameterTypeArray() {
		return parameterTypeArray;
	}

	public void setParameterTypeArray(String[] parameterTypeArray) {
		this.parameterTypeArray = parameterTypeArray;
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
