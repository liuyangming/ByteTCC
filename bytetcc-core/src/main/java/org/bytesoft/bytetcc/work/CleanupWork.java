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
				this.compressSlaverQuietly();
				this.switchMasterAndSlaver();

				swapMillis = System.currentTimeMillis() + 1000L * 60;
				continue;
			} // end-if (System.currentTimeMillis() > swapMillis)

			long startMillis = System.currentTimeMillis();

			long time = System.currentTimeMillis() / 1000L;
			long mode = time % 30;

			if (mode == 0 || mode == 29) {
				this.waitingFor(1000L);
				continue;
			}

			this.cleanupSlaver(29 - mode); // at least 1s

			long costSeconds = (System.currentTimeMillis() - startMillis) / 1000L;
			long value = (mode + costSeconds) % 30;
			if (value != 0 && value != 29 && (mode + costSeconds) < 30) {
				this.waitingFor(1000L * (29 - value));
			} // end-if (value != 0 && value != 29 && (mode + costSeconds) < 30)

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

	private void compressSlaverQuietly() {
		this.slaver.timingCompress(); // compress
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

			long value = (stopMillis - System.currentTimeMillis()) / 1000L;
			if (value <= 0) {
				break;
			} // end-if (value <= 0)

			this.cleanupByResource(resourceId, records, value);
		}

	}

	private void cleanupByResource(String resourceId, Set<CleanupRecord> records, long seconds) {
		long stopMillis = System.currentTimeMillis() + 1000L * seconds;

		int remain = records.size();
		Iterator<CleanupRecord> recordItr = records.iterator();
		while (System.currentTimeMillis() < stopMillis && recordItr.hasNext()) {
			List<CleanupRecord> recordList = new ArrayList<CleanupRecord>();
			List<Xid> xidList = new ArrayList<Xid>();

			int batchSize = remain > 1000 && remain < 1100 ? remain : 1000; // 1000
			for (int i = 0; i < batchSize && recordItr.hasNext(); i++, remain--) {
				CleanupRecord record = recordItr.next();
				recordList.add(record);
				xidList.add(record.getXid());
			} // end-for (int i = 0; i < batchSize && recordItr.hasNext(); i++, remain--)

			long time = System.currentTimeMillis() / 1000L;
			long mode = time % 60;

			// LocalXAResource.end(Xid,int): mode < 30 ? bytejta_one : bytejta_two;
			boolean flag = mode < 30 ? false : true; // [converse] bytejta_two : bytejta_one
			try {
				this.cleanup(resourceId, xidList, flag);
			} catch (RuntimeException rex) {
				logger.error("forget-transaction: error occurred while forgetting branch: resource= {}, xids= {}", resourceId,
						xidList, rex);
				return; // continue;
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
