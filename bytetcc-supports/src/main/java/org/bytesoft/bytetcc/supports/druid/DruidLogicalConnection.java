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

import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Struct;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;

import com.alibaba.druid.pool.DruidPooledConnection;

public class DruidLogicalConnection implements Connection {
	private boolean connectionClosed;
	private final DruidLocalXAConnection managedConnection;
	private final DruidPooledConnection delegateConnection;

	public DruidLogicalConnection(DruidLocalXAConnection managedConnection, DruidPooledConnection connection) {
		this.managedConnection = managedConnection;
		this.delegateConnection = connection;
	}

	public <T> T unwrap(Class<T> iface) throws SQLException {
		return delegateConnection.unwrap(iface);
	}

	public boolean isWrapperFor(Class<?> iface) throws SQLException {
		return delegateConnection.isWrapperFor(iface);
	}

	public Statement createStatement() throws SQLException {
		return delegateConnection.createStatement();
	}

	public PreparedStatement prepareStatement(String sql) throws SQLException {
		return delegateConnection.prepareStatement(sql);
	}

	public CallableStatement prepareCall(String sql) throws SQLException {
		return delegateConnection.prepareCall(sql);
	}

	public String nativeSQL(String sql) throws SQLException {
		return delegateConnection.nativeSQL(sql);
	}

	public void setAutoCommit(boolean autoCommit) throws SQLException {
		delegateConnection.setAutoCommit(autoCommit);
	}

	public boolean getAutoCommit() throws SQLException {
		return delegateConnection.getAutoCommit();
	}

	public void commit() throws SQLException {
		managedConnection.commitLocalTransaction();
	}

	public void rollback() throws SQLException {
		managedConnection.rollbackLocalTransaction();
	}

	public synchronized void close() throws SQLException {
		if (this.connectionClosed) {
			// ignore
		} else {
			this.connectionClosed = true;
			managedConnection.closeLogicalConnection();
		}
	}

	public boolean isClosed() throws SQLException {
		return delegateConnection.isClosed();
	}

	public DatabaseMetaData getMetaData() throws SQLException {
		return delegateConnection.getMetaData();
	}

	public void setReadOnly(boolean readOnly) throws SQLException {
		delegateConnection.setReadOnly(readOnly);
	}

	public boolean isReadOnly() throws SQLException {
		return delegateConnection.isReadOnly();
	}

	public void setCatalog(String catalog) throws SQLException {
		delegateConnection.setCatalog(catalog);
	}

	public String getCatalog() throws SQLException {
		return delegateConnection.getCatalog();
	}

	public void setTransactionIsolation(int level) throws SQLException {
		delegateConnection.setTransactionIsolation(level);
	}

	public int getTransactionIsolation() throws SQLException {
		return delegateConnection.getTransactionIsolation();
	}

	public SQLWarning getWarnings() throws SQLException {
		return delegateConnection.getWarnings();
	}

	public void clearWarnings() throws SQLException {
		delegateConnection.clearWarnings();
	}

	public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
		return delegateConnection.createStatement(resultSetType, resultSetConcurrency);
	}

	public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency)
			throws SQLException {
		return delegateConnection.prepareStatement(sql, resultSetType, resultSetConcurrency);
	}

	public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
		return delegateConnection.prepareCall(sql, resultSetType, resultSetConcurrency);
	}

	public Map<String, Class<?>> getTypeMap() throws SQLException {
		return delegateConnection.getTypeMap();
	}

	public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
		delegateConnection.setTypeMap(map);
	}

	public void setHoldability(int holdability) throws SQLException {
		delegateConnection.setHoldability(holdability);
	}

	public int getHoldability() throws SQLException {
		return delegateConnection.getHoldability();
	}

	public Savepoint setSavepoint() throws SQLException {
		return delegateConnection.setSavepoint();
	}

	public Savepoint setSavepoint(String name) throws SQLException {
		return delegateConnection.setSavepoint(name);
	}

	public void rollback(Savepoint savepoint) throws SQLException {
		delegateConnection.rollback(savepoint);
	}

	public void releaseSavepoint(Savepoint savepoint) throws SQLException {
		delegateConnection.releaseSavepoint(savepoint);
	}

	public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability)
			throws SQLException {
		return delegateConnection.createStatement(resultSetType, resultSetConcurrency, resultSetHoldability);
	}

	public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency,
			int resultSetHoldability) throws SQLException {
		return delegateConnection.prepareStatement(sql, resultSetType, resultSetConcurrency, resultSetHoldability);
	}

	public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency,
			int resultSetHoldability) throws SQLException {
		return delegateConnection.prepareCall(sql, resultSetType, resultSetConcurrency, resultSetHoldability);
	}

	public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
		return delegateConnection.prepareStatement(sql, autoGeneratedKeys);
	}

	public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
		return delegateConnection.prepareStatement(sql, columnIndexes);
	}

	public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
		return delegateConnection.prepareStatement(sql, columnNames);
	}

	public Clob createClob() throws SQLException {
		return delegateConnection.createClob();
	}

	public Blob createBlob() throws SQLException {
		return delegateConnection.createBlob();
	}

	public NClob createNClob() throws SQLException {
		return delegateConnection.createNClob();
	}

	public SQLXML createSQLXML() throws SQLException {
		return delegateConnection.createSQLXML();
	}

	public boolean isValid(int timeout) throws SQLException {
		return delegateConnection.isValid(timeout);
	}

	public void setClientInfo(String name, String value) throws SQLClientInfoException {
		delegateConnection.setClientInfo(name, value);
	}

	public void setClientInfo(Properties properties) throws SQLClientInfoException {
		delegateConnection.setClientInfo(properties);
	}

	public String getClientInfo(String name) throws SQLException {
		return delegateConnection.getClientInfo(name);
	}

	public Properties getClientInfo() throws SQLException {
		return delegateConnection.getClientInfo();
	}

	public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
		return delegateConnection.createArrayOf(typeName, elements);
	}

	public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
		return delegateConnection.createStruct(typeName, attributes);
	}

	public void setSchema(String schema) throws SQLException {
		delegateConnection.setSchema(schema);
	}

	public String getSchema() throws SQLException {
		return delegateConnection.getSchema();
	}

	public void abort(Executor executor) throws SQLException {
		delegateConnection.abort(executor);
	}

	public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
		delegateConnection.setNetworkTimeout(executor, milliseconds);
	}

	public int getNetworkTimeout() throws SQLException {
		return delegateConnection.getNetworkTimeout();
	}

}
