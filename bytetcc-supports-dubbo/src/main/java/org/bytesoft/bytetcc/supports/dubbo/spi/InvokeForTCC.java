package org.bytesoft.bytetcc.supports.dubbo.spi;

import com.alibaba.dubbo.rpc.*;
import org.bytesoft.bytetcc.supports.dubbo.CompensableBeanRegistry;
import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.RemotingException;
import org.bytesoft.compensable.TransactionBeanFactory;
import org.bytesoft.transaction.remote.RemoteCoordinator;

import java.util.Map;

public class InvokeForTCC {

    public Result invokeForTCC(Invoker<?> invoker, Invocation invocation) throws RpcException, RemotingException {
        CompensableBeanRegistry beanRegistry = CompensableBeanRegistry.getInstance();
        CompensableBeanFactory beanFactory = beanRegistry.getBeanFactory();
        TransactionBeanFactory transactionBeanFactory = (TransactionBeanFactory) beanRegistry.getBeanFactory();
        RemoteCoordinator compensableCoordinator = (RemoteCoordinator) beanFactory.getCompensableNativeParticipant();

        Map<String, String> attachments = invocation.getAttachments();
        attachments.put(RemoteCoordinator.class.getName(), compensableCoordinator.getIdentifier());
        RpcResult result = (RpcResult) invoker.invoke(invocation);
        Object value = result.getValue();
        if (CompensableServiceFilter.InvocationResult.class.isInstance(value)) {
            CompensableServiceFilter.InvocationResult wrapped = (CompensableServiceFilter.InvocationResult) value;
            result.setValue(null);
            result.setException(null);

            if (wrapped.isFailure()) {
                result.setException(wrapped.getError());
            } else {
                result.setValue(wrapped.getValue());
            }

            // String propagatedBy = (String) wrapped.getVariable(RemoteCoordinator.class.getName());
            // String identifier = compensableCoordinator.getIdentifier();
        }
        return result;
    }

}
