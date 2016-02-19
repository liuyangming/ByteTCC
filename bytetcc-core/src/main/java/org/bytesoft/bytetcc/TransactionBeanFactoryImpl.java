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
package org.bytesoft.bytetcc;

import org.bytesoft.bytejta.supports.wire.RemoteCoordinator;
import org.bytesoft.bytetcc.logger.EmptyCompensableLogger;
import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.CompensableInvocationExecutor;
import org.bytesoft.compensable.CompensableManager;
import org.bytesoft.compensable.logger.CompensableLogger;
import org.bytesoft.transaction.TransactionManager;
import org.bytesoft.transaction.TransactionRecovery;
import org.bytesoft.transaction.TransactionRepository;
import org.bytesoft.transaction.supports.rpc.TransactionInterceptor;
import org.bytesoft.transaction.xa.XidFactory;

public final class TransactionBeanFactoryImpl implements CompensableBeanFactory {

	private TransactionManager transactionManager;
	private CompensableManager compensableManager;
	private XidFactory transactionXidFactory;
	private XidFactory compensableXidFactory;
	private CompensableLogger compensableLogger = new EmptyCompensableLogger();
	private TransactionRepository transactionRepository;
	private TransactionInterceptor transactionInterceptor;
	private TransactionRecovery transactionRecovery;
	private RemoteCoordinator transactionCoordinator;
	private RemoteCoordinator compensableCoordinator;
	private CompensableInvocationExecutor compensableInvocationExecutor;

	public TransactionManager getTransactionManager() {
		return transactionManager;
	}

	public void setTransactionManager(TransactionManager transactionManager) {
		this.transactionManager = transactionManager;
	}

	public CompensableManager getCompensableManager() {
		return this.compensableManager;
	}

	public void setCompensableManager(CompensableManager compensableManager) {
		this.compensableManager = compensableManager;
	}

	public XidFactory getTransactionXidFactory() {
		return transactionXidFactory;
	}

	public void setTransactionXidFactory(XidFactory xidFactory) {
		this.transactionXidFactory = xidFactory;
	}

	public XidFactory getCompensableXidFactory() {
		return compensableXidFactory;
	}

	public void setCompensableXidFactory(XidFactory compensableXidFactory) {
		this.compensableXidFactory = compensableXidFactory;
	}

	public CompensableLogger getCompensableLogger() {
		return compensableLogger;
	}

	public void setCompensableLogger(CompensableLogger compensableLogger) {
		this.compensableLogger = compensableLogger;
	}

	public TransactionRepository getTransactionRepository() {
		return transactionRepository;
	}

	public void setTransactionRepository(TransactionRepository transactionRepository) {
		this.transactionRepository = transactionRepository;
	}

	public TransactionInterceptor getTransactionInterceptor() {
		return transactionInterceptor;
	}

	public void setTransactionInterceptor(TransactionInterceptor transactionInterceptor) {
		this.transactionInterceptor = transactionInterceptor;
	}

	public TransactionRecovery getTransactionRecovery() {
		return transactionRecovery;
	}

	public void setTransactionRecovery(TransactionRecovery transactionRecovery) {
		this.transactionRecovery = transactionRecovery;
	}

	public RemoteCoordinator getTransactionCoordinator() {
		return transactionCoordinator;
	}

	public void setTransactionCoordinator(RemoteCoordinator transactionCoordinator) {
		this.transactionCoordinator = transactionCoordinator;
	}

	public RemoteCoordinator getCompensableCoordinator() {
		return compensableCoordinator;
	}

	public void setCompensableCoordinator(RemoteCoordinator compensableCoordinator) {
		this.compensableCoordinator = compensableCoordinator;
	}

	public CompensableInvocationExecutor getCompensableInvocationExecutor() {
		return compensableInvocationExecutor;
	}

	public void setCompensableInvocationExecutor(CompensableInvocationExecutor compensableInvocationExecutor) {
		this.compensableInvocationExecutor = compensableInvocationExecutor;
	}

}
