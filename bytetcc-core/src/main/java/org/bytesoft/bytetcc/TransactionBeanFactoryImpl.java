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
import org.bytesoft.bytetcc.supports.logger.CompensableTransactionLogger;
import org.bytesoft.bytetcc.supports.logger.EmptyCompensableTransactionLogger;
import org.bytesoft.transaction.TransactionManager;
import org.bytesoft.transaction.TransactionRecovery;
import org.bytesoft.transaction.TransactionRepository;
import org.bytesoft.transaction.supports.TransactionTimer;
import org.bytesoft.transaction.supports.rpc.TransactionInterceptor;
import org.bytesoft.transaction.xa.XidFactory;

public final class TransactionBeanFactoryImpl implements CompensableTransactionBeanFactory {

	private TransactionManager transactionManager;
	private CompensableTransactionManager compensableTransactionManager;
	private XidFactory xidFactory;
	private XidFactory compensableXidFactory;
	private CompensableTransactionLogger transactionLogger = new EmptyCompensableTransactionLogger();
	private TransactionRepository transactionRepository;
	private TransactionInterceptor transactionInterceptor;
	private TransactionRecovery transactionRecovery;
	private RemoteCoordinator transactionCoordinator;
	private RemoteCoordinator compensableTransactionCoordinator;
	private CompensableInvocationExecutor compensableInvocationExecutor;

	public TransactionTimer getTransactionTimer() {
		throw new IllegalStateException();
	}

	public TransactionManager getTransactionManager() {
		return transactionManager;
	}

	public void setTransactionManager(TransactionManager transactionManager) {
		this.transactionManager = transactionManager;
	}

	public CompensableTransactionManager getCompensableTransactionManager() {
		return compensableTransactionManager;
	}

	public void setCompensableTransactionManager(CompensableTransactionManager compensableTransactionManager) {
		this.compensableTransactionManager = compensableTransactionManager;
	}

	public XidFactory getXidFactory() {
		return xidFactory;
	}

	public void setXidFactory(XidFactory xidFactory) {
		this.xidFactory = xidFactory;
	}

	public XidFactory getCompensableXidFactory() {
		return compensableXidFactory;
	}

	public void setCompensableXidFactory(XidFactory compensableXidFactory) {
		this.compensableXidFactory = compensableXidFactory;
	}

	public CompensableTransactionLogger getTransactionLogger() {
		return transactionLogger;
	}

	public void setTransactionLogger(CompensableTransactionLogger transactionLogger) {
		this.transactionLogger = transactionLogger;
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

	public RemoteCoordinator getCompensableTransactionCoordinator() {
		return compensableTransactionCoordinator;
	}

	public void setCompensableTransactionCoordinator(RemoteCoordinator compensableTransactionCoordinator) {
		this.compensableTransactionCoordinator = compensableTransactionCoordinator;
	}

	public CompensableInvocationExecutor getCompensableInvocationExecutor() {
		return compensableInvocationExecutor;
	}

	public void setCompensableInvocationExecutor(CompensableInvocationExecutor compensableInvocationExecutor) {
		this.compensableInvocationExecutor = compensableInvocationExecutor;
	}

}
