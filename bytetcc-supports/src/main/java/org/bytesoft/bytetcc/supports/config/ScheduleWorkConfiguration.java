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
public class ScheduleWorkConfiguration {

	@org.springframework.context.annotation.Bean
	public org.bytesoft.bytetcc.work.CompensableWork compensableWork() {
		return new org.bytesoft.bytetcc.work.CompensableWork();
	}

	@org.springframework.context.annotation.Bean
	public org.bytesoft.transaction.work.SimpleWorkManager compensableWorkManager() {
		return new org.bytesoft.transaction.work.SimpleWorkManager();
	}

	@org.springframework.context.annotation.Bean
	public org.bytesoft.transaction.adapter.ResourceAdapterImpl compensableResourceAdapter(
			@org.springframework.beans.factory.annotation.Autowired org.bytesoft.bytetcc.work.CompensableWork compensableWork,
			@org.springframework.beans.factory.annotation.Autowired org.bytesoft.bytetcc.logging.SampleCompensableLogger compensableLogger,
			@org.springframework.beans.factory.annotation.Autowired org.bytesoft.bytetcc.work.vfs.CleanupWork cleanupWork) {
		org.bytesoft.transaction.adapter.ResourceAdapterImpl resourceAdapter = new org.bytesoft.transaction.adapter.ResourceAdapterImpl();
		resourceAdapter.getWorkList().add(compensableWork);
		resourceAdapter.getWorkList().add(compensableLogger);
		resourceAdapter.getWorkList().add(cleanupWork);
		return resourceAdapter;
	}

	@org.springframework.context.annotation.Bean
	public org.springframework.jca.support.ResourceAdapterFactoryBean resourceAdapter(
			@org.springframework.beans.factory.annotation.Autowired org.bytesoft.transaction.adapter.ResourceAdapterImpl resourceAdapter,
			@org.springframework.beans.factory.annotation.Autowired org.bytesoft.transaction.work.SimpleWorkManager workManager) {
		org.springframework.jca.support.ResourceAdapterFactoryBean factory = new org.springframework.jca.support.ResourceAdapterFactoryBean();
		factory.setWorkManager(workManager);
		factory.setResourceAdapter(resourceAdapter);
		return factory;
	}

}
