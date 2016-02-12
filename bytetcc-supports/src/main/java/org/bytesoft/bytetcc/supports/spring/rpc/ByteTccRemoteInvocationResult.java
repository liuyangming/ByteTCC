/**
 * Copyright 2014-2015 yangming.liu<liuyangming@gmail.com>.
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

import java.lang.reflect.Proxy;

import org.bytesoft.bytejta.supports.wire.RemoteCoordinator;
import org.bytesoft.byterpc.RemoteInvocationResult;
import org.bytesoft.byterpc.remote.RemoteRequestor;
import org.bytesoft.byterpc.supports.RemoteInvocationFactory;
import org.bytesoft.byterpc.supports.RemoteInvocationFactoryAware;
import org.bytesoft.byterpc.supports.RemoteMethodFactory;
import org.bytesoft.byterpc.supports.RemoteMethodFactoryAware;
import org.bytesoft.byterpc.supports.RemoteRequestorAware;
import org.bytesoft.transaction.TransactionContext;
import org.bytesoft.transaction.supports.rpc.TransactionResponse;

public class ByteTccRemoteInvocationResult extends RemoteInvocationResult implements RemoteRequestorAware,
		RemoteInvocationFactoryAware, RemoteMethodFactoryAware, TransactionResponse {
	private static final long serialVersionUID = 1L;

	private TransactionContext transaction;
	private transient RemoteRequestor requestor;
	private transient RemoteInvocationFactory invocationFactory;
	private transient RemoteMethodFactory remoteMethodFactory;

	public ByteTccRemoteInvocationResult(ByteTccRemoteInvocation invocation) {
		super(invocation);
	}

	public TransactionContext getTransactionContext() {
		return this.transaction;
	}

	public RemoteCoordinator getSourceTransactionCoordinator() {
		ByteTccRemoteTransactionStub stub = new ByteTccRemoteTransactionStub();
		stub.setRequestor(this.requestor);
		ByteTccRemoteInvocation invocation = (ByteTccRemoteInvocation) this.getInvocation();
		stub.setIdentifier(String.valueOf(invocation.getDestination()));
		stub.setRemoteMethodFactory(this.remoteMethodFactory);
		stub.setInvocationFactory(this.invocationFactory);
		Class<?> interfaceClass = RemoteCoordinator.class;
		ClassLoader cl = interfaceClass.getClassLoader();
		Object proxyObject = Proxy.newProxyInstance(cl, new Class<?>[] { interfaceClass }, stub);
		return RemoteCoordinator.class.cast(proxyObject);
	}

	public void setTransactionContext(TransactionContext transactionContext) {
		this.transaction = transactionContext;
	}

	public void setRemoteRequestor(RemoteRequestor requestor) {
		this.requestor = requestor;
	}

	public void setRemoteInvocationFactory(RemoteInvocationFactory invocationFactory) {
		this.invocationFactory = invocationFactory;
	}

	public void setRemoteMethodFactory(RemoteMethodFactory remoteMethodFactory) {
		this.remoteMethodFactory = remoteMethodFactory;
	}

}
