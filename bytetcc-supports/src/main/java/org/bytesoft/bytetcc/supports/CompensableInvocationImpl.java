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

import org.bytesoft.compensable.CompensableInvocation;

public class CompensableInvocationImpl implements CompensableInvocation {

	private String declaringClass;
	private String methodName;
	private String[] parameterTypeArray;
	private transient Method method;
	private Object[] args;
	private String confirmableKey;
	private String cancellableKey;
	private Object identifier;

	private boolean simplified;
	private boolean enlisted;

	protected Object writeReplace() throws ObjectStreamException {
		CompensableInvocationInfo that = new CompensableInvocationInfo();

		that.setArgs(this.args);
		that.setConfirmableKey(this.confirmableKey);
		that.setCancellableKey(this.cancellableKey);
		that.setIdentifier(this.identifier);
		that.setSimplified(this.simplified);

		that.setDeclaringClass(this.getDeclaringClass());
		that.setMethodName(this.getMethodName());

		that.setParameterTypeArray(this.getParameterTypeArray());

		return that;
	}

	private void initMethod(Method method) {
		this.declaringClass = method.getDeclaringClass().getName();
		this.methodName = method.getName();
		Class<?>[] parameterTypes = method.getParameterTypes();
		String[] parameterTypeArray = new String[parameterTypes.length];
		for (int i = 0; i < parameterTypes.length; i++) {
			Class<?> parameterType = parameterTypes[i];
			parameterTypeArray[i] = parameterType.getName();
		}
		this.parameterTypeArray = parameterTypeArray;
	}

	public String getDeclaringClass() {
		return this.declaringClass;
	}

	public void setDeclaringClass(String declaringClass) {
		this.declaringClass = declaringClass;
	}

	public String getMethodName() {
		return this.methodName;
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

	public Method getMethod() {
		return method;
	}

	public void setMethod(Method method) {
		this.initMethod(method);
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

	public boolean isEnlisted() {
		return enlisted;
	}

	public void setEnlisted(boolean enlisted) {
		this.enlisted = enlisted;
	}

	public boolean isSimplified() {
		return simplified;
	}

	public void setSimplified(boolean simplified) {
		this.simplified = simplified;
	}

}
