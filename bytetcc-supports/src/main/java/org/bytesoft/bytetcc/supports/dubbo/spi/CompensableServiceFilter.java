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
package org.bytesoft.bytetcc.supports.dubbo.spi;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.Map;

import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;

import org.apache.commons.lang3.StringUtils;
import org.bytesoft.bytejta.supports.dubbo.DubboRemoteCoordinator;
import org.bytesoft.bytejta.supports.dubbo.InvocationContext;
import org.bytesoft.bytejta.supports.rpc.TransactionRequestImpl;
import org.bytesoft.bytejta.supports.rpc.TransactionResponseImpl;
import org.bytesoft.bytejta.supports.wire.RemoteCoordinator;
import org.bytesoft.bytejta.supports.wire.RemoteCoordinatorRegistry;
import org.bytesoft.bytetcc.CompensableCoordinator;
import org.bytesoft.bytetcc.supports.dubbo.CompensableBeanRegistry;
import org.bytesoft.common.utils.ByteUtils;
import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.CompensableManager;
import org.bytesoft.compensable.CompensableTransaction;
import org.bytesoft.compensable.RemotingException;
import org.bytesoft.compensable.TransactionContext;
import org.bytesoft.transaction.TransactionRepository;
import org.bytesoft.transaction.internal.TransactionException;
import org.bytesoft.transaction.supports.rpc.TransactionInterceptor;
import org.bytesoft.transaction.xa.TransactionXid;
import org.bytesoft.transaction.xa.XidFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.rpc.Filter;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcContext;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.RpcResult;
import com.caucho.hessian.io.HessianInput;
import com.caucho.hessian.io.HessianOutput;

public class CompensableServiceFilter implements Filter {
	static final Logger logger = LoggerFactory.getLogger(CompensableServiceFilter.class);

	public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException, RemotingException {
		if (RpcContext.getContext().isProviderSide()) {
			return this.providerInvoke(invoker, invocation);
		} else {
			return this.consumerInvoke(invoker, invocation);
		}
	}

	public Result providerInvoke(Invoker<?> invoker, Invocation invocation) throws RpcException, RemotingException {
		URL url = RpcContext.getContext().getUrl();
		String interfaceClazz = url.getServiceInterface();
		if (XAResource.class.getName().equals(interfaceClazz) || RemoteCoordinator.class.getName().equals(interfaceClazz)) {
			return this.providerInvokeForTCC(invoker, invocation);
		} else {
			return this.providerInvokeForSVC(invoker, invocation);
		}
	}

	public Result providerInvokeForTCC(Invoker<?> invoker, Invocation invocation) throws RpcException, RemotingException {
		CompensableBeanRegistry beanRegistry = CompensableBeanRegistry.getInstance();
		CompensableBeanFactory beanFactory = beanRegistry.getBeanFactory();
		XidFactory xidFactory = beanFactory.getCompensableXidFactory();
		TransactionRepository compensableRepository = beanFactory.getCompensableRepository();

		Class<?>[] parameterTypeArray = invocation.getParameterTypes();
		Class<?> parameterType = (parameterTypeArray == null || parameterTypeArray.length == 0) ? null : parameterTypeArray[0];
		if (parameterTypeArray == null || parameterTypeArray.length != 1) {
			return invoker.invoke(invocation);
		} else if (Xid.class.equals(parameterType) == false) {
			return invoker.invoke(invocation);
		}

		Object[] arguments = invocation.getArguments();
		Xid xid = (Xid) arguments[0];

		TransactionXid globalXid = xidFactory.createGlobalXid(xid.getGlobalTransactionId());
		CompensableTransaction transaction = (CompensableTransaction) compensableRepository.getTransaction(globalXid);
		TransactionContext transactionContext = transaction.getTransactionContext();
		String propagatedBy = String.valueOf(transactionContext.getPropagatedBy());

		String remoteHost = RpcContext.getContext().getRemoteHost();
		int remotePort = RpcContext.getContext().getRemotePort();
		String remoteAddr = String.format("%s:%s", remoteHost, remotePort);

		if (StringUtils.equals(propagatedBy, remoteAddr)) {
			return invoker.invoke(invocation);
		}

		TransactionException error = new TransactionException(XAException.XAER_PROTO);
		RpcResult result = new RpcResult();
		result.setException(error);

		return result;
	}

	public Result providerInvokeForSVC(Invoker<?> invoker, Invocation invocation) throws RpcException, RemotingException {
		RemoteCoordinatorRegistry remoteCoordinatorRegistry = RemoteCoordinatorRegistry.getInstance();
		CompensableBeanRegistry beanRegistry = CompensableBeanRegistry.getInstance();
		RemoteCoordinator consumeCoordinator = beanRegistry.getConsumeCoordinator();

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

			remoteCoordinator = (RemoteCoordinator) Proxy.newProxyInstance(DubboRemoteCoordinator.class.getClassLoader(),
					new Class[] { RemoteCoordinator.class }, dubboCoordinator);
			remoteCoordinatorRegistry.putTransactionManagerStub(address, remoteCoordinator);
		}

		TransactionRequestImpl request = new TransactionRequestImpl();
		request.setTargetTransactionCoordinator(remoteCoordinator);

		TransactionResponseImpl response = new TransactionResponseImpl();
		response.setSourceTransactionCoordinator(remoteCoordinator);
		boolean success = false;
		try {
			this.beforeProviderInvokeForSVC(invocation, request, response);
			Result result = invoker.invoke(invocation);
			success = true;
			return result;
		} catch (RemotingException rex) {
			RpcResult result = new RpcResult();
			result.setException(rex);
			return result;
		} catch (RuntimeException rex) {
			logger.error("Error occurred in remote call!", rex);

			RpcResult result = new RpcResult();
			result.setException(new RemotingException(rex.getMessage()));
			return result;
		} finally {
			try {
				this.afterProviderInvokeForSVC(invocation, request, response);
			} catch (RemotingException rex) {
				if (success) {
					RpcResult result = new RpcResult();
					result.setException(rex);
					return result;
				} else {
					logger.error("Error occurred in remote call!", rex);
				}
			} catch (RuntimeException rex) {
				if (success) {
					RpcResult result = new RpcResult();
					result.setException(rex);
					return result;
				} else {
					logger.error("Error occurred in remote call!", rex);
				}
			}
		}

	}

	private void beforeProviderInvokeForSVC(Invocation invocation, TransactionRequestImpl request,
			TransactionResponseImpl response) throws RpcException {
		CompensableBeanRegistry beanRegistry = CompensableBeanRegistry.getInstance();
		CompensableBeanFactory beanFactory = beanRegistry.getBeanFactory();
		TransactionInterceptor transactionInterceptor = beanFactory.getTransactionInterceptor();

		RemotingException rpcError = null;
		String transactionContextContent = invocation.getAttachment(TransactionContext.class.getName());
		String propagatedBy = invocation.getAttachment(CompensableCoordinator.class.getName());
		if (StringUtils.isNotBlank(transactionContextContent)) {
			byte[] requestByteArray = ByteUtils.stringToByteArray(transactionContextContent);
			ByteArrayInputStream bais = new ByteArrayInputStream(requestByteArray);
			HessianInput input = new HessianInput(bais);
			try {
				TransactionContext remoteTransactionContext = (TransactionContext) input.readObject();
				remoteTransactionContext.setPropagatedBy(propagatedBy);
				request.setTransactionContext(remoteTransactionContext);
			} catch (IOException ex) {
				logger.error("Error occurred in remote call!", ex);
				rpcError = new RemotingException(ex.getMessage());
			}
		}

		try {
			transactionInterceptor.afterReceiveRequest(request);
		} catch (RuntimeException rex) {
			logger.error("Error occurred in remote call!", rex);
			throw new RemotingException(rex.getMessage());
		}

		if (rpcError != null) {
			throw rpcError;
		}

	}

	private void afterProviderInvokeForSVC(Invocation invocation, TransactionRequestImpl request,
			TransactionResponseImpl response) throws RpcException {
		CompensableBeanRegistry beanRegistry = CompensableBeanRegistry.getInstance();
		CompensableBeanFactory beanFactory = beanRegistry.getBeanFactory();
		TransactionInterceptor transactionInterceptor = beanFactory.getTransactionInterceptor();
		CompensableManager transactionManager = beanFactory.getCompensableManager();

		String transactionContextContent = invocation.getAttachment(TransactionContext.class.getName());

		CompensableTransaction transaction = transactionManager.getCompensableTransactionQuietly();
		TransactionContext nativeTransactionContext = transaction == null ? null : transaction.getTransactionContext();

		response.setTransactionContext(nativeTransactionContext);
		try {
			transactionInterceptor.beforeSendResponse(response);
			if (StringUtils.isNotBlank(transactionContextContent)) {
				ByteArrayOutputStream baos = new ByteArrayOutputStream();
				HessianOutput output = new HessianOutput(baos);
				output.writeObject(nativeTransactionContext);
				String nativeTansactionContextContent = ByteUtils.byteArrayToString(baos.toByteArray());
				invocation.getAttachments().put(TransactionContext.class.getName(), nativeTansactionContextContent);
			}
		} catch (IOException ex) {
			logger.error("Error occurred in remote call!", ex);
			throw new RemotingException(ex.getMessage());
		} catch (RuntimeException rex) {
			logger.error("Error occurred in remote call!", rex);
			throw new RemotingException(rex.getMessage());
		}
	}

	public Result consumerInvoke(Invoker<?> invoker, Invocation invocation) throws RpcException, RemotingException {
		URL url = RpcContext.getContext().getUrl();
		String interfaceClazz = url.getServiceInterface();
		if (XAResource.class.getName().equals(interfaceClazz) || RemoteCoordinator.class.getName().equals(interfaceClazz)) {
			return this.consumerInvokeForTCC(invoker, invocation);
		} else {
			return this.consumerInvokeForSVC(invoker, invocation);
		}
	}

	public Result consumerInvokeForTCC(Invoker<?> invoker, Invocation invocation) throws RpcException, RemotingException {
		return invoker.invoke(invocation);
	}

	public Result consumerInvokeForSVC(Invoker<?> invoker, Invocation invocation) throws RpcException, RemotingException {
		RemoteCoordinatorRegistry remoteCoordinatorRegistry = RemoteCoordinatorRegistry.getInstance();
		CompensableBeanRegistry beanRegistry = CompensableBeanRegistry.getInstance();
		CompensableBeanFactory beanFactory = beanRegistry.getBeanFactory();
		RemoteCoordinator consumeCoordinator = beanRegistry.getConsumeCoordinator();
		CompensableManager transactionManager = beanFactory.getCompensableManager();
		CompensableTransaction transaction = transactionManager.getCompensableTransactionQuietly();
		TransactionContext nativeTransactionContext = transaction == null ? null : transaction.getTransactionContext();

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

			remoteCoordinator = (RemoteCoordinator) Proxy.newProxyInstance(DubboRemoteCoordinator.class.getClassLoader(),
					new Class[] { RemoteCoordinator.class }, dubboCoordinator);
			remoteCoordinatorRegistry.putTransactionManagerStub(address, remoteCoordinator);
		}

		TransactionRequestImpl request = new TransactionRequestImpl();
		request.setTransactionContext(nativeTransactionContext);
		request.setTargetTransactionCoordinator(remoteCoordinator);

		TransactionResponseImpl response = new TransactionResponseImpl();
		response.setSourceTransactionCoordinator(remoteCoordinator);
		boolean success = false;
		try {
			this.beforeConsumerInvokeForSVC(invocation, request, response);
			Result result = invoker.invoke(invocation);
			success = true;
			return result;
		} catch (RemotingException rex) {
			RpcResult result = new RpcResult();
			result.setException(rex);
			return result;
		} catch (RuntimeException rex) {
			logger.error("Error occurred in remote call!", rex);

			RpcResult result = new RpcResult();
			result.setException(new RemotingException(rex.getMessage()));
			return result;
		} finally {
			try {
				this.afterConsumerInvokeForSVC(invocation, request, response);
			} catch (RemotingException rex) {
				if (success) {
					RpcResult result = new RpcResult();
					result.setException(rex);
					return result;
				} else {
					logger.error("Error occurred in remote call!", rex);
				}
			} catch (RuntimeException rex) {
				if (success) {
					RpcResult result = new RpcResult();
					result.setException(rex);
					return result;
				} else {
					logger.error("Error occurred in remote call!", rex);
				}
			}
		}

	}

	private void beforeConsumerInvokeForSVC(Invocation invocation, TransactionRequestImpl request,
			TransactionResponseImpl response) throws RpcException {
		CompensableBeanRegistry beanRegistry = CompensableBeanRegistry.getInstance();
		CompensableBeanFactory beanFactory = beanRegistry.getBeanFactory();
		TransactionInterceptor transactionInterceptor = beanFactory.getTransactionInterceptor();
		RemoteCoordinator compensableCoordinator = beanFactory.getCompensableCoordinator();

		transactionInterceptor.beforeSendRequest(request);
		if (request.getTransactionContext() != null) {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			HessianOutput output = new HessianOutput(baos);
			try {
				output.writeObject(request.getTransactionContext());
			} catch (IOException ex) {
				logger.error("Error occurred in remote call!", ex);
				throw new RemotingException(ex.getMessage());
			}
			String transactionContextContent = ByteUtils.byteArrayToString(baos.toByteArray());
			Map<String, String> attachments = invocation.getAttachments();
			attachments.put(TransactionContext.class.getName(), transactionContextContent);
			attachments.put(CompensableCoordinator.class.getName(), compensableCoordinator.getIdentifier());
		}
	}

	private void afterConsumerInvokeForSVC(Invocation invocation, TransactionRequestImpl request,
			TransactionResponseImpl response) throws RpcException {
		CompensableBeanRegistry beanRegistry = CompensableBeanRegistry.getInstance();
		CompensableBeanFactory beanFactory = beanRegistry.getBeanFactory();
		TransactionInterceptor transactionInterceptor = beanFactory.getTransactionInterceptor();

		RemotingException rpcError = null;
		try {
			if (request.getTransactionContext() != null) {
				String transactionContextContent = invocation.getAttachment(TransactionContext.class.getName());
				byte[] byteArray = ByteUtils.stringToByteArray(transactionContextContent);
				ByteArrayInputStream bais = new ByteArrayInputStream(byteArray);
				HessianInput input = new HessianInput(bais);
				TransactionContext remoteTransactionContext = (TransactionContext) input.readObject();
				response.setTransactionContext(remoteTransactionContext);
			}
		} catch (IOException ex) {
			logger.error("Error occurred in remote call!", ex);
			rpcError = new RemotingException(ex.getMessage());
		}

		try {
			transactionInterceptor.afterReceiveResponse(response);
		} catch (RuntimeException rex) {
			logger.error("Error occurred in remote call!", rex);
			throw new RemotingException(rex.getMessage());
		}

		if (rpcError != null) {
			throw rpcError;
		}

	}

}
