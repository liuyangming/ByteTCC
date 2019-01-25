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

import java.util.List;

import org.bytesoft.bytetcc.TransactionManagerImpl;
import org.bytesoft.bytetcc.UserCompensableImpl;
import org.bytesoft.bytetcc.supports.spring.SpringContextRegistry;
import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.aware.CompensableBeanFactoryAware;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.ImportResource;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.TransactionManagementConfigurer;
import org.springframework.transaction.jta.JtaTransactionManager;

import com.mongodb.ServerAddress;
import com.mongodb.client.MongoClients;

@ImportResource({ "classpath:bytetcc-supports-dubbo.xml" })
@EnableAspectJAutoProxy(proxyTargetClass = true)
@EnableTransactionManagement
public class DubboSupportConfiguration
		implements TransactionManagementConfigurer, ApplicationContextAware, EnvironmentAware, CompensableBeanFactoryAware {
	static final String CONSTANT_MONGODBURI = "spring.data.mongodb.uri";

	private ApplicationContext applicationContext;
	private Environment environment;
	private CompensableBeanFactory beanFactory;

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

	@ConditionalOnMissingBean(com.mongodb.client.MongoClient.class)
	@ConditionalOnProperty(CONSTANT_MONGODBURI)
	@org.springframework.context.annotation.Bean
	public com.mongodb.client.MongoClient mongoClient(@Autowired(required = false) com.mongodb.MongoClient mongoClient) {
		if (mongoClient == null) {
			return MongoClients.create(this.environment.getProperty(CONSTANT_MONGODBURI));
		} else {
			List<ServerAddress> addressList = mongoClient.getAllAddress();
			StringBuilder ber = new StringBuilder();
			for (int i = 0; addressList != null && i < addressList.size(); i++) {
				ServerAddress address = addressList.get(i);
				String host = address.getHost();
				int port = address.getPort();
				if (i == 0) {
					ber.append(host).append(":").append(port);
				} else {
					ber.append(",").append(host).append(":").append(port);
				}
			}
			return MongoClients.create(String.format("mongodb://%s", ber.toString()));
		}
	}

	public void setEnvironment(Environment environment) {
		this.environment = environment;
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
