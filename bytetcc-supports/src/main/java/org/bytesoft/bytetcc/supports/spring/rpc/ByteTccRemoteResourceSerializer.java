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

import java.io.IOException;
import java.lang.reflect.Proxy;

import org.bytesoft.byterpc.remote.RemoteRequestor;
import org.bytesoft.byterpc.supports.RemoteInvocationFactory;
import org.bytesoft.byterpc.supports.RemoteMethodFactory;
import org.bytesoft.transaction.rpc.TransactionResource;
import org.bytesoft.transaction.supports.spring.AbstractXAResourceSerializer;

public class ByteTccRemoteResourceSerializer extends AbstractXAResourceSerializer {
	private RemoteRequestor requestor;
	private RemoteInvocationFactory invocationFactory;
	private RemoteMethodFactory remoteMethodFactory;

	public TransactionResource deserializeTransactionResource(String identifier) throws IOException {
		ByteTccRemoteTransactionStub stub = new ByteTccRemoteTransactionStub();
		stub.setRequestor(this.requestor);
		stub.setIdentifier(identifier);
		stub.setRemoteMethodFactory(this.remoteMethodFactory);
		stub.setInvocationFactory(this.invocationFactory);
		Class<?> interfaceClass = TransactionResource.class;
		ClassLoader cl = interfaceClass.getClassLoader();
		Object proxyObject = Proxy.newProxyInstance(cl, new Class<?>[] { interfaceClass }, stub);
		return TransactionResource.class.cast(proxyObject);
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

	public RemoteMethodFactory getRemoteMethodFactory() {
		return remoteMethodFactory;
	}

	public void setRemoteMethodFactory(RemoteMethodFactory remoteMethodFactory) {
		this.remoteMethodFactory = remoteMethodFactory;
	}

}
