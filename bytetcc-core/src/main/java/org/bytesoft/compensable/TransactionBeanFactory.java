package org.bytesoft.compensable;

import org.bytesoft.transaction.TransactionManager;
import org.bytesoft.transaction.TransactionParticipant;
import org.bytesoft.transaction.TransactionRecovery;
import org.bytesoft.transaction.TransactionRepository;
import org.bytesoft.transaction.supports.rpc.TransactionInterceptor;
import org.bytesoft.transaction.xa.XidFactory;

public interface TransactionBeanFactory {
    XidFactory getTransactionXidFactory();

    TransactionManager getTransactionManager();

    TransactionParticipant getTransactionNativeParticipant();

    TransactionRepository getTransactionRepository();

    TransactionInterceptor getTransactionInterceptor();

    TransactionRecovery getTransactionRecovery();
}
