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
package org.bytesoft.bytetcc.supports.work;

import java.util.concurrent.Callable;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import javax.resource.spi.work.Work;
import javax.resource.spi.work.WorkManager;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.leader.LeaderSelector;
import org.apache.curator.framework.recipes.leader.LeaderSelectorListener;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException.NodeExistsException;
import org.bytesoft.bytetcc.work.CommandManager;
import org.bytesoft.common.utils.CommonUtils;
import org.bytesoft.compensable.aware.CompensableEndpointAware;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;

public class CompensableCommandManager
		implements SmartInitializingSingleton, CompensableEndpointAware, LeaderSelectorListener, CommandManager {
	static final Logger logger = LoggerFactory.getLogger(CompensableCommandManager.class);

	static final String CONSTANTS_ROOT_PATH = "/org/bytesoft/bytetcc";

	@javax.annotation.Resource
	private CuratorFramework curatorFramework;

	private Lock stateLock = new ReentrantLock();
	private Condition stateCondition = this.stateLock.newCondition();
	private volatile Boolean stateDisallowed;
	private volatile boolean permsDisallowed;

	private String endpoint;

	private LeaderSelector leadSelector;

	@javax.annotation.Resource
	private WorkManager workManager;

	public void execute(Runnable runnable) throws Exception {
		if (this.hasLeadership() == false) {
			throw new SecurityException("Current node is not the master!");
		}

		this.checkExecutionPermission();

		WorkImpl work = new WorkImpl(runnable);
		this.workManager.startWork(work);
	}

	public Object execute(Callable<Object> callable) throws Exception {
		if (this.hasLeadership() == false) {
			throw new SecurityException("Current node is not the master!");
		}

		this.checkExecutionPermission();

		WorkImpl work = new WorkImpl(callable);
		this.workManager.startWork(work);
		return work.waitForResultIfNecessary();
	}

	private void checkExecutionPermission() {
		if (this.stateDisallowed != null && this.stateDisallowed) {
			throw new SecurityException("Current state is no longer connected!");
		} else if (this.permsDisallowed) {
			throw new SecurityException("Current node is no longer the master!");
		}
	}

	public void takeLeadership(CuratorFramework client) throws Exception {
		try {
			this.stateLock.lock();
			this.permsDisallowed = false;
			if (this.stateDisallowed != null && this.stateDisallowed) {
				logger.warn("Wrong state! Re-elect the master node.");
			} else {
				this.stateCondition.awaitUninterruptibly();
			}
		} finally {
			this.permsDisallowed = true;
			this.stateLock.unlock();
		}
	}

	public boolean hasLeadership() {
		if (this.leadSelector == null) {
			return false;
		} else {
			return this.leadSelector.hasLeadership();
		}
	}

	public void stateChanged(CuratorFramework client, ConnectionState newState) {
		try {
			this.stateLock.lock();
			this.stateDisallowed = ConnectionState.CONNECTED.equals(newState) == false
					&& ConnectionState.RECONNECTED.equals(newState) == false;

			if (this.stateDisallowed) {
				this.stateCondition.signalAll();
			} // end-if (this.stateDisallowed)
		} finally {
			this.stateLock.unlock();
		}
	}

	public void afterSingletonsInstantiated() {
		String basePath = String.format("%s/%s", CONSTANTS_ROOT_PATH, this.getApplication());
		try {
			this.createPersistentPathIfNecessary(basePath);
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}

		String masterPath = String.format("%s/master", basePath);
		this.leadSelector = new LeaderSelector(this.curatorFramework, masterPath, this);
		this.leadSelector.autoRequeue();
		this.leadSelector.start();
	}

	private void createPersistentPathIfNecessary(String path) throws Exception {
		try {
			this.curatorFramework.create() //
					.creatingParentContainersIfNeeded().withMode(CreateMode.PERSISTENT).forPath(path);
		} catch (NodeExistsException nex) {
			logger.debug("Path exists(path= {})!", path);
		}
	}

	class WorkImpl implements Work {
		private final Lock lock = new ReentrantLock();
		private final Condition condition = this.lock.newCondition();
		private final Callable<Object> callable;
		private final Runnable runnable;
		private Object result;
		private Boolean error;

		public WorkImpl(Callable<Object> callable) {
			this.runnable = null;
			this.callable = callable;
		}

		public WorkImpl(Runnable runnable) {
			this.callable = null;
			this.runnable = runnable;
		}

		public Object waitForResultIfNecessary() throws Exception {
			if (this.callable == null) {
				return null;
			} else {
				return this.waitForResult();
			}
		}

		public Object waitForResult() throws Exception {
			try {
				this.lock.lock();
				if (this.error == null) {
					this.condition.awaitUninterruptibly();
				}

				if (this.error == false) {
					return this.result;
				} else if (Exception.class.isInstance(this.result)) {
					throw (Exception) this.result;
				} else {
					throw new RuntimeException((Throwable) this.result);
				}
			} finally {
				this.lock.unlock();
			}
		}

		public void run() {
			if (this.callable != null) {
				this.executeCallable();
			} else if (this.runnable != null) {
				this.executeRunnable();
			}
		}

		private void executeCallable() {
			try {
				this.lock.lock();
				checkExecutionPermission();
				this.result = this.callable.call();
				this.error = false;
				this.condition.signalAll();
			} catch (Exception error) {
				this.result = error;
				this.error = true;
				this.condition.signalAll();
			} finally {
				this.lock.unlock();
			}
		}

		private void executeRunnable() {
			try {
				this.runnable.run();
			} catch (Exception ex) {
				logger.error("Error occurred while executing task(task= {}).", this.runnable);
			}
		}

		public void release() {
		}

		public Object getResult() {
			return result;
		}

		public void setResult(Object result) {
			this.result = result;
		}

		public Boolean getError() {
			return error;
		}

		public void setError(Boolean error) {
			this.error = error;
		}

	}

	public CuratorFramework getCuratorFramework() {
		return curatorFramework;
	}

	public void setCuratorFramework(CuratorFramework curatorFramework) {
		this.curatorFramework = curatorFramework;
	}

	private String getApplication() {
		return CommonUtils.getApplication(this.endpoint);
	}

	public String getEndpoint() {
		return this.endpoint;
	}

	public void setEndpoint(String identifier) {
		this.endpoint = identifier;
	}

}
