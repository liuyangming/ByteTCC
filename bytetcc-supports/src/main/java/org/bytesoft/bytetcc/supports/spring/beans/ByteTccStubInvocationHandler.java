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
package org.bytesoft.bytetcc.supports.spring.beans;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import net.sf.cglib.proxy.MethodInterceptor;
import net.sf.cglib.proxy.MethodProxy;

import org.bytesoft.byterpc.RemoteInvocationResult;
import org.bytesoft.byterpc.cci.ConnectionFactory;
import org.bytesoft.byterpc.cci.internal.ConnectionImpl;
import org.bytesoft.byterpc.common.RemoteMethodKey;
import org.bytesoft.byterpc.spi.ConnectionManager;
import org.bytesoft.byterpc.spi.internal.ManagedConnectionImpl;
import org.bytesoft.byterpc.supports.RemoteMethodFactory;
import org.bytesoft.byterpc.wire.RemoteClientEndpoint;
import org.bytesoft.bytetcc.supports.spring.rpc.ByteTccRemoteInvocation;
import org.bytesoft.naming.NamingContextFactory;

public class ByteTccStubInvocationHandler implements ByteTccStubObject, InvocationHandler, MethodInterceptor {
	private String provider;
	private String serviceId;
	private Class<?> interfaceClass;
	private RemoteClientEndpoint remoteEndpoint;

	public Object intercept(Object obj, Method method, Object[] args, MethodProxy proxy) throws Throwable {
		// System.out.printf("method: %s, args: %s, proxy: %s%n", method, Arrays.toString(args), proxy);
		return this.invoke(proxy, method, args);
	}

	public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {

		try {
			if (Object.class.equals(method.getDeclaringClass())) {
				return method.invoke(this, args);
			} else if (ByteTccStubObject.class.equals(method.getDeclaringClass())) {
				return method.invoke(this, args);
			}
		} catch (IllegalAccessException ex) {
			throw new RuntimeException(ex);
		} catch (InvocationTargetException ex) {
			throw ex.getTargetException();
		}

		if (this.remoteEndpoint == null) {
			NamingContextFactory namingContextFactory = NamingContextFactory.getInstance();
			ConnectionManager connectionManager = namingContextFactory.getConnectionManager();
			ConnectionImpl connection = (ConnectionImpl) connectionManager.allocateConnection(this.provider);
			ManagedConnectionImpl mc = connection.getManagedConnection();
			this.remoteEndpoint = (RemoteClientEndpoint) mc.getRemoteRequestor();
		}

		RemoteMethodFactory rmf = this.remoteEndpoint.getRemoteMethodFactory();
		RemoteMethodKey methodKey = rmf.getRemoteMethodKey(method);

		ByteTccRemoteInvocation invocation = new ByteTccRemoteInvocation();
		invocation.setInstanceKey(this.serviceId);
		invocation.setMethodKey(methodKey.getMethodKey());
		invocation.setArgs(args);
		String destination = null;
		if (this.provider.startsWith(ConnectionFactory.PROTOCOL_PREFIX)) {
			destination = this.provider.substring(ConnectionFactory.PROTOCOL_PREFIX.length());
		} else {
			destination = this.provider;
		}
		invocation.setDestination(destination);

		RemoteInvocationResult result = this.remoteEndpoint.fireRequest(invocation);
		if (result.isFailure()) {
			throw (Throwable) result.getValue();
		} else {
			return result.getValue();
		}

	}

	public String toString() {
		String className = this.interfaceClass == null ? Object.class.getName() : this.interfaceClass.getName();
		return String.format("%s [url: %s]", className, this.provider);
	}

	public String getProvider() {
		return provider;
	}

	public void setProvider(String provider) {
		this.provider = provider;
	}

	public Class<?> getInterfaceClass() {
		return interfaceClass;
	}

	public void setInterfaceClass(Class<?> interfaceClass) {
		this.interfaceClass = interfaceClass;
	}

	public String getServiceId() {
		return serviceId;
	}

	public void setServiceId(String serviceId) {
		this.serviceId = serviceId;
	}

}
