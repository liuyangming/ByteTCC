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

@org.springframework.context.annotation.Configuration
public class TransactionConfiguration {

	@org.springframework.context.annotation.Bean
	public org.bytesoft.bytejta.supports.spring.ManagedConnectionFactoryPostProcessor managedConnectionFactoryPostProcessor() {
		return new org.bytesoft.bytejta.supports.spring.ManagedConnectionFactoryPostProcessor();
	}

	@org.springframework.context.annotation.Bean
	public org.bytesoft.bytejta.TransactionManagerImpl bytejtaTransactionManager() {
		return new org.bytesoft.bytejta.TransactionManagerImpl();
	}

	@org.springframework.context.annotation.Bean
	public org.bytesoft.bytejta.TransactionCoordinator bytejtaTransactionCoordinator() {
		return new org.bytesoft.bytejta.TransactionCoordinator();
	}

	@org.springframework.context.annotation.Bean
	public org.bytesoft.bytejta.TransactionRecoveryImpl bytejtaTransactionRecovery() {
		return new org.bytesoft.bytejta.TransactionRecoveryImpl();
	}

	@org.springframework.context.annotation.Bean
	public org.bytesoft.bytejta.TransactionRepositoryImpl bytejtaTransactionRepository() {
		return new org.bytesoft.bytejta.TransactionRepositoryImpl();
	}

	@org.springframework.context.annotation.Bean
	public org.bytesoft.bytejta.xa.XidFactoryImpl bytejtaXidFactory() {
		return new org.bytesoft.bytejta.xa.XidFactoryImpl();
	}

	@org.springframework.context.annotation.Bean
	public org.bytesoft.bytejta.supports.rpc.TransactionInterceptorImpl bytejtaTransactionInterceptor() {
		return new org.bytesoft.bytejta.supports.rpc.TransactionInterceptorImpl();
	}

	@org.springframework.context.annotation.Bean
	public org.bytesoft.bytetcc.logging.SampleTransactionLogger bytejtaTransactionLogger() {
		return new org.bytesoft.bytetcc.logging.SampleTransactionLogger();
	}

	@org.springframework.context.annotation.Bean
	public org.bytesoft.bytejta.VacantTransactionLock bytejtaTransactionLock() {
		return new org.bytesoft.bytejta.VacantTransactionLock();
	}

}
