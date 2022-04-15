package org.bytesoft.bytetcc;

import org.bytesoft.compensable.TransactionBeanFactory;
import org.bytesoft.transaction.TransactionManager;
import org.bytesoft.transaction.TransactionRecovery;
import org.bytesoft.transaction.TransactionRepository;
import org.bytesoft.transaction.remote.RemoteCoordinator;
import org.bytesoft.transaction.supports.rpc.TransactionInterceptor;
import org.bytesoft.transaction.xa.XidFactory;

public class TransactionBeanFactoryImpl implements TransactionBeanFactory {
    protected TransactionManager transactionManager;
    protected XidFactory transactionXidFactory;
    protected TransactionRepository transactionRepository;
    protected TransactionInterceptor transactionInterceptor;
    protected TransactionRecovery transactionRecovery;
    protected RemoteCoordinator transactionCoordinator;

    public TransactionManager getTransactionManager() {
        return transactionManager;
    }

    public XidFactory getTransactionXidFactory() {
        return transactionXidFactory;
    }

    public TransactionRepository getTransactionRepository() {
        return transactionRepository;
    }

    public TransactionInterceptor getTransactionInterceptor() {
        return transactionInterceptor;
    }

    public TransactionRecovery getTransactionRecovery() {
        return transactionRecovery;
    }

    public RemoteCoordinator getTransactionNativeParticipant() {
        return transactionCoordinator;
    }

    public void setTransactionRecovery(TransactionRecovery transactionRecovery) {
        this.transactionRecovery = transactionRecovery;
    }

    public void setTransactionCoordinator(RemoteCoordinator transactionCoordinator) {
        this.transactionCoordinator = transactionCoordinator;
    }

    public void setTransactionInterceptor(TransactionInterceptor transactionInterceptor) {
        this.transactionInterceptor = transactionInterceptor;
    }
    public void setTransactionRepository(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    public void setTransactionXidFactory(XidFactory transactionXidFactory) {
        this.transactionXidFactory = transactionXidFactory;
    }
    public void setTransactionManager(TransactionManager transactionManager) {
        this.transactionManager = transactionManager;
    }
}
