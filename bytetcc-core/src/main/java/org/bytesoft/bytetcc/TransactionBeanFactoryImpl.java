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
import org.bytesoft.bytetcc.supports.CompensableSynchronization;
import org.bytesoft.bytetcc.supports.resource.LocalResourceCleaner;
import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.CompensableContext;
import org.bytesoft.compensable.CompensableManager;
import org.bytesoft.compensable.ContainerContext;
import org.bytesoft.compensable.logging.CompensableLogger;
import org.bytesoft.transaction.TransactionManager;
import org.bytesoft.transaction.TransactionRecovery;
import org.bytesoft.transaction.TransactionRepository;
import org.bytesoft.transaction.logging.ArchiveDeserializer;
import org.bytesoft.transaction.supports.rpc.TransactionInterceptor;
import org.bytesoft.transaction.supports.serialize.XAResourceDeserializer;
import org.bytesoft.transaction.xa.XidFactory;

public final class TransactionBeanFactoryImpl implements CompensableBeanFactory {

	private TransactionManager transactionManager;
	private CompensableManager compensableManager;
	private XidFactory transactionXidFactory;
	private XidFactory compensableXidFactory;
	private CompensableLogger compensableLogger;
	private TransactionRepository transactionRepository;
	private TransactionRepository compensableRepository;
	private TransactionInterceptor transactionInterceptor;
	private TransactionRecovery transactionRecovery;
	private TransactionRecovery compensableRecovery;
	private RemoteCoordinator transactionCoordinator;
	private RemoteCoordinator compensableCoordinator;
	private ContainerContext containerContext;
	private ArchiveDeserializer archiveDeserializer;
	private XAResourceDeserializer resourceDeserializer;
	private LocalResourceCleaner localResourceCleaner;
	private CompensableContext compensableContext;
	private CompensableSynchronization compensableSynchronization;

	public TransactionManager getTransactionManager() {
		return transactionManager;
	}

	public void setTransactionManager(TransactionManager transactionManager) {
		this.transactionManager = transactionManager;
	}

	public CompensableManager getCompensableManager() {
		return compensableManager;
	}

	public void setCompensableManager(CompensableManager compensableManager) {
		this.compensableManager = compensableManager;
	}

	public XidFactory getTransactionXidFactory() {
		return transactionXidFactory;
	}

	public void setTransactionXidFactory(XidFactory transactionXidFactory) {
		this.transactionXidFactory = transactionXidFactory;
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

	public TransactionRepository getCompensableRepository() {
		return compensableRepository;
	}

	public void setCompensableRepository(TransactionRepository compensableRepository) {
		this.compensableRepository = compensableRepository;
	}

	public TransactionInterceptor getTransactionInterceptor() {
		return transactionInterceptor;
	}

	public void setTransactionInterceptor(TransactionInterceptor transactionInterceptor) {
		this.transactionInterceptor = transactionInterceptor;
	}

	public TransactionRecovery getCompensableRecovery() {
		return compensableRecovery;
	}

	public void setCompensableRecovery(TransactionRecovery compensableRecovery) {
		this.compensableRecovery = compensableRecovery;
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

	public ContainerContext getContainerContext() {
		return containerContext;
	}

	public void setContainerContext(ContainerContext containerContext) {
		this.containerContext = containerContext;
	}

	public ArchiveDeserializer getArchiveDeserializer() {
		return archiveDeserializer;
	}

	public void setArchiveDeserializer(ArchiveDeserializer archiveDeserializer) {
		this.archiveDeserializer = archiveDeserializer;
	}

	public XAResourceDeserializer getResourceDeserializer() {
		return resourceDeserializer;
	}

	public void setResourceDeserializer(XAResourceDeserializer resourceDeserializer) {
		this.resourceDeserializer = resourceDeserializer;
	}

	public LocalResourceCleaner getLocalResourceCleaner() {
		return localResourceCleaner;
	}

	public void setLocalResourceCleaner(LocalResourceCleaner localResourceCleaner) {
		this.localResourceCleaner = localResourceCleaner;
	}

	public CompensableContext getCompensableContext() {
		return compensableContext;
	}

	public void setCompensableContext(CompensableContext compensableContext) {
		this.compensableContext = compensableContext;
	}

	public CompensableSynchronization getCompensableSynchronization() {
		return compensableSynchronization;
	}

	public void setCompensableSynchronization(CompensableSynchronization compensableSynchronization) {
		this.compensableSynchronization = compensableSynchronization;
	}

}
