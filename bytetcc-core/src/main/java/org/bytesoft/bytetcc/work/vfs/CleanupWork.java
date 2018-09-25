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
package org.bytesoft.bytetcc.work.vfs;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import javax.resource.spi.work.Work;
import javax.transaction.xa.XAException;
import javax.transaction.xa.Xid;

import org.apache.commons.lang3.StringUtils;
import org.bytesoft.bytejta.supports.jdbc.RecoveredResource;
import org.bytesoft.bytejta.supports.resource.LocalXAResourceDescriptor;
import org.bytesoft.bytetcc.supports.resource.LocalResourceCleaner;
import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.aware.CompensableBeanFactoryAware;
import org.bytesoft.compensable.aware.CompensableEndpointAware;
import org.bytesoft.transaction.supports.serialize.XAResourceDeserializer;
import org.bytesoft.transaction.xa.XidFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CleanupWork implements Work, LocalResourceCleaner, CompensableEndpointAware, CompensableBeanFactoryAware {
	static final Logger logger = LoggerFactory.getLogger(CleanupWork.class);
	static final byte[] IDENTIFIER = "org.bytesoft.bytetcc.resource.cleanup".getBytes();

	static final long SECOND_MILLIS = 1000L;
	static final int MAX_HANDLE_RECORDS = 200;

	static final int CONSTANTS_START_INDEX = IDENTIFIER.length + 2 + 4 + 4;
	static final int CONSTANTS_RES_ID_MAX_SIZE = 23;
	static final int CONSTANTS_RECORD_SIZE = CONSTANTS_RES_ID_MAX_SIZE + XidFactory.GLOBAL_TRANSACTION_LENGTH
			+ XidFactory.BRANCH_QUALIFIER_LENGTH;

	@javax.inject.Inject
	private CompensableBeanFactory beanFactory;
	private final Lock lock = new ReentrantLock();

	private final Lock startLock = new ReentrantLock();
	private final Condition startCond = this.startLock.newCondition();
	private boolean started;

	private File directory;
	private boolean released;
	private String endpoint;

	private final CleanupFile resourceOne = new CleanupFile("resource1.log");
	private final CleanupFile resourceTwo = new CleanupFile("resource2.log");

	private CleanupFile master = null;
	private CleanupFile slaver = null;

	public void initialize() {
		this.resourceOne.setDirectory(this.directory);
		this.resourceOne.setBeanFactory(this.beanFactory);
		this.resourceOne.setEndpoint(this.endpoint);

		this.resourceTwo.setDirectory(this.directory);
		this.resourceTwo.setBeanFactory(this.beanFactory);
		this.resourceTwo.setEndpoint(this.endpoint);

		byte masterFlagOne = this.resourceOne.initialize(true);
		byte masterFlagTwo = this.resourceTwo.initialize(false);

		if (masterFlagOne == 0x1 && masterFlagTwo == 0x0) {
			this.master = this.resourceOne;
			this.slaver = this.resourceTwo;
		} else if (masterFlagOne == 0x0 && masterFlagTwo == 0x1) {
			this.master = this.resourceTwo;
			this.slaver = this.resourceOne;
		} else if (masterFlagOne == 0x0 && masterFlagTwo == 0x0) {
			throw new IllegalStateException("Illegal state!");
		} else if (masterFlagOne == 0x2 && masterFlagTwo == 0x1) {
			this.resourceTwo.markSlaver();
			this.resourceOne.markMaster();

			this.master = this.resourceOne;
			this.slaver = this.resourceTwo;
		} else if (masterFlagOne == 0x2 && masterFlagTwo == 0x0) {
			this.resourceOne.markMaster();

			this.master = this.resourceOne;
			this.slaver = this.resourceTwo;
		} else if (masterFlagOne == 0x1 && masterFlagTwo == 0x2) {
			this.resourceOne.markSlaver();
			this.resourceTwo.markMaster();

			this.master = this.resourceTwo;
			this.slaver = this.resourceOne;
		} else if (masterFlagOne == 0x0 && masterFlagTwo == 0x2) {
			this.resourceTwo.markMaster();

			this.master = this.resourceTwo;
			this.slaver = this.resourceOne;
		} else {
			throw new IllegalStateException("Illegal state!");
		}
	}

	public void startupRecover() {
		this.master.startupRecover();
		this.slaver.startupRecover();
	}

	public void destroy() {
		this.resourceOne.destroy();
		this.resourceTwo.destroy();
	}

	public void run() {
		this.startupRecover(); // initialize

		this.markStartupDone();

		long swapMillis = System.currentTimeMillis() + 1000L * 30;

		while (this.released == false) {
			if (System.currentTimeMillis() < swapMillis) {
				this.waitingFor(100);
			} else {
				this.switchMasterAndSlaver();
				swapMillis = System.currentTimeMillis() + 1000L * 30;

				this.cleanupSlaver();
				this.compressSlaver();
			}
		}

		this.destroy();

	}

	protected void waitingFor(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException ex) {
			logger.error(ex.getMessage());
		}
	}

	private void compressSlaver() {
		this.slaver.timingCompress(); // compress
	}

	private void cleanupSlaver() {
		Map<String, Set<CleanupRecord>> recordMap = this.slaver.getRecordMap();
		Set<Map.Entry<String, Set<CleanupRecord>>> entrySet = recordMap.entrySet();
		Iterator<Map.Entry<String, Set<CleanupRecord>>> itr = entrySet.iterator();

		while (itr.hasNext()) {
			Map.Entry<String, Set<CleanupRecord>> entry = itr.next();
			String resourceId = entry.getKey();
			Set<CleanupRecord> records = entry.getValue();

			this.cleanupByResource(resourceId, records);
		}

	}

	private void cleanupByResource(String resourceId, Set<CleanupRecord> records) {
		int remain = records.size();
		Iterator<CleanupRecord> recordItr = records.iterator();
		while (recordItr.hasNext()) {
			List<CleanupRecord> recordList = new ArrayList<CleanupRecord>();
			List<Xid> xidList = new ArrayList<Xid>();

			int defaultBatchSize = 2000;
			int maxBatchSize = defaultBatchSize * 5 / 4;
			int batchSize = remain > defaultBatchSize && remain < maxBatchSize ? remain : defaultBatchSize;
			for (int i = 0; i < batchSize && recordItr.hasNext(); i++, remain--) {
				CleanupRecord record = recordItr.next();
				recordList.add(record);
				xidList.add(record.getXid());
			} // end-for (int i = 0; i < batchSize && recordItr.hasNext(); i++, remain--)

			try {
				this.cleanup(resourceId, xidList);
			} catch (RuntimeException rex) {
				logger.error("forget-transaction: error occurred while forgetting branch: resource= {}, xids= {}", resourceId,
						xidList, rex);
				return; // continue;
			}

			for (int i = 0; i < recordList.size(); i++) {
				CleanupRecord record = recordList.get(i);
				int recordFlag = record.getRecordFlag();
				record.setRecordFlag(recordFlag | 0x2);
			}

		} // end-while (recordItr.hasNext())
	}

	public void switchMasterAndSlaver() {
		try {
			this.lock.lock();
			this.slaver.markPrepare();
			this.master.markSlaver();
			this.slaver.markMaster();

			CleanupFile cleanupFile = this.master;
			this.master = this.slaver;
			this.slaver = cleanupFile;
		} finally {
			this.lock.unlock();
		}
	}

	private void cleanup(String resourceId, List<Xid> xidList) throws RuntimeException {
		XAResourceDeserializer resourceDeserializer = this.beanFactory.getResourceDeserializer();
		if (StringUtils.isBlank(resourceId)) {
			throw new IllegalStateException();
		}

		Xid[] xidArray = new Xid[xidList.size()];
		xidList.toArray(xidArray);
		LocalXAResourceDescriptor descriptor = //
				(LocalXAResourceDescriptor) resourceDeserializer.deserialize(resourceId);
		RecoveredResource resource = (RecoveredResource) descriptor.getDelegate();
		try {
			resource.forget(xidArray);
		} catch (XAException xaex) {
			logger.error("Error occurred while forgetting resource: {}.", resourceId, xaex);

			switch (xaex.errorCode) {
			case XAException.XAER_NOTA:
				break;
			case XAException.XAER_RMERR:
			case XAException.XAER_RMFAIL:
				throw new IllegalStateException();
			}
		}

	}

	public void forget(Xid xid, String resourceId) throws RuntimeException {
		this.waitForStartup();

		try {
			this.lock.lock();
			this.master.forget(xid, resourceId);
		} finally {
			this.lock.unlock();
		}

	}

	public void markStartupDone() {
		try {
			this.startLock.lock();
			this.started = true;
			this.startCond.signalAll();
		} finally {
			this.startLock.unlock();
		}
	}

	public void waitForStartup() {
		if (this.started == false) {
			try {
				this.startLock.lock();
				while (this.started == false) {
					try {
						this.startCond.await(100, TimeUnit.MILLISECONDS);
					} catch (InterruptedException ex) {
						logger.debug(ex.getMessage());
					}
				} // end-while (this.started == false)
			} finally {
				this.startLock.unlock();
			}
		} // end-if (this.started == false)
	}

	public void release() {
		this.released = true;
	}

	public String getEndpoint() {
		return this.endpoint;
	}

	public void setEndpoint(String identifier) {
		this.endpoint = identifier;
	}

	public CompensableBeanFactory getBeanFactory() {
		return this.beanFactory;
	}

	public void setBeanFactory(CompensableBeanFactory tbf) {
		this.beanFactory = tbf;
	}

	public File getDirectory() {
		return directory;
	}

	public void setDirectory(File directory) {
		this.directory = directory;
	}

}
