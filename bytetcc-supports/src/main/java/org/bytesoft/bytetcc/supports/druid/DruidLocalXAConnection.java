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
package org.bytesoft.bytetcc.supports.druid;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.ConnectionEventListener;
import javax.sql.StatementEventListener;
import javax.sql.XAConnection;
import javax.transaction.xa.XAResource;

import com.alibaba.druid.pool.DruidPooledConnection;

public class DruidLocalXAConnection implements XAConnection {
	private final DruidPooledConnection druidPooledConnection;
	private final DruidLocalXAResource xaResource = new DruidLocalXAResource();
	private boolean initialized = false;
	private boolean logicalConnectionReleased = false;
	private int pooledConnectionSharingCount = 0;

	public DruidLocalXAConnection(DruidPooledConnection connection) {
		this.druidPooledConnection = connection;
	}

	public Connection getConnection() throws SQLException {
		DruidLogicalConnection logicalConnection = new DruidLogicalConnection(this, this.druidPooledConnection);
		if (this.initialized) {
			this.pooledConnectionSharingCount++;
		} else {
			this.xaResource.setLocalTransaction(logicalConnection);
			this.initialized = true;
			this.logicalConnectionReleased = false;
		}
		return logicalConnection;
	}

	public void closeLogicalConnection() throws SQLException {
		if (this.pooledConnectionSharingCount > 0) {
			this.pooledConnectionSharingCount--;
		} else if (this.initialized) {
			if (this.logicalConnectionReleased) {
				throw new SQLException();
			} else {
				this.logicalConnectionReleased = true;
			}
		} else {
			throw new SQLException();
		}
	}

	public void commitLocalTransaction() throws SQLException {
		try {
			this.druidPooledConnection.commit();
		} catch (SQLException ex) {
			throw ex;
		} catch (RuntimeException ex) {
			throw new SQLException(ex);
		} finally {
			try {
				this.close();
			} catch (SQLException ex) {
				// ignore
			}
		}
	}

	public void rollbackLocalTransaction() throws SQLException {
		try {
			this.druidPooledConnection.rollback();
		} catch (SQLException ex) {
			throw ex;
		} catch (RuntimeException ex) {
			throw new SQLException(ex);
		} finally {
			try {
				this.close();
			} catch (SQLException ex) {
				// ignore
			}
		}
	}

	public void close() throws SQLException {
		try {
			this.druidPooledConnection.close();
		} finally {
			this.initialized = false;
		}
	}

	public void addConnectionEventListener(ConnectionEventListener paramConnectionEventListener) {
		this.druidPooledConnection.addConnectionEventListener(paramConnectionEventListener);
	}

	public void removeConnectionEventListener(ConnectionEventListener paramConnectionEventListener) {
		this.druidPooledConnection.removeConnectionEventListener(paramConnectionEventListener);
	}

	public void addStatementEventListener(StatementEventListener paramStatementEventListener) {
		this.druidPooledConnection.addStatementEventListener(paramStatementEventListener);
	}

	public void removeStatementEventListener(StatementEventListener paramStatementEventListener) {
		this.druidPooledConnection.removeStatementEventListener(paramStatementEventListener);
	}

	public XAResource getXAResource() throws SQLException {
		return this.xaResource;
	}

}
