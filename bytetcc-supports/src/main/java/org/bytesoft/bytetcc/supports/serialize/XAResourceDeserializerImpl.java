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
package org.bytesoft.bytetcc.supports.serialize;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.jms.XAConnectionFactory;
import javax.jms.XASession;
import javax.resource.spi.ManagedConnection;
import javax.resource.spi.ManagedConnectionFactory;
import javax.sql.XAConnection;
import javax.sql.XADataSource;
import javax.transaction.xa.XAResource;

import org.bytesoft.bytejta.supports.dubbo.DubboRemoteCoordinator;
import org.bytesoft.bytejta.supports.dubbo.InvocationContext;
import org.bytesoft.bytejta.supports.jdbc.DataSourceHolder;
import org.bytesoft.bytejta.supports.jdbc.RecoveredResource;
import org.bytesoft.bytejta.supports.wire.RemoteCoordinator;
import org.bytesoft.bytejta.supports.wire.RemoteCoordinatorRegistry;
import org.bytesoft.bytetcc.supports.dubbo.CompensableBeanRegistry;
import org.bytesoft.transaction.supports.serialize.XAResourceDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

public class XAResourceDeserializerImpl implements XAResourceDeserializer, ApplicationContextAware {
	static final Logger logger = LoggerFactory.getLogger(XAResourceDeserializerImpl.class);

	private static Pattern pattern = Pattern.compile("^[^:]+\\s*:\\s*\\d+$");
	private ApplicationContext applicationContext;

	private Map<String, XAResource> cachedResourceMap = new ConcurrentHashMap<String, XAResource>();

	public XAResource deserialize(String identifier) {
		try {
			Object bean = this.applicationContext.getBean(identifier);
			XAResource cachedResource = this.cachedResourceMap.get(identifier);
			if (cachedResource == null) {
				cachedResource = this.deserializeResource(identifier, bean);
				if (cachedResource != null) {
					this.cachedResourceMap.put(identifier, cachedResource);
				}
			}
			return cachedResource;
		} catch (BeansException bex) {
			Matcher matcher = pattern.matcher(identifier);
			if (matcher.find()) {
				RemoteCoordinatorRegistry registry = RemoteCoordinatorRegistry.getInstance();
				RemoteCoordinator coordinator = registry.getTransactionManagerStub(identifier);
				if (coordinator == null) {
					String[] array = identifier.split("\\:");
					InvocationContext invocationContext = new InvocationContext();
					invocationContext.setServerHost(array[0]);
					invocationContext.setServerPort(Integer.valueOf(array[1]));

					CompensableBeanRegistry beanRegistry = CompensableBeanRegistry.getInstance();
					RemoteCoordinator consumeCoordinator = beanRegistry.getConsumeCoordinator();

					DubboRemoteCoordinator dubboCoordinator = new DubboRemoteCoordinator();
					dubboCoordinator.setInvocationContext(invocationContext);
					dubboCoordinator.setRemoteCoordinator(consumeCoordinator);

					coordinator = (RemoteCoordinator) Proxy.newProxyInstance(DubboRemoteCoordinator.class.getClassLoader(),
							new Class[] { RemoteCoordinator.class }, dubboCoordinator);
					registry.putTransactionManagerStub(identifier, coordinator);
				}

				return registry.getTransactionManagerStub(identifier);
			} else {
				logger.error("can not find a matching xa-resource(identifier= {})!", identifier);
				return null;
			}
		} catch (Exception ex) {
			logger.error("can not find a matching xa-resource(identifier= {})!", identifier);
			return null;
		}

	}

	private XAResource deserializeResource(String identifier, Object bean) throws Exception {
		if (DataSourceHolder.class.isInstance(bean)) {
			DataSourceHolder holder = (DataSourceHolder) bean;
			RecoveredResource xares = new RecoveredResource();
			xares.setDataSource(holder.getDataSource());
			return xares;
		} else if (javax.sql.DataSource.class.isInstance(bean)) {
			javax.sql.DataSource dataSource = (javax.sql.DataSource) bean;
			RecoveredResource xares = new RecoveredResource();
			xares.setDataSource(dataSource);
			return xares;
		} else if (XADataSource.class.isInstance(bean)) {
			XADataSource xaDataSource = (XADataSource) bean;
			XAConnection xaConnection = null;
			try {
				xaConnection = xaDataSource.getXAConnection();
				return xaConnection.getXAResource();
			} finally {
				this.closeQuietly(xaConnection);
			}
		} else if (XAConnectionFactory.class.isInstance(bean)) {
			XAConnectionFactory connectionFactory = (XAConnectionFactory) bean;
			javax.jms.XAConnection xaConnection = null;
			XASession xaSession = null;
			try {
				xaConnection = connectionFactory.createXAConnection();
				xaSession = xaConnection.createXASession();
				return xaSession.getXAResource();
			} finally {
				this.closeQuietly(xaSession);
				this.closeQuietly(xaConnection);
			}
		} else if (ManagedConnectionFactory.class.isInstance(bean)) {
			ManagedConnectionFactory connectionFactory = (ManagedConnectionFactory) bean;
			ManagedConnection managedConnection = null;
			try {
				managedConnection = connectionFactory.createManagedConnection(null, null);
				return managedConnection.getXAResource();
			} finally {
				this.closeQuietly(managedConnection);
			}
		} else {
			return null;
		}

	}

	protected void closeQuietly(XAConnection closeable) {
		if (closeable != null) {
			try {
				closeable.close();
			} catch (Exception ex) {
				logger.debug(ex.getMessage());
			}
		}
	}

	protected void closeQuietly(javax.jms.XAConnection closeable) {
		if (closeable != null) {
			try {
				closeable.close();
			} catch (Exception ex) {
				logger.debug(ex.getMessage());
			}
		}
	}

	protected void closeQuietly(javax.jms.XASession closeable) {
		if (closeable != null) {
			try {
				closeable.close();
			} catch (Exception ex) {
				logger.debug(ex.getMessage());
			}
		}
	}

	protected void closeQuietly(ManagedConnection closeable) {
		if (closeable != null) {
			try {
				closeable.destroy();
			} catch (Exception ex) {
				logger.debug(ex.getMessage());
			}
		}
	}

	public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
		this.applicationContext = applicationContext;
	}

	public ApplicationContext getApplicationContext() {
		return applicationContext;
	}

}
