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
package org.bytesoft.bytetcc.internal;

import java.io.InvalidClassException;
import java.io.ObjectStreamException;
import java.io.Serializable;
import java.lang.reflect.Method;

import javax.transaction.SystemException;

import org.bytesoft.bytetcc.CompensableContext;
import org.bytesoft.bytetcc.CompensableInvocation;
import org.bytesoft.bytetcc.supports.CompensableSynchronization;
import org.bytesoft.bytetcc.transaction.TccTransactionImpl;
import org.bytesoft.transaction.xa.TransactionXid;

public class CompensableInvocationImpl extends CompensableSynchronization implements CompensableContext, CompensableInvocation {
	private static final long serialVersionUID = 1L;

	private transient Object identifier;
	private transient Method method;
	private String declaring;
	private String methodName;
	private String[] parameters;

	private Object[] args;
	private String confirmableKey;
	private String cancellableKey;
	private Serializable variable;

	private transient TccTransactionImpl transaction;

	public void suspend() {
	}

	public void resume() {
	}

	public Serializable getCompensableVariable() {
		return this.variable;
	}

	public void setCompensableVariable(Serializable variable) throws IllegalStateException {
		if (this.variable == null) {
			this.variable = variable;
		} else if (this.variable.equals(variable)) {
			// ignore
		} else {
			throw new IllegalStateException("The varable of current compensable-service has already been set!");
		}
	}

	public void afterInitialization(TransactionXid xid) {
	}

	public void beforeCompletion(TransactionXid xid) {
	}

	public void afterCompletion(TransactionXid xid, int status) {
	}

	public boolean isRollbackOnly() throws IllegalStateException {
		if (this.transaction == null) {
			throw new IllegalStateException();
		}
		return this.transaction.isRollbackOnly();
	}

	public void setRollbackOnly() throws IllegalStateException {
		if (this.transaction == null) {
			throw new IllegalStateException();
		}
		try {
			this.transaction.setRollbackOnly();
		} catch (SystemException ex) {
			throw new IllegalStateException(ex);
		}
	}

	protected Object writeReplace() throws ObjectStreamException {
		this.declaring = this.method.getDeclaringClass().getName();
		this.methodName = this.method.getName();
		Class<?>[] classes = this.method.getParameterTypes();
		this.parameters = new String[classes.length];
		for (int i = 0; i < classes.length; i++) {
			this.parameters[i] = classes[i].getName();
		}
		return this;
	}

	protected Object readResolve() throws ObjectStreamException {
		try {
			ClassLoader cl = Thread.currentThread().getContextClassLoader();
			Class<?>[] parameterTypes = new Class<?>[this.parameters == null ? 0 : this.parameters.length];
			Class<?> clazz = cl.loadClass(this.declaring);
			for (int i = 0; i < parameterTypes.length; i++) {
				parameterTypes[i] = cl.loadClass(this.parameters[i]);
			}
			this.method = clazz.getMethod(this.methodName, parameterTypes);
		} catch (ClassNotFoundException cnfex) {
			throw new InvalidClassException(cnfex.getMessage());
		} catch (NoSuchMethodException ex) {
			throw new InvalidClassException(ex.getMessage());
		} catch (SecurityException ex) {
			throw new InvalidClassException(ex.getMessage());
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

	public TccTransactionImpl getTransaction() {
		return transaction;
	}

	public void setTransaction(TccTransactionImpl transaction) {
		this.transaction = transaction;
	}

	public Object getIdentifier() {
		return identifier;
	}

	public void setIdentifier(Object identifier) {
		this.identifier = identifier;
	}

}
