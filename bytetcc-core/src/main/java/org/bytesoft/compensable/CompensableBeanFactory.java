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
package org.bytesoft.compensable;

import org.bytesoft.bytejta.supports.wire.RemoteCoordinator;
import org.bytesoft.compensable.logger.CompensableLogger;
import org.bytesoft.transaction.TransactionManager;
import org.bytesoft.transaction.TransactionRecovery;
import org.bytesoft.transaction.TransactionRepository;
import org.bytesoft.transaction.supports.rpc.TransactionInterceptor;
import org.bytesoft.transaction.xa.XidFactory;

public interface CompensableBeanFactory {

	public XidFactory getTransactionXidFactory();

	public XidFactory getCompensableXidFactory();

	public TransactionManager getTransactionManager();

	public CompensableManager getCompensableManager();

	public RemoteCoordinator getTransactionCoordinator();

	public RemoteCoordinator getCompensableCoordinator();

	public CompensableLogger getCompensableLogger();

	public TransactionRepository getTransactionRepository();

	public TransactionInterceptor getTransactionInterceptor();

	public TransactionRecovery getTransactionRecovery();

	public CompensableInvocationExecutor getCompensableInvocationExecutor();

}
