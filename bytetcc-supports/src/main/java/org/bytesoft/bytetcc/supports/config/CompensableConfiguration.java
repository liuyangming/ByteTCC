/**
 * Copyright 2014-2018 yangming.liu<bytefox@126.com>.
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
package org.bytesoft.bytetcc.supports.config;

import org.bytesoft.transaction.TransactionManager;
import org.bytesoft.transaction.TransactionRecovery;
import org.bytesoft.transaction.TransactionRepository;
import org.bytesoft.transaction.remote.RemoteCoordinator;
import org.bytesoft.transaction.supports.serialize.XAResourceDeserializer;
import org.bytesoft.transaction.xa.XidFactory;

@org.springframework.context.annotation.Configuration
public class CompensableConfiguration {

	@org.springframework.context.annotation.Bean
	public org.bytesoft.bytetcc.supports.spring.CompensableBeanPostProcessor compensableBeanPostProcessor() {
		return new org.bytesoft.bytetcc.supports.spring.CompensableBeanPostProcessor();
	}

	@org.springframework.context.annotation.Bean
	public org.bytesoft.bytetcc.supports.spring.CompensableAnnotationValidator compensableAnnotationValidator() {
		return new org.bytesoft.bytetcc.supports.spring.CompensableAnnotationValidator();
	}

	@org.springframework.context.annotation.Bean
	public org.bytesoft.bytetcc.supports.spring.CompensableContextPostProcessor compensableContextPostProcessor() {
		return new org.bytesoft.bytetcc.supports.spring.CompensableContextPostProcessor();
	}

	@org.springframework.context.annotation.Bean
	public org.bytesoft.bytetcc.UserCompensableImpl bytetccUserTransaction() {
		return new org.bytesoft.bytetcc.UserCompensableImpl();
	}

	@org.springframework.context.annotation.Bean
	public org.bytesoft.bytetcc.CompensableManagerImpl bytetccCompensableManager() {
		return new org.bytesoft.bytetcc.CompensableManagerImpl();
	}

	@org.springframework.context.annotation.Bean
	public org.bytesoft.bytetcc.TransactionManagerImpl transactionManager() {
		return new org.bytesoft.bytetcc.TransactionManagerImpl();
	}

	@org.springframework.context.annotation.Bean
	public org.bytesoft.bytetcc.CompensableCoordinator bytetccCompensableCoordinator() {
		return new org.bytesoft.bytetcc.CompensableCoordinator();
	}

	@org.springframework.context.annotation.Bean
	public org.bytesoft.bytetcc.TransactionCoordinator bytetccTransactionCoordinator() {
		return new org.bytesoft.bytetcc.TransactionCoordinator();
	}

	@org.springframework.context.annotation.Bean
	public org.bytesoft.bytetcc.TransactionRecoveryImpl bytetccTransactionRecovery() {
		return new org.bytesoft.bytetcc.TransactionRecoveryImpl();
	}

	@org.springframework.context.annotation.Bean
	public org.bytesoft.bytejta.TransactionRepositoryImpl bytetccTransactionRepository() {
		return new org.bytesoft.bytejta.TransactionRepositoryImpl();
	}

	@org.springframework.context.annotation.Bean
	public org.bytesoft.bytetcc.xa.XidFactoryImpl bytetccXidFactory() {
		return new org.bytesoft.bytetcc.xa.XidFactoryImpl();
	}

	@org.springframework.context.annotation.Bean
	public org.bytesoft.bytetcc.supports.rpc.CompensableInterceptorImpl bytetccCompensableInterceptor() {
		return new org.bytesoft.bytetcc.supports.rpc.CompensableInterceptorImpl();
	}

	@org.springframework.context.annotation.Bean
	public org.bytesoft.bytetcc.supports.logging.MongoCompensableLogger bytetccCompensableLogger() {
		return new org.bytesoft.bytetcc.supports.logging.MongoCompensableLogger();
	}

	@org.springframework.context.annotation.Bean
	public org.bytesoft.bytetcc.supports.spring.SpringContainerContextImpl springContainerContext() {
		return new org.bytesoft.bytetcc.supports.spring.SpringContainerContextImpl();
	}

	@org.springframework.context.annotation.Bean
	public org.bytesoft.bytetcc.CompensableContextImpl bytetccCompensableContext() {
		return new org.bytesoft.bytetcc.CompensableContextImpl();
	}

	@org.springframework.context.annotation.Bean
	public org.bytesoft.bytetcc.work.CommandManager bytetccCommandManager() {
		return new org.bytesoft.bytetcc.supports.work.CompensableCommandManager();
	}

	@org.springframework.context.annotation.Bean
	public org.bytesoft.bytetcc.supports.work.CompensableCleanupWork bytetccCleanupWork() {
		return new org.bytesoft.bytetcc.supports.work.CompensableCleanupWork();
	}

	@org.springframework.context.annotation.Bean
	public org.bytesoft.bytetcc.supports.internal.MongoCompensableLock bytetccTransactionLock() {
		return new org.bytesoft.bytetcc.supports.internal.MongoCompensableLock();
	}

	@org.springframework.context.annotation.Bean
	public org.bytesoft.transaction.logging.ArchiveDeserializer bytetccTransactionDeserializer(
			@org.springframework.beans.factory.annotation.Autowired XAResourceDeserializer resourceDeserializer) {
		org.bytesoft.bytetcc.logging.deserializer.TransactionArchiveDeserializer archiveDeserializer = new org.bytesoft.bytetcc.logging.deserializer.TransactionArchiveDeserializer();
		archiveDeserializer.setCompensableArchiveDeserializer(this.bytetccCompensableDeserializer());
		// archiveDeserializer.setResourceArchiveDeserializer(this.bytetccXAResourceDeserializer());
		return archiveDeserializer;
	}

	@org.springframework.context.annotation.Bean
	public org.bytesoft.transaction.logging.ArchiveDeserializer bytetccXAResourceDeserializer(
			@org.springframework.beans.factory.annotation.Autowired XAResourceDeserializer resourceDeserializer) {
		org.bytesoft.bytetcc.logging.deserializer.XAResourceArchiveDeserializer archiveDeserializer = new org.bytesoft.bytetcc.logging.deserializer.XAResourceArchiveDeserializer();
		archiveDeserializer.setDeserializer(resourceDeserializer);
		return archiveDeserializer;
	}

	@org.springframework.context.annotation.Bean
	public org.bytesoft.transaction.logging.ArchiveDeserializer bytetccCompensableDeserializer() {
		return new org.bytesoft.bytetcc.logging.deserializer.CompensableArchiveDeserializer();
	}

	@org.springframework.context.annotation.Bean
	public org.bytesoft.transaction.logging.ArchiveDeserializer bytetccArchiveDeserializer() {
		org.bytesoft.bytetcc.logging.ArchiveDeserializerImpl archiveDeserializer = new org.bytesoft.bytetcc.logging.ArchiveDeserializerImpl();
		// archiveDeserializer.setTransactionArchiveDeserializer(this.bytetccTransactionDeserializer());
		// archiveDeserializer.setXaResourceArchiveDeserializer(this.bytetccXAResourceDeserializer());
		archiveDeserializer.setCompensableArchiveDeserializer(this.bytetccCompensableDeserializer());
		return archiveDeserializer;
	}

	@org.springframework.context.annotation.Bean
	public org.bytesoft.compensable.CompensableBeanFactory CompensableBeanFactory(
			@org.springframework.beans.factory.annotation.Autowired TransactionManager bytejtaTransactionManager,
			@org.springframework.beans.factory.annotation.Autowired XidFactory bytejtaXidFactory,
			@org.springframework.beans.factory.annotation.Autowired TransactionRepository bytejtaTransactionRepository,
			@org.springframework.beans.factory.annotation.Autowired TransactionRecovery bytejtaTransactionRecovery,
			@org.springframework.beans.factory.annotation.Autowired RemoteCoordinator bytejtaTransactionCoordinator,
			@org.springframework.beans.factory.annotation.Autowired XAResourceDeserializer bytetccResourceDeserializer) {
		org.bytesoft.bytetcc.TransactionBeanFactoryImpl beanFactory = new org.bytesoft.bytetcc.TransactionBeanFactoryImpl();
		beanFactory.setTransactionManager(bytejtaTransactionManager);
		beanFactory.setCompensableManager(this.bytetccCompensableManager());
		beanFactory.setTransactionXidFactory(bytejtaXidFactory);
		beanFactory.setCompensableXidFactory(this.bytetccXidFactory());
		beanFactory.setCompensableLogger(this.bytetccCompensableLogger());
		beanFactory.setTransactionRepository(bytejtaTransactionRepository);
		beanFactory.setCompensableRepository(this.bytetccTransactionRepository());
		beanFactory.setTransactionInterceptor(this.bytetccCompensableInterceptor());
		beanFactory.setTransactionRecovery(bytejtaTransactionRecovery);
		beanFactory.setCompensableRecovery(this.bytetccTransactionRecovery());
		beanFactory.setTransactionCoordinator(bytejtaTransactionCoordinator);
		beanFactory.setCompensableCoordinator(this.bytetccCompensableCoordinator());
		beanFactory.setContainerContext(this.springContainerContext());
		beanFactory.setArchiveDeserializer(this.bytetccArchiveDeserializer());
		beanFactory.setResourceDeserializer(bytetccResourceDeserializer);
		beanFactory.setLocalResourceCleaner(this.bytetccCleanupWork());
		beanFactory.setCompensableContext(this.bytetccCompensableContext());
		beanFactory.setCompensableLock(this.bytetccTransactionLock());
		return beanFactory;
	}

}
