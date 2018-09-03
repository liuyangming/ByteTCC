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

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import javax.resource.spi.work.Work;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.leader.LeaderSelector;
import org.apache.curator.framework.recipes.leader.LeaderSelectorListener;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException.NodeExistsException;
import org.bytesoft.bytetcc.work.CommandManager;
import org.bytesoft.common.utils.CommonUtils;
import org.bytesoft.transaction.aware.TransactionEndpointAware;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;

public class CompensableCommandManager
		implements SmartInitializingSingleton, TransactionEndpointAware, LeaderSelectorListener, CommandManager, Work {
	static final Logger logger = LoggerFactory.getLogger(CompensableCommandManager.class);

	static final String CONSTANTS_ROOT_PATH = "/org/bytesoft/bytetcc";

	@javax.annotation.Resource(name = "compensableCuratorFramework")
	private CuratorFramework curatorFramework;

	private Lock stateLock = new ReentrantLock();
	private Condition stateCondition = this.stateLock.newCondition();

	private Lock workLock = new ReentrantLock();
	private Condition workCondition = this.workLock.newCondition();

	private String endpoint;

	private volatile ConnectionState state;
	private LeaderSelector leadSelector;

	private volatile boolean released = false;

	private final Queue<ExecutionWork> works = new LinkedList<ExecutionWork>();

	public void execute(Runnable runnable) throws Exception {
		this.execute(new CallableImpl(runnable));
	}

	public Object execute(Callable<?> callable) throws Exception {
		if (this.hasLeadership() == false) {
			throw new IllegalStateException("Current node is not master!");
		} else if (ConnectionState.CONNECTED.equals(this.state) == false) {
			throw new IllegalStateException("State is not Connected!");
		}

		ExecutionWork work = new ExecutionWork();
		this.registerTask(work);
		return this.waitForResult(work);
	}

	private void registerTask(ExecutionWork work) {
		try {
			this.workLock.lock();
			this.works.offer(work);
			this.workCondition.signalAll();
		} finally {
			this.workLock.unlock();
		}
	}

	private Object waitForResult(ExecutionWork work) throws Exception {
		try {
			work.lock.lock();
			work.condition.awaitUninterruptibly();
			return this.retrieveResult(work);
		} finally {
			work.lock.unlock();
		}
	}

	private Object retrieveResult(ExecutionWork work) throws Exception {
		if (work.error == false) {
			return work.result;
		} else if (Exception.class.isInstance(work.result)) {
			throw (Exception) work.result;
		} else {
			throw new Exception((Throwable) work.result);
		}
	}

	public void run() {
		while (this.released == false) {
			this.executeTaskList();
			this.waitForMillis(10);
		}
	}

	private void executeTaskList() {
		List<ExecutionWork> tasks = new ArrayList<ExecutionWork>();
		try {
			this.workLock.lock();
			for (int i = 0; i < 10 && this.works.isEmpty() == false; i++) {
				ExecutionWork task = this.works.poll();
				tasks.add(task);
			}
		} finally {
			this.workLock.unlock();
		}

		for (int i = 0; i < tasks.size(); i++) {
			ExecutionWork task = tasks.get(i);
			this.executeTask(task);
		}
	}

	private void executeTask(ExecutionWork work) {
		try {
			work.lock.lock();
			if (ConnectionState.CONNECTED.equals(this.state) == false) {
				throw new IllegalStateException("Current node is no longer the master!");
			}
			work.result = work.callable.call();
			work.error = false;
			work.condition.signalAll();
		} catch (Exception ex) {
			work.result = ex;
			work.error = true;
			work.condition.signalAll();
		} finally {
			work.lock.unlock();
		}
	}

	public void takeLeadership(CuratorFramework client) throws Exception {
		try {
			this.stateLock.lock();
			if (ConnectionState.CONNECTED.equals(this.state) == false) {
				logger.debug("Wrong state! Re-elect the master node.");
				return;
			}

			this.stateCondition.awaitUninterruptibly();
		} finally {
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
			this.state = newState;
			this.stateCondition.signalAll();
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

	public void release() {
		this.released = true;
	}

	private void waitForMillis(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException ex) {
			logger.debug(ex.getMessage());
		}
	}

	static class ExecutionWork {
		public Lock lock = new ReentrantLock();
		public Condition condition = this.lock.newCondition();
		public boolean error;
		public Object result;
		public Callable<Object> callable;
	}

	static class CallableImpl implements Callable<Object> {
		private Runnable runnable;

		public CallableImpl(Runnable runnable) {
			this.runnable = runnable;
		}

		public Object call() throws Exception {
			this.runnable.run();
			return null;
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

	public void setEndpoint(String identifier) {
		this.endpoint = identifier;
	}

}
