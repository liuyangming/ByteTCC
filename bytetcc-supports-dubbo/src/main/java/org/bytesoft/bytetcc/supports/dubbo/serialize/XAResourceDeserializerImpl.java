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
package org.bytesoft.bytetcc.supports.dubbo.serialize;

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
import org.bytesoft.bytejta.supports.resource.CommonResourceDescriptor;
import org.bytesoft.bytejta.supports.resource.LocalXAResourceDescriptor;
import org.bytesoft.bytejta.supports.resource.RemoteResourceDescriptor;
import org.bytesoft.bytejta.supports.wire.RemoteCoordinator;
import org.bytesoft.bytejta.supports.wire.RemoteCoordinatorRegistry;
import org.bytesoft.bytetcc.supports.dubbo.CompensableBeanRegistry;
import org.bytesoft.transaction.supports.resource.XAResourceDescriptor;
import org.bytesoft.transaction.supports.serialize.XAResourceDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

public class XAResourceDeserializerImpl implements XAResourceDeserializer, ApplicationContextAware {
	static final Logger logger = LoggerFactory.getLogger(XAResourceDeserializerImpl.class);

	private static Pattern pattern = Pattern.compile("^[^:]+\\s*:\\s*[^:]+\\s*:\\s*\\d+$");
	private ApplicationContext applicationContext;

	private Map<String, XAResourceDescriptor> cachedResourceMap = new ConcurrentHashMap<String, XAResourceDescriptor>();

	public XAResourceDescriptor deserialize(String identifier) {
		try {
			Object bean = this.applicationContext.getBean(identifier);
			XAResourceDescriptor cachedResource = this.cachedResourceMap.get(identifier);
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
					invocationContext.setServiceKey(array[1]);
					invocationContext.setServerPort(Integer.valueOf(array[2]));

					CompensableBeanRegistry beanRegistry = CompensableBeanRegistry.getInstance();
					RemoteCoordinator consumeCoordinator = beanRegistry.getConsumeCoordinator();

					DubboRemoteCoordinator dubboCoordinator = new DubboRemoteCoordinator();
					dubboCoordinator.setInvocationContext(invocationContext);
					dubboCoordinator.setRemoteCoordinator(consumeCoordinator);

					coordinator = (RemoteCoordinator) Proxy.newProxyInstance(DubboRemoteCoordinator.class.getClassLoader(),
							new Class[] { RemoteCoordinator.class }, dubboCoordinator);
					registry.putTransactionManagerStub(identifier, coordinator);
				}

				RemoteResourceDescriptor descriptor = new RemoteResourceDescriptor();
				descriptor.setIdentifier(identifier);
				descriptor.setDelegate(registry.getTransactionManagerStub(identifier));

				return descriptor;
			} else {
				logger.error("can not find a matching xa-resource(identifier= {})!", identifier);
				return null;
			}
		} catch (Exception ex) {
			logger.error("can not find a matching xa-resource(identifier= {})!", identifier);
			return null;
		}

	}

	private XAResourceDescriptor deserializeResource(String identifier, Object bean) throws Exception {
		if (DataSourceHolder.class.isInstance(bean)) {
			DataSourceHolder holder = (DataSourceHolder) bean;
			RecoveredResource xares = new RecoveredResource();
			xares.setDataSource(holder.getDataSource());

			LocalXAResourceDescriptor descriptor = new LocalXAResourceDescriptor();
			descriptor.setDelegate(xares);
			descriptor.setIdentifier(identifier);

			return descriptor;
		} else if (javax.sql.DataSource.class.isInstance(bean)) {
			javax.sql.DataSource dataSource = (javax.sql.DataSource) bean;
			RecoveredResource xares = new RecoveredResource();
			xares.setDataSource(dataSource);

			LocalXAResourceDescriptor descriptor = new LocalXAResourceDescriptor();
			descriptor.setDelegate(xares);
			descriptor.setIdentifier(identifier);

			return descriptor;
		} else if (XADataSource.class.isInstance(bean)) {
			XADataSource xaDataSource = (XADataSource) bean;
			XAConnection xaConnection = xaDataSource.getXAConnection();
			java.sql.Connection connection = null;
			try {
				connection = xaConnection.getConnection();
				XAResource xares = xaConnection.getXAResource();

				CommonResourceDescriptor descriptor = new CommonResourceDescriptor();
				descriptor.setDelegate(xares);
				descriptor.setIdentifier(identifier);
				descriptor.setManaged(xaConnection);

				return descriptor;
			} catch (Exception ex) {
				logger.warn(ex.getMessage());

				XAResource xares = xaConnection.getXAResource();

				CommonResourceDescriptor descriptor = new CommonResourceDescriptor();
				descriptor.setDelegate(xares);
				descriptor.setIdentifier(identifier);
				descriptor.setManaged(xaConnection);

				return descriptor;
			} finally {
				this.closeQuietly(connection);
			}
		} else if (XAConnectionFactory.class.isInstance(bean)) {
			XAConnectionFactory connectionFactory = (XAConnectionFactory) bean;
			javax.jms.XAConnection xaConnection = connectionFactory.createXAConnection();
			XASession xaSession = xaConnection.createXASession();
			javax.jms.Session session = null;
			try {
				session = xaSession.getSession();
				XAResource xares = xaSession.getXAResource();

				CommonResourceDescriptor descriptor = new CommonResourceDescriptor();
				descriptor.setDelegate(xares);
				descriptor.setIdentifier(identifier);
				descriptor.setManaged(xaConnection);

				return descriptor;
			} catch (Exception ex) {
				logger.warn(ex.getMessage());

				XAResource xares = xaSession.getXAResource();

				CommonResourceDescriptor descriptor = new CommonResourceDescriptor();
				descriptor.setDelegate(xares);
				descriptor.setIdentifier(identifier);
				descriptor.setManaged(xaConnection);

				return descriptor;
			} finally {
				this.closeQuietly(session);
			}
		} else if (ManagedConnectionFactory.class.isInstance(bean)) {
			ManagedConnectionFactory connectionFactory = (ManagedConnectionFactory) bean;
			ManagedConnection managedConnection = connectionFactory.createManagedConnection(null, null);
			javax.resource.cci.Connection connection = null;
			try {
				connection = (javax.resource.cci.Connection) managedConnection.getConnection(null, null);
				XAResource xares = managedConnection.getXAResource();

				CommonResourceDescriptor descriptor = new CommonResourceDescriptor();
				descriptor.setDelegate(xares);
				descriptor.setIdentifier(identifier);
				descriptor.setManaged(managedConnection);

				return descriptor;
			} catch (Exception ex) {
				logger.warn(ex.getMessage());

				XAResource xares = managedConnection.getXAResource();

				CommonResourceDescriptor descriptor = new CommonResourceDescriptor();
				descriptor.setDelegate(xares);
				descriptor.setIdentifier(identifier);
				descriptor.setManaged(managedConnection);

				return descriptor;
			} finally {
				this.closeQuietly(connection);
			}
		} else {
			return null;
		}

	}

	protected void closeQuietly(javax.resource.cci.Connection closeable) {
		if (closeable != null) {
			try {
				closeable.close();
			} catch (Exception ex) {
				logger.debug(ex.getMessage());
			}
		}
	}

	protected void closeQuietly(java.sql.Connection closeable) {
		if (closeable != null) {
			try {
				closeable.close();
			} catch (Exception ex) {
				logger.debug(ex.getMessage());
			}
		}
	}

	protected void closeQuietly(javax.jms.Session closeable) {
		if (closeable != null) {
			try {
				closeable.close();
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
