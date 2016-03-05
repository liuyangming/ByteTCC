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
package org.bytesoft.bytetcc.supports.dubbo;

import java.lang.reflect.Proxy;

import org.bytesoft.bytejta.supports.dubbo.DubboRemoteCoordinator;
import org.bytesoft.bytejta.supports.invoke.InvocationContext;
import org.bytesoft.bytejta.supports.rpc.TransactionRequestImpl;
import org.bytesoft.bytejta.supports.rpc.TransactionResponseImpl;
import org.bytesoft.bytejta.supports.wire.RemoteCoordinator;
import org.bytesoft.bytejta.supports.wire.RemoteCoordinatorRegistry;
import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.transaction.Transaction;
import org.bytesoft.transaction.TransactionContext;
import org.bytesoft.transaction.TransactionManager;
import org.bytesoft.transaction.supports.rpc.TransactionInterceptor;

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.rpc.Filter;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcContext;
import com.alibaba.dubbo.rpc.RpcException;

public class CompensableDubboServiceFilter implements Filter {

	public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
		if (RpcContext.getContext().isProviderSide()) {
			return this.providerInvoke(invoker, invocation);
		} else {
			return this.consumerInvoke(invoker, invocation);
		}
	}

	public Result providerInvoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
		RemoteCoordinatorRegistry remoteCoordinatorRegistry = RemoteCoordinatorRegistry.getInstance();
		CompensableBeanRegistry beanRegistry = CompensableBeanRegistry.getInstance();
		CompensableBeanFactory beanFactory = beanRegistry.getBeanFactory();
		RemoteCoordinator consumeCoordinator = beanRegistry.getConsumeCoordinator();
		TransactionInterceptor transactionInterceptor = beanFactory.getTransactionInterceptor();
		TransactionManager transactionManager = beanFactory.getCompensableManager();
		Transaction transaction = transactionManager.getTransactionQuietly();
		TransactionContext transactionContext = transaction == null ? null : transaction.getTransactionContext();

		URL targetUrl = invoker.getUrl();
		String targetAddr = targetUrl.getIp();
		int targetPort = targetUrl.getPort();
		String address = String.format("%s:%s", targetAddr, targetPort);
		InvocationContext invocationContext = new InvocationContext();
		invocationContext.setServerHost(targetAddr);
		invocationContext.setServerPort(targetPort);

		RemoteCoordinator remoteCoordinator = remoteCoordinatorRegistry.getTransactionManagerStub(address);
		if (remoteCoordinator == null) {
			DubboRemoteCoordinator dubboCoordinator = new DubboRemoteCoordinator();
			dubboCoordinator.setInvocationContext(invocationContext);
			dubboCoordinator.setRemoteCoordinator(consumeCoordinator);

			remoteCoordinator = (RemoteCoordinator) Proxy.newProxyInstance(
					DubboRemoteCoordinator.class.getClassLoader(), new Class[] { RemoteCoordinator.class },
					dubboCoordinator);
			remoteCoordinatorRegistry.putTransactionManagerStub(address, remoteCoordinator);
		}

		Result result = null;
		TransactionRequestImpl request = new TransactionRequestImpl();
		request.setTransactionContext(transactionContext);
		request.setTargetTransactionCoordinator(remoteCoordinator);
		TransactionResponseImpl response = new TransactionResponseImpl();
		response.setTransactionContext(transactionContext);
		response.setSourceTransactionCoordinator(remoteCoordinator);
		try {
			transactionInterceptor.afterReceiveRequest(request);
			result = invoker.invoke(invocation);
			transactionInterceptor.beforeSendResponse(response);
		} catch (RpcException rex) {
			// TODO
		} catch (RuntimeException rex) {
			// TODO
		}
		return result;
	}

	public Result consumerInvoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
		RemoteCoordinatorRegistry remoteCoordinatorRegistry = RemoteCoordinatorRegistry.getInstance();
		CompensableBeanRegistry beanRegistry = CompensableBeanRegistry.getInstance();
		CompensableBeanFactory beanFactory = beanRegistry.getBeanFactory();
		RemoteCoordinator consumeCoordinator = beanRegistry.getConsumeCoordinator();
		TransactionInterceptor transactionInterceptor = beanFactory.getTransactionInterceptor();
		TransactionManager transactionManager = beanFactory.getCompensableManager();
		Transaction transaction = transactionManager.getTransactionQuietly();
		TransactionContext transactionContext = transaction == null ? null : transaction.getTransactionContext();

		URL targetUrl = invoker.getUrl();
		String targetAddr = targetUrl.getIp();
		int targetPort = targetUrl.getPort();
		String address = String.format("%s:%s", targetAddr, targetPort);
		InvocationContext invocationContext = new InvocationContext();
		invocationContext.setServerHost(targetAddr);
		invocationContext.setServerPort(targetPort);

		RemoteCoordinator remoteCoordinator = remoteCoordinatorRegistry.getTransactionManagerStub(address);
		if (remoteCoordinator == null) {
			DubboRemoteCoordinator dubboCoordinator = new DubboRemoteCoordinator();
			dubboCoordinator.setInvocationContext(invocationContext);
			dubboCoordinator.setRemoteCoordinator(consumeCoordinator);

			remoteCoordinator = (RemoteCoordinator) Proxy.newProxyInstance(
					DubboRemoteCoordinator.class.getClassLoader(), new Class[] { RemoteCoordinator.class },
					dubboCoordinator);
			remoteCoordinatorRegistry.putTransactionManagerStub(address, remoteCoordinator);
		}

		Result result = null;
		TransactionRequestImpl request = new TransactionRequestImpl();
		request.setTransactionContext(transactionContext);
		request.setTargetTransactionCoordinator(remoteCoordinator);
		TransactionResponseImpl response = new TransactionResponseImpl();
		response.setTransactionContext(transactionContext);
		response.setSourceTransactionCoordinator(remoteCoordinator);
		try {
			transactionInterceptor.beforeSendRequest(request);
			result = invoker.invoke(invocation);
			transactionInterceptor.afterReceiveResponse(response);
		} catch (RpcException rex) {
			// TODO
		} catch (RuntimeException rex) {
			// TODO
		}
		return result;
	}
}
