/**
 * Copyright 2014-2017 yangming.liu<bytefox@126.com>.
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
package org.bytesoft.bytetcc.supports.dubbo.config;

import org.bytesoft.bytetcc.CompensableCoordinator;
import org.bytesoft.bytetcc.CompensableManagerImpl;
import org.bytesoft.bytetcc.TransactionManagerImpl;
import org.bytesoft.bytetcc.TransactionRecoveryImpl;
import org.bytesoft.bytetcc.UserCompensableImpl;
import org.bytesoft.bytetcc.supports.spring.SpringContextRegistry;
import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.aware.CompensableBeanFactoryAware;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.ImportResource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.TransactionManagementConfigurer;
import org.springframework.transaction.jta.JtaTransactionManager;

@ImportResource({ "classpath:bytetcc-disable-tx-advice.xml", "classpath:bytetcc-supports-dubbo-secondary.xml" })
@EnableAspectJAutoProxy(proxyTargetClass = true)
@EnableTransactionManagement
public class DubboSecondaryConfiguration implements TransactionManagementConfigurer, ApplicationContextAware,
		SmartInitializingSingleton, InitializingBean, CompensableBeanFactoryAware {
	private ApplicationContext applicationContext;
	private CompensableBeanFactory beanFactory;

	public void afterSingletonsInstantiated() {
		CompensableManagerImpl compensableManager = this.applicationContext.getBean(CompensableManagerImpl.class);
		CompensableCoordinator compensableCoordinator = this.applicationContext.getBean(CompensableCoordinator.class);
		UserCompensableImpl userCompensable = this.applicationContext.getBean(UserCompensableImpl.class);
		TransactionRecoveryImpl compensableRecovery = this.applicationContext.getBean(TransactionRecoveryImpl.class);
		compensableManager.setStatefully(true);
		compensableCoordinator.setStatefully(true);
		userCompensable.setStatefully(true);
		compensableRecovery.setStatefully(true);
	}

	public void afterPropertiesSet() throws Exception {
	}

	public PlatformTransactionManager annotationDrivenTransactionManager() {
		JtaTransactionManager jtaTransactionManager = new JtaTransactionManager();
		jtaTransactionManager.setTransactionManager(this.applicationContext.getBean(TransactionManagerImpl.class));
		jtaTransactionManager.setUserTransaction(this.applicationContext.getBean(UserCompensableImpl.class));

		SpringContextRegistry springContextRegistry = SpringContextRegistry.getInstance();
		springContextRegistry.setApplicationContext(this.applicationContext);
		springContextRegistry.setBeanFactory(this.beanFactory);
		springContextRegistry.setTransactionManager(jtaTransactionManager);
		return springContextRegistry.getTransactionManager();
	}

	@org.springframework.context.annotation.Bean("jtaTransactionManager")
	public PlatformTransactionManager jtaTransactionManager() {
		return SpringContextRegistry.getInstance().getTransactionManager();
	}

	public CompensableBeanFactory getBeanFactory() {
		return beanFactory;
	}

	public void setBeanFactory(CompensableBeanFactory beanFactory) {
		this.beanFactory = beanFactory;
	}

	public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
		this.applicationContext = applicationContext;
	}

}
