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
package org.bytesoft.bytetcc.work;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
	static final Logger logger = LoggerFactory.getLogger(CompensableWork.class);
	static final byte[] IDENTIFIER = "org.bytesoft.bytetcc.resource.cleanup".getBytes();

	static final long SECOND_MILLIS = 1000L;
	static final int MAX_HANDLE_RECORDS = 200;

	static final int CONSTANTS_START_INDEX = IDENTIFIER.length + 2 + 4 + 4;
	static final int CONSTANTS_RES_ID_MAX_SIZE = 23;
	static final int CONSTANTS_RECORD_SIZE = CONSTANTS_RES_ID_MAX_SIZE + XidFactory.GLOBAL_TRANSACTION_LENGTH
			+ XidFactory.BRANCH_QUALIFIER_LENGTH;

	private CompensableBeanFactory beanFactory;
	private Lock lock = new ReentrantLock();

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

		try {
			this.startupRecover();
		} catch (Exception ex) {
			logger.error("Error occurred while starting cleanup task!", ex);
		}

		long swapMillis = System.currentTimeMillis() + 1000L * 60;

		while (this.released == false) {
			if (System.currentTimeMillis() > swapMillis) {
				this.switchMaster();

				swapMillis = System.currentTimeMillis() + 1000L * 60;
				continue;
			} // end-if (System.currentTimeMillis() > swapMillis)

			long startMillis = System.currentTimeMillis();

			long time = System.currentTimeMillis() / 1000L;
			long mode = time % 30;

			if (mode < 5 || mode > 24) {
				this.compressSlaver(10);
			} else if (mode < 15) {
				this.cleanupSlaver(10);
			} else {
				this.clearSlaverQuietly();
			}

			long costMillis = System.currentTimeMillis() - startMillis;
			if (costMillis < (1000L * 10)) {
				this.waitingFor(1000L * 10 - costMillis);
			} // end-if (costMillis < (1000L * 10))

		}

		this.destroy();
	}

	private void waitingFor(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException ex) {
			logger.error(ex.getMessage());
		}
	}

	private void compressSlaver(long seconds) {
		this.slaver.timingCompress(); // compress
	}

	private void clearSlaverQuietly() {
		this.slaver.timingClear(10); // clear
	}

	private void cleanupSlaver(long seconds) {
		long stopMillis = System.currentTimeMillis() + 1000L * seconds;

		Map<String, Set<CleanupRecord>> recordMap = this.slaver.getRecordMap();
		Set<Map.Entry<String, Set<CleanupRecord>>> entrySet = recordMap.entrySet();
		Iterator<Map.Entry<String, Set<CleanupRecord>>> itr = entrySet.iterator();

		while (System.currentTimeMillis() < stopMillis && itr.hasNext()) {
			Map.Entry<String, Set<CleanupRecord>> entry = itr.next();
			String resourceId = entry.getKey();
			Set<CleanupRecord> records = entry.getValue();

			List<CleanupRecord> recordList = new ArrayList<CleanupRecord>();
			List<Xid> xidList = new ArrayList<Xid>();

			int startIndex = 0;

			Iterator<CleanupRecord> recordItr = records.iterator();
			while (System.currentTimeMillis() < stopMillis && recordItr.hasNext()) {

				for (int batch = 0, index = 0; batch < 1000 && recordItr.hasNext(); index++) {

					if (index < startIndex) {
						continue;
					} // end-if (index < startIndex)

					CleanupRecord record = recordItr.next();
					recordList.add(record);
					xidList.add(record.getXid());

					batch++;
					startIndex++;
				}

				long time = System.currentTimeMillis() / 1000L;
				long mode = time % 60;

				// LocalXAResource.end(Xid,int):
				// String finalSql = mode < 30 ? sqlOne : sqlTwo;
				boolean flag = mode < 30 ? true : false;

				try {
					this.cleanup(resourceId, xidList, flag);
				} catch (RuntimeException rex) {
					logger.error("forget-transaction: error occurred while forgetting branch: resource= {}, xids= {}",
							resourceId, xidList, rex);
					continue;
				}

				for (int i = 0; i < recordList.size(); i++) {
					CleanupRecord record = recordList.get(i);
					int recordFlag = record.getRecordFlag();
					if (flag) {
						record.setRecordFlag(recordFlag | 0x2);
					} else {
						record.setRecordFlag(recordFlag | 0x4);
					}
				}

			} // end-while (System.currentTimeMillis() < stopMillis && recordItr.hasNext())

		}

	}

	public void switchMaster() {
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

	private void cleanup(String resourceId, List<Xid> xidList, boolean flag) throws RuntimeException {
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
			resource.forget(xidArray, flag);
		} catch (XAException xaex) {
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
		try {
			this.lock.lock();
			this.master.forget(xid, resourceId);
		} finally {
			this.lock.unlock();
		}
	}

	public void release() {
		this.released = true;
	}

	public void setEndpoint(String identifier) {
		this.endpoint = identifier;
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
