package org.bytesoft.bytetcc;

import org.bytesoft.bytejta.supports.wire.RemoteCoordinator;
import org.bytesoft.bytetcc.supports.logger.CompensableTransactionLogger;
import org.bytesoft.transaction.TransactionBeanFactory;
import org.bytesoft.transaction.xa.XidFactory;

public interface CompensableTransactionBeanFactory extends TransactionBeanFactory {

	public XidFactory getCompensableXidFactory();

	public RemoteCoordinator getCompensableTransactionCoordinator();

	public CompensableTransactionManager getCompensableTransactionManager();

	public CompensableTransactionLogger getTransactionLogger();

	public CompensableInvocationExecutor getCompensableInvocationExecutor();

}
