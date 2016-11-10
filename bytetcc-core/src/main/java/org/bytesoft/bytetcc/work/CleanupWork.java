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

import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import javax.resource.spi.work.Work;
import javax.transaction.xa.XAException;
import javax.transaction.xa.Xid;

import org.apache.commons.lang3.StringUtils;
import org.bytesoft.bytejta.supports.jdbc.RecoveredResource;
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

	static final String CONSTANTS_RESOURCE_NAME = "resource.log";

	private CompensableBeanFactory beanFactory;
	private Lock lock = new ReentrantLock();
	private Condition condition = this.lock.newCondition();

	private boolean released = false;
	private String endpoint;
	private int sizeOfRaf = -1;
	private int endIndex = CONSTANTS_START_INDEX;
	private File directory;
	private RandomAccessFile raf;
	private FileChannel channel;
	private MappedByteBuffer header;
	private final Map<String, List<Record>> recordMap = new HashMap<String, List<Record>>();

	public void initialize() {
		if (this.directory == null) {
			String address = StringUtils.trimToEmpty(this.endpoint);
			String dirName = address.replaceAll("\\:|\\.", "_");
			this.directory = new File(String.format("bytetcc/%s", dirName));
		}

		if (this.directory.exists() == false) {
			if (this.directory.mkdirs() == false) {
				throw new RuntimeException();
			}
		}

		boolean created = false;
		File resource = new File(this.directory, CONSTANTS_RESOURCE_NAME);
		boolean exists = resource.exists();
		if (exists == false) {
			try {
				created = resource.createNewFile();
			} catch (IOException ex) {
				throw new RuntimeException(ex.getMessage());
			}

			if (created == false) {
				throw new RuntimeException();
			}
		}

		try {
			this.raf = new RandomAccessFile(resource, "rw");
		} catch (FileNotFoundException ex) {
			throw new RuntimeException(ex.getMessage());
		}

		if (created) {
			try {
				this.raf.setLength(CONSTANTS_START_INDEX);
			} catch (IOException ex) {
				throw new RuntimeException(ex.getMessage());
			}
		}

		try {
			this.sizeOfRaf = (int) this.raf.length();
		} catch (IOException ex) {
			throw new RuntimeException(ex.getMessage());
		}

		this.channel = raf.getChannel();
		try {
			this.header = this.channel.map(MapMode.READ_WRITE, 0, CONSTANTS_START_INDEX);
		} catch (IOException ex) {
			throw new RuntimeException(ex.getMessage());
		}

		this.checkIdentifier();
		this.checkVersion();
		this.checkStartIndex();
		this.endIndex = this.checkEndIndex();

		this.compress();
	}

	private void checkIdentifier() {
		byte[] array = new byte[IDENTIFIER.length];
		this.header.position(0);
		this.header.get(array);
		if (Arrays.equals(IDENTIFIER, array)) {
			// ignore
		} else if (Arrays.equals(new byte[IDENTIFIER.length], array)) {
			this.header.position(0);
			this.header.put(IDENTIFIER);
		} else {
			throw new IllegalStateException();
		}
	}

	private void checkVersion() {
		this.header.position(IDENTIFIER.length);
		int major = this.header.get();
		int minor = this.header.get();
		if (major == 0 && minor == 1) {
			// ignore
		} else if (major == 0 && minor == 0) {
			this.header.position(IDENTIFIER.length);
			this.header.put((byte) 0x0);
			this.header.put((byte) 0x1);
		} else {
			throw new IllegalStateException();
		}
	}

	private void checkStartIndex() {
		this.header.position(IDENTIFIER.length + 2);
		int start = this.header.getInt();
		if (start == IDENTIFIER.length + 2 + 8) {
			// ignore
		} else if (start == 0) {
			this.header.position(IDENTIFIER.length + 2);
			this.header.putInt(IDENTIFIER.length + 2 + 8);
		} else {
			throw new IllegalStateException();
		}
	}

	private int checkEndIndex() {
		this.header.position(IDENTIFIER.length + 2 + 4);
		int end = this.header.getInt();
		if (end == 0) {
			this.header.position(IDENTIFIER.length + 2 + 4);
			this.header.putInt(IDENTIFIER.length + 2 + 8);
			return IDENTIFIER.length + 2 + 8;
		} else if (end < CONSTANTS_START_INDEX) {
			throw new IllegalStateException();
		} else {
			return end;
		}
	}

	public void destroy() {
		this.closeQuietly(this.channel);
		this.closeQuietly(this.raf);
	}

	public void closeQuietly(Closeable closeable) {
		if (closeable != null) {
			try {
				closeable.close();
			} catch (IOException ex) {
				logger.debug(ex.getMessage());
			}
		}
	}

	public void forget(Xid xid, String resourceId) throws RuntimeException {
		byte[] globalTransactionId = xid.getGlobalTransactionId();
		byte[] branchQualifier = xid.getBranchQualifier();
		byte[] keyByteArray = resourceId.getBytes();

		byte sizeOfkeyByteArray = (byte) keyByteArray.length;
		if (sizeOfkeyByteArray > CONSTANTS_RES_ID_MAX_SIZE) {
			throw new IllegalStateException("The resource name is too long!");
		}

		byte[] resourceByteArray = new byte[CONSTANTS_RES_ID_MAX_SIZE];
		System.arraycopy(keyByteArray, 0, resourceByteArray, 0, keyByteArray.length);

		ByteBuffer buffer = ByteBuffer.allocate(1 + CONSTANTS_RECORD_SIZE);
		buffer.put((byte) 0x1);

		buffer.put(globalTransactionId);
		buffer.put(branchQualifier);
		buffer.put(resourceByteArray);

		buffer.flip();

		this.invokeForget(xid, resourceId, buffer);
	}

	private void invokeForget(Xid xid, String resource, ByteBuffer buffer) throws IllegalStateException {
		try {
			this.lock.lock();

			int position = buffer.capacity() + this.endIndex;
			if (position > this.sizeOfRaf) {
				try {
					this.raf.setLength(position);
					this.sizeOfRaf = position;
				} catch (IOException ex) {
					throw new IllegalStateException(ex.getMessage());
				}
			}

			try {
				this.channel.position(this.endIndex);
				buffer.flip();
				this.channel.write(buffer);
			} catch (Exception ex) {
				throw new IllegalStateException(ex.getMessage());
			}

			int current = this.endIndex;

			this.endIndex = position;
			this.header.position(IDENTIFIER.length + 2 + 4);
			this.header.putInt(position);

			Record record = new Record();
			record.resource = resource;
			record.xid = xid;
			record.startIndex = current;
			// this.recordList.add(record);

			List<Record> recordList = this.recordMap.get(record.resource);
			if (recordList == null) {
				recordList = new ArrayList<Record>();
				this.recordMap.put(record.resource, recordList);
			}
			recordList.add(record);

			this.condition.signalAll();
		} finally {
			this.lock.unlock();
		}
	}

	private Selection select() {
		Selection selection = new Selection();
		Iterator<Map.Entry<String, List<Record>>> itr = this.recordMap.entrySet().iterator();
		while (selection.records.isEmpty() && itr.hasNext()) {
			Map.Entry<String, List<Record>> entry = itr.next();
			List<Record> recordList = entry.getValue();
			if (recordList != null && recordList.isEmpty() == false) {
				selection.resource = entry.getKey();
				if (recordList.size() > MAX_HANDLE_RECORDS) {
					int index = 0;
					for (Iterator<Record> recordItr = recordList.iterator(); index < MAX_HANDLE_RECORDS
							&& recordItr.hasNext(); index++) {
						Record record = recordItr.next();
						selection.records.add(record);
						recordItr.remove(); // remove
					}
				} else {
					selection.records.addAll(recordList);
					recordList.clear(); // clear
				}
				break;
			}
		}
		return selection;
	}

	public void run() {
		while (this.released == false) {
			Selection selection = null;
			try {
				this.lock.lock();
				for (long times = 0, start = System.currentTimeMillis(); times < 30
						&& (System.currentTimeMillis() - start) < 30000;) {
					long waitingMillis = 100;
					long begin = System.currentTimeMillis();
					try {
						this.condition.await(waitingMillis, TimeUnit.MILLISECONDS);
					} catch (InterruptedException ex) {
						logger.debug(ex.getMessage());
					}
					times = ((System.currentTimeMillis() - begin) < (waitingMillis - 1)) ? times + 1 : times;
				}
				selection = this.select();
			} finally {
				this.lock.unlock();
			}

			List<Xid> selectedXidList = new ArrayList<Xid>();
			for (int i = 0; selection != null && i < selection.records.size(); i++) {
				Record record = selection.records.get(i);
				selectedXidList.add(record.xid);
			}

			try {
				this.cleanup(selection.resource, selectedXidList);
			} catch (RuntimeException rex) {
				logger.error("forget-transaction: error occurred while forgetting branch: resource= {}, xids= {}",
						selection.resource, selectedXidList, rex);

				try {
					this.lock.lock();
					List<Record> recordList = this.recordMap.get(selection.resource);
					recordList.addAll(selection.records);
				} finally {
					this.lock.unlock();
				}

				continue;
			}

			for (int i = 0; selection != null && i < selection.records.size(); i++) {
				Record record = selection.records.get(i);
				try {
					this.lock.lock();
					this.channel.position(record.startIndex);
					ByteBuffer buffer = ByteBuffer.allocate(1);
					buffer.put((byte) 0x0);
					this.channel.write(buffer);
				} catch (Exception ex) {
					List<Record> recordList = this.recordMap.get(selection.resource);
					recordList.add(record);
				} finally {
					this.lock.unlock();
				}
			}

			this.compress();
		} // end-while (this.currentActive())

		this.destroy();
	}

	private void compress() {
		boolean locked = false;
		try {
			int position = this.invokeCompress();

			this.lock.lock();
			locked = true;
			this.endIndex = position;

		} catch (RuntimeException rex) {
			logger.error("Error occurred while compressing file {}." //
					, new File(this.directory, CONSTANTS_RESOURCE_NAME), rex);
		} finally {
			if (locked) {
				this.lock.unlock();
			}
		}
	}

	private int invokeCompress() throws RuntimeException {
		ByteBuffer current = ByteBuffer.allocate(CONSTANTS_RECORD_SIZE + 1);
		ByteBuffer previou = ByteBuffer.allocate(CONSTANTS_RECORD_SIZE + 1);
		int position = CONSTANTS_START_INDEX;
		for (int index = CONSTANTS_START_INDEX; index < this.endIndex; index += CONSTANTS_RECORD_SIZE + 1) {
			try {
				this.lock.lock();

				this.channel.position(index);
				this.channel.read(current);
				current.flip();
				boolean enabled = 0x1 == current.get();
				if (enabled) {
					if (index != position) {
						if (previou.equals(current) == false) {
							previou.put((byte) 0x1);
							previou.put(current);

							previou.flip();
							current.flip();

							this.channel.position(position);
							this.channel.write(current);

							previou.flip();
							current.clear();
						}

						this.channel.position(index);
						ByteBuffer buffer = ByteBuffer.allocate(1);
						buffer.put((byte) 0x0);
						this.channel.write(buffer);
					}
					position = index + CONSTANTS_RECORD_SIZE + 1;
				}
			} catch (IOException ex) {
				throw new RuntimeException(ex);
			} finally {
				this.lock.unlock();

				previou.flip();
				current.clear();
			}
		}

		return position;
	}

	private void cleanup(String resourceId, List<Xid> xidList) throws RuntimeException {
		XAResourceDeserializer resourceDeserializer = this.beanFactory.getResourceDeserializer();
		if (StringUtils.isNotBlank(resourceId)) {
			Xid[] xidArray = new Xid[xidList.size()];
			xidList.toArray(xidArray);
			RecoveredResource resource = (RecoveredResource) resourceDeserializer.deserialize(resourceId);
			try {
				resource.forget(xidArray);
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

	}

	// private void waitForMillis(long millis) {
	// try {
	// Thread.sleep(millis);
	// } catch (Exception ignore) {
	// logger.debug(ignore.getMessage(), ignore);
	// }
	// }

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

	private static class Record {
		public int startIndex;
		public Xid xid;
		public String resource;
	}

	private static class Selection {
		public String resource;
		public List<Record> records = new ArrayList<Record>();
	}

}
