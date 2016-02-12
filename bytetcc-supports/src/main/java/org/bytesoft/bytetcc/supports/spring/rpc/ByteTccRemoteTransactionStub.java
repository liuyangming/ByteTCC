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
package org.bytesoft.bytetcc.supports.spring.rpc;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.rmi.RemoteException;

import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;

import org.bytesoft.bytejta.supports.wire.RemoteCoordinator;
import org.bytesoft.byterpc.RemoteInvocationResult;
import org.bytesoft.byterpc.common.RemoteMethodKey;
import org.bytesoft.byterpc.remote.RemoteRequestor;
import org.bytesoft.byterpc.supports.RemoteInvocationFactory;
import org.bytesoft.byterpc.supports.RemoteMethodFactory;
import org.bytesoft.bytetcc.supports.spring.beans.ByteTccSkeletonObject;
import org.bytesoft.common.utils.CommonUtils;
import org.bytesoft.transaction.Transaction;
import org.bytesoft.transaction.TransactionContext;
import org.bytesoft.transaction.internal.TransactionException;

public class ByteTccRemoteTransactionStub implements InvocationHandler, RemoteCoordinator {
	private String identifier;
	private RemoteRequestor requestor;
	private RemoteInvocationFactory invocationFactory;
	private RemoteMethodFactory remoteMethodFactory;

	public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
		if (Object.class.equals(method.getDeclaringClass())) {
			try {
				return method.invoke(this, args);
			} catch (IllegalAccessException ex) {
				throw new RuntimeException(ex);
			} catch (InvocationTargetException ex) {
				throw ex.getTargetException();
			}
		} else if (ByteTccSkeletonObject.class.equals(method.getDeclaringClass())) {
			try {
				return method.invoke(this, args);
			} catch (IllegalAccessException ex) {
				throw new RuntimeException(ex);
			} catch (InvocationTargetException ex) {
				throw ex.getTargetException();
			}
		} else if (RemoteCoordinator.class.equals(method.getDeclaringClass())) {
			try {
				return method.invoke(this, args);
			} catch (IllegalAccessException ex) {
				throw new RuntimeException(ex);
			} catch (InvocationTargetException ex) {
				throw ex.getTargetException();
			}
		} else if (XAResource.class.equals(method.getDeclaringClass())) {
			try {
				return method.invoke(this, args);
			} catch (IllegalAccessException ex) {
				throw new RuntimeException(ex);
			} catch (InvocationTargetException ex) {
				Throwable targetEx = ex.getTargetException();
				if (IllegalStateException.class.isInstance(targetEx)) {
					ByteTccRemoteInvocation invocation = (ByteTccRemoteInvocation) this.invocationFactory
							.createRemoteInvocation();
					RemoteMethodKey methodKey = this.remoteMethodFactory.getRemoteMethodKey(method);
					invocation.setDestination(this.identifier);
					invocation.setMethodKey(methodKey.getMethodKey());
					invocation.setInstanceKey(XAResource.class.getName());// TODO
					invocation.setArgs(args);
					RemoteInvocationResult result = this.requestor.fireRequest(invocation);
					if (result.isFailure()) {
						Throwable throwable = (Throwable) result.getValue();
						if (RemoteException.class.isInstance(throwable)) {
							throw new XAException(XAException.XAER_RMFAIL);
						} else {
							throw throwable;
						}
					} else {
						return result.getValue();
					}
				} else {
					throw ex.getTargetException();
				}
			}
		} else {
			try {
				return method.invoke(this, args);
			} catch (IllegalAccessException ex) {
				throw new RuntimeException(ex);
			} catch (InvocationTargetException ex) {
				throw ex.getTargetException();
			}
		}
	}

	public int hashCode() {
		return 37 + 41 * (this.identifier == null ? 0 : this.identifier.hashCode());
	}

	public boolean equals(Object obj) {
		if (obj == null) {
			return false;
		} else if (Proxy.isProxyClass(obj.getClass()) == false) {
			return false;
		}
		InvocationHandler handler = Proxy.getInvocationHandler(obj);
		if (ByteTccRemoteTransactionStub.class.isInstance(handler) == false) {
			return false;
		}
		ByteTccRemoteTransactionStub that = (ByteTccRemoteTransactionStub) handler;
		return CommonUtils.equals(this.identifier, that.identifier);
	}

	public void commit(Xid arg0, boolean arg1) throws XAException {
		throw new IllegalStateException();
	}

	public void end(Xid arg0, int arg1) throws XAException {
	}

	public void end(TransactionContext transactionContext, int flags) throws TransactionException {
	}

	public void forget(Xid arg0) throws XAException {
	}

	public int getTransactionTimeout() throws XAException {
		return 0;
	}

	public boolean isSameRM(XAResource xares) throws XAException {
		return this.equals(xares);
	}

	public int prepare(Xid arg0) throws XAException {
		throw new IllegalStateException();
	}

	public Xid[] recover(int arg0) throws XAException {
		throw new IllegalStateException();
	}

	public void rollback(Xid arg0) throws XAException {
		throw new IllegalStateException();
	}

	public boolean setTransactionTimeout(int arg0) throws XAException {
		return false;
	}

	public void start(Xid arg0, int arg1) throws XAException {
	}

	public void start(TransactionContext transactionContext, int flags) throws TransactionException {
	}

	public Transaction getTransactionQuietly() {
		return null;
	}

	public String toString() {
		return String.format("ByteTccRemoteTransactionStub(identifier: %s)", this.identifier);
	}

	public RemoteMethodFactory getRemoteMethodFactory() {
		return remoteMethodFactory;
	}

	public void setRemoteMethodFactory(RemoteMethodFactory remoteMethodFactory) {
		this.remoteMethodFactory = remoteMethodFactory;
	}

	public String getIdentifier() {
		return identifier;
	}

	public void setIdentifier(String identifier) {
		this.identifier = identifier;
	}

	public RemoteRequestor getRequestor() {
		return requestor;
	}

	public void setRequestor(RemoteRequestor requestor) {
		this.requestor = requestor;
	}

	public RemoteInvocationFactory getInvocationFactory() {
		return invocationFactory;
	}

	public void setInvocationFactory(RemoteInvocationFactory invocationFactory) {
		this.invocationFactory = invocationFactory;
	}

}
