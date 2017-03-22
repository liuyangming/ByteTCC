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
import java.io.Serializable;
import java.lang.reflect.Proxy;
import java.util.HashMap;
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

import com.alibaba.com.caucho.hessian.io.HessianHandle;
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
		RemoteCoordinator compensableCoordinator = beanFactory.getCompensableCoordinator();

		Class<?>[] parameterTypeArray = invocation.getParameterTypes();
		Class<?> parameterType = (parameterTypeArray == null || parameterTypeArray.length == 0) ? null : parameterTypeArray[0];
		if (parameterTypeArray == null || parameterTypeArray.length == 0) {
			return this.wrapResultForProvider(invoker, invocation, false);
		} else if (Xid.class.equals(parameterType) == false) {
			return this.wrapResultForProvider(invoker, invocation, false);
		}

		RpcResult result = new RpcResult();

		Object[] arguments = invocation.getArguments();
		Xid xid = (Xid) arguments[0];

		TransactionXid globalXid = xidFactory.createGlobalXid(xid.getGlobalTransactionId());
		CompensableTransaction transaction = (CompensableTransaction) compensableRepository.getTransaction(globalXid);
		if (transaction == null) {
			InvocationResult wrapped = new InvocationResult();
			wrapped.setError(new TransactionException(XAException.XAER_NOTA));
			wrapped.setVariable(CompensableCoordinator.class.getName(), compensableCoordinator.getIdentifier());

			result.setException(null);
			result.setValue(wrapped);
		} else {
			TransactionContext transactionContext = transaction.getTransactionContext();
			String propagatedBy = String.valueOf(transactionContext.getPropagatedBy());

			String remoteAddr = invocation.getAttachment(CompensableCoordinator.class.getName());

			if (StringUtils.equals(propagatedBy, remoteAddr)) {
				return this.wrapResultForProvider(invoker, invocation, false);
			}

			InvocationResult wrapped = new InvocationResult();
			wrapped.setError(new TransactionException(XAException.XAER_PROTO));
			wrapped.setVariable(CompensableCoordinator.class.getName(), compensableCoordinator.getIdentifier());

			result.setException(null);
			result.setValue(wrapped);

			logger.warn("{}| branch should be invoked by its own coordinator.", globalXid);
		}

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

		boolean failure = false;
		try {
			this.beforeProviderInvokeForSVC(invocation, request, response);
			return this.wrapResultForProvider(invoker, invocation, true);
		} catch (RemotingException rex) {
			failure = true;

			return this.createErrorResultForProvider(rex, true);
		} catch (Throwable rex) {
			failure = true;
			logger.error("Error occurred in remote call!", rex);

			return this.createErrorResultForProvider(rex, true);
		} finally {
			try {
				this.afterProviderInvokeForSVC(invocation, request, response);
			} catch (RemotingException rex) {
				if (failure) {
					logger.error("Error occurred in remote call!", rex);
				} else {
					return this.createErrorResultForProvider(rex, true);
				}
			} catch (Throwable rex) {
				if (failure) {
					logger.error("Error occurred in remote call!", rex);
				} else {
					return this.createErrorResultForProvider(rex, true);
				}
			}
		}

	}

	public Result wrapResultForProvider(Invoker<?> invoker, Invocation invocation, boolean attachRequired) {

		try {
			RpcResult result = (RpcResult) invoker.invoke(invocation);
			if (result.hasException()) {
				return this.createErrorResultForProvider(result.getException(), attachRequired);
			} else {
				return this.convertResultForProvider(result, attachRequired);
			}
		} catch (Throwable rex) {
			return this.createErrorResultForProvider(rex, attachRequired);
		}

	}

	private Result convertResultForProvider(RpcResult result, boolean attachRequired) {
		CompensableBeanRegistry beanRegistry = CompensableBeanRegistry.getInstance();
		CompensableBeanFactory beanFactory = beanRegistry.getBeanFactory();
		CompensableManager compensableManager = beanFactory.getCompensableManager();

		Object value = result.getValue();

		InvocationResult wrapped = new InvocationResult();
		wrapped.setValue(value);
		if (attachRequired) {
			CompensableTransaction compensable = compensableManager.getCompensableTransactionQuietly();
			TransactionContext transactionContext = compensable.getTransactionContext();
			wrapped.setVariable(RemoteCoordinator.class.getName(), String.valueOf(transactionContext.getPropagatedBy()));
		}

		result.setException(null);
		result.setValue(wrapped);

		return result;
	}

	private Result createErrorResultForProvider(Throwable throwable, boolean attachRequired) {
		CompensableBeanRegistry beanRegistry = CompensableBeanRegistry.getInstance();
		CompensableBeanFactory beanFactory = beanRegistry.getBeanFactory();
		RemoteCoordinator compensableCoordinator = beanFactory.getCompensableCoordinator();

		RpcResult result = new RpcResult();

		InvocationResult wrapped = new InvocationResult();
		wrapped.setError(throwable);
		if (attachRequired) {
			wrapped.setVariable(CompensableCoordinator.class.getName(), compensableCoordinator.getIdentifier());
		}

		result.setException(null);
		result.setValue(wrapped);

		return result;
	}

	private void beforeProviderInvokeForSVC(Invocation invocation, TransactionRequestImpl request,
			TransactionResponseImpl response) {
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
			TransactionResponseImpl response) {
		CompensableBeanRegistry beanRegistry = CompensableBeanRegistry.getInstance();
		CompensableBeanFactory beanFactory = beanRegistry.getBeanFactory();
		TransactionInterceptor transactionInterceptor = beanFactory.getTransactionInterceptor();
		CompensableManager transactionManager = beanFactory.getCompensableManager();

		CompensableTransaction transaction = transactionManager.getCompensableTransactionQuietly();
		TransactionContext nativeTransactionContext = transaction == null ? null : transaction.getTransactionContext();

		response.setTransactionContext(nativeTransactionContext);
		try {
			transactionInterceptor.beforeSendResponse(response);
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
		CompensableBeanRegistry beanRegistry = CompensableBeanRegistry.getInstance();
		CompensableBeanFactory beanFactory = beanRegistry.getBeanFactory();
		RemoteCoordinator compensableCoordinator = beanFactory.getCompensableCoordinator();

		Map<String, String> attachments = invocation.getAttachments();
		attachments.put(CompensableCoordinator.class.getName(), compensableCoordinator.getIdentifier());
		return invoker.invoke(invocation);
	}

	public Result consumerInvokeForSVC(Invoker<?> invoker, Invocation invocation) throws RpcException, RemotingException {
		RemoteCoordinatorRegistry remoteCoordinatorRegistry = RemoteCoordinatorRegistry.getInstance();
		CompensableBeanRegistry beanRegistry = CompensableBeanRegistry.getInstance();
		CompensableBeanFactory beanFactory = beanRegistry.getBeanFactory();
		RemoteCoordinator compensableCoordinator = beanFactory.getCompensableCoordinator();
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
		boolean success = true;
		try {
			this.beforeConsumerInvokeForSVC(invocation, request, response);
			RpcResult result = (RpcResult) invoker.invoke(invocation);
			Object value = result.getValue();
			if (InvocationResult.class.isInstance(value)) {
				InvocationResult wrapped = (InvocationResult) value;
				result.setValue(null);
				result.setException(null);

				if (wrapped.isFailure()) {
					result.setException(wrapped.getError());
				} else {
					result.setValue(wrapped.getValue());
				}

				String propagatedBy = (String) wrapped.getVariable(RemoteCoordinator.class.getName());
				String identifier = compensableCoordinator.getIdentifier();
				boolean participantDelistRequired = StringUtils.equals(propagatedBy, identifier) == false;
				response.setParticipantDelistFlag(participantDelistRequired);
				response.setParticipantEnlistFlag(request.isParticipantEnlistFlag());
			}
			return result;
		} catch (RemotingException rex) {
			success = false;

			RpcResult result = new RpcResult();
			result.setException(rex);
			return result;
		} catch (Throwable rex) {
			success = false;
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
				}
			} catch (RuntimeException rex) {
				if (success) {
					logger.error("Error occurred in remote call!", rex);

					RpcResult result = new RpcResult();
					result.setException(new RemotingException(rex.getMessage()));
					return result;
				}
			}
		}

	}

	private void beforeConsumerInvokeForSVC(Invocation invocation, TransactionRequestImpl request,
			TransactionResponseImpl response) {
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
			TransactionResponseImpl response) {
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

	static class InvocationResult implements HessianHandle, Serializable {
		private static final long serialVersionUID = 1L;

		private Throwable error;
		private Object value;
		private final Map<String, Serializable> variables = new HashMap<String, Serializable>();

		public boolean isFailure() {
			return this.error != null;
		}

		public Object getValue() {
			return value;
		}

		public void setValue(Object value) {
			this.value = value;
		}

		public void setVariable(String key, Serializable value) {
			this.variables.put(key, value);
		}

		public Serializable getVariable(String key) {
			return this.variables.get(key);
		}

		public Throwable getError() {
			return error;
		}

		public void setError(Throwable error) {
			this.error = error;
		}

	}

}
