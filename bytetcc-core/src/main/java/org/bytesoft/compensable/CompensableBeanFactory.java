package org.bytesoft.compensable;

import org.bytesoft.bytejta.supports.wire.RemoteCoordinator;
import org.bytesoft.compensable.supports.logger.CompensableLogger;
import org.bytesoft.transaction.TransactionBeanFactory;
import org.bytesoft.transaction.xa.XidFactory;

public interface CompensableBeanFactory extends TransactionBeanFactory {

	public XidFactory getCompensableXidFactory();

	public RemoteCoordinator getCompensableCoordinator();

	public CompensableManager getCompensableManager();

	public CompensableLogger getCompensableLogger();

	public CompensableInvocationExecutor getCompensableInvocationExecutor();

}
