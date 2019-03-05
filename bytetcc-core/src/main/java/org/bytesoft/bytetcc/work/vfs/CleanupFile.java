/**
 * Copyright 2014-2017 yangming.liu<bytefox@126.com>.
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
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.transaction.xa.Xid;

import org.apache.commons.lang3.StringUtils;
import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.aware.CompensableBeanFactoryAware;
import org.bytesoft.compensable.aware.CompensableEndpointAware;
import org.bytesoft.transaction.xa.TransactionXid;
import org.bytesoft.transaction.xa.XidFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CleanupFile implements CompensableEndpointAware, CompensableBeanFactoryAware {
	static final Logger logger = LoggerFactory.getLogger(CleanupFile.class);
	static final byte[] IDENTIFIER = "org.bytesoft.bytetcc.resource.cleanup".getBytes();

	static final int CONSTANTS_REMAIN_SIZE = 16;
	static final int CONSTANTS_START_INDEX = IDENTIFIER.length + 2 + 1 + 4 + 4 + CONSTANTS_REMAIN_SIZE;
	static final int CONSTANTS_RES_ID_MAX_SIZE = 31;
	static final int CONSTANTS_RECORD_SIZE = CONSTANTS_RES_ID_MAX_SIZE + XidFactory.GLOBAL_TRANSACTION_LENGTH
			+ XidFactory.BRANCH_QUALIFIER_LENGTH;

	private final String resourceName;

	private CompensableBeanFactory beanFactory;
	private String endpoint;
	private int sizeOfRaf = -1;
	private int endIndex = CONSTANTS_START_INDEX;
	private File directory;
	private RandomAccessFile raf;
	private FileChannel channel;
	private MappedByteBuffer header;

	private final List<CleanupRecord> recordList = new ArrayList<CleanupRecord>();
	private final Map<String, Set<CleanupRecord>> recordMap = new HashMap<String, Set<CleanupRecord>>();

	public CleanupFile(String resourceName) {
		this.resourceName = resourceName;
	}

	public byte initialize(boolean master) {
		try {
			this.initializeDirectoryIfNecessary();
			boolean created = this.initializeFileIfNecessary();

			ByteBuffer byteBuffer = ByteBuffer.allocate(CONSTANTS_START_INDEX);
			this.channel = raf.getChannel();
			this.channel.read(byteBuffer);

			this.checkIdentifier(byteBuffer);
			this.checkVersion(byteBuffer);
			byte masterFlag = this.checkMasterFlag(byteBuffer, created ? master : null);
			this.checkStartIndex(byteBuffer);
			this.endIndex = this.checkEndIndex(byteBuffer);

			this.sizeOfRaf = this.endIndex; // this.sizeOfRaf = (int) this.raf.length();
			this.raf.setLength(this.sizeOfRaf);
			this.header = this.channel.map(MapMode.READ_WRITE, 0, CONSTANTS_START_INDEX);
			this.header.put((ByteBuffer) byteBuffer.rewind());

			return masterFlag;
		} catch (FileNotFoundException ex) {
			throw new RuntimeException(String.format("File not found: %s.", new File(this.directory, this.resourceName)), ex);
		} catch (IOException ex) {
			throw new RuntimeException(ex);
		}
	}

	private void initializeDirectoryIfNecessary() {
		if (this.directory == null) {
			String address = StringUtils.trimToEmpty(this.endpoint);
			String dirName = address.replaceAll("[^a-zA-Z0-9]", "_");
			this.directory = new File(String.format("bytetcc/%s", dirName));
		}

		if (this.directory.exists() == false) {
			if (this.directory.mkdirs() == false) {
				throw new RuntimeException();
			}
		}
	}

	private boolean initializeFileIfNecessary() throws FileNotFoundException {
		boolean created = false;
		File resource = new File(this.directory, this.resourceName);
		boolean existed = resource.exists();
		if (existed == false) {
			try {
				created = resource.createNewFile();
			} catch (IOException ex) {
				throw new RuntimeException(String.format("Error occurred while creating file: %s.", resource));
			}

			if (created == false) {
				throw new RuntimeException();
			}
		}
		this.raf = new RandomAccessFile(resource, "rw");
		return created;
	}

	private void checkIdentifier(ByteBuffer byteBuf) {
		byte[] array = new byte[IDENTIFIER.length];
		byteBuf.position(0);
		byteBuf.get(array);
		if (Arrays.equals(IDENTIFIER, array)) {
			// ignore
		} else if (Arrays.equals(new byte[IDENTIFIER.length], array)) {
			byteBuf.position(0);
			byteBuf.put(IDENTIFIER);
		} else {
			throw new IllegalStateException();
		}
	}

	private void checkVersion(ByteBuffer byteBuf) {
		byteBuf.position(IDENTIFIER.length);
		int major = byteBuf.get();
		int minor = byteBuf.get();
		if (major == 1 && minor == 0) {
			// ignore
		} else if (major == 0 && minor == 0) {
			byteBuf.position(IDENTIFIER.length);
			byteBuf.put((byte) 0x1);
			byteBuf.put((byte) 0x0);
		} else {
			throw new IllegalStateException(String.format("Incompatible version: %s.%s", major, minor));
		}
	}

	private byte checkMasterFlag(ByteBuffer byteBuf, Boolean master) {
		byteBuf.position(IDENTIFIER.length + 2);
		if (master == null) {
			return byteBuf.get();
		} else if (master) {
			byteBuf.put((byte) 0x1);
			return (byte) 0x1;
		} else {
			byteBuf.put((byte) 0x0);
			return (byte) 0x0;
		}
	}

	public void markMaster() {
		this.header.position(IDENTIFIER.length + 2);
		this.header.put((byte) 0x1);
	}

	public void markPrepare() {
		this.header.position(IDENTIFIER.length + 2);
		this.header.put((byte) 0x2);
	}

	public void markSlaver() {
		this.header.position(IDENTIFIER.length + 2);
		this.header.put((byte) 0x0);
	}

	private void checkStartIndex(ByteBuffer byteBuf) {
		byteBuf.position(IDENTIFIER.length + 2 + 1);
		int start = byteBuf.getInt();
		if (start == CONSTANTS_START_INDEX) {
			// ignore
		} else if (start == 0) {
			byteBuf.position(IDENTIFIER.length + 2 + 1);
			byteBuf.putInt(CONSTANTS_START_INDEX);
		} else {
			throw new IllegalStateException(String.format("Illegal startIndex: %s", start));
		}
	}

	private int checkEndIndex(ByteBuffer byteBuf) {
		byteBuf.position(IDENTIFIER.length + 2 + 1 + 4);
		int end = byteBuf.getInt();
		if (end == 0) {
			byteBuf.position(IDENTIFIER.length + 2 + 1 + 4);
			byteBuf.putInt(CONSTANTS_START_INDEX);
			return CONSTANTS_START_INDEX;
		} else if (end < CONSTANTS_START_INDEX) {
			throw new IllegalStateException(String.format("Illegal endIndex: %s", end));
		} else {
			return end;
		}
	}

	public void startupRecover() throws RuntimeException {
		XidFactory xidFactory = this.beanFactory.getTransactionXidFactory();

		for (int current = CONSTANTS_START_INDEX; current < this.endIndex; current = current + CONSTANTS_RECORD_SIZE + 1) {
			ByteBuffer buffer = ByteBuffer.allocate(1 + CONSTANTS_RECORD_SIZE);

			try {
				this.channel.position(current);
				this.channel.read(buffer);
				buffer.flip();
			} catch (Exception ex) {
				logger.error("Error occurred while accessing file {}!", this.resourceName, ex);
				throw new IllegalStateException();
			}

			byte[] resourceByteArray = new byte[CONSTANTS_RES_ID_MAX_SIZE];
			byte[] globalByteArray = new byte[XidFactory.GLOBAL_TRANSACTION_LENGTH];
			byte[] branchByteArray = new byte[XidFactory.BRANCH_QUALIFIER_LENGTH];

			byte recordFlag = buffer.get();
			buffer.get(globalByteArray);
			buffer.get(branchByteArray);
			buffer.get(resourceByteArray);

			TransactionXid globalXid = xidFactory.createGlobalXid(globalByteArray);
			TransactionXid branchXid = xidFactory.createBranchXid(globalXid, branchByteArray);
			String resourceId = StringUtils.trimToNull(new String(resourceByteArray));

			CleanupRecord record = new CleanupRecord();
			record.setStartIndex(current);
			record.setXid(branchXid);
			record.setRecordFlag(recordFlag);
			record.setEnabled((recordFlag & 0x1) == 0x1);
			record.setResource(resourceId);

			this.recordList.add(record);
			if (record.isEnabled()) {
				this.registerRecord(record);
			} // end-if (record.isEnabled())
		}
	}

	public void timingCompress() throws RuntimeException {
		LinkedList<CleanupRecord> removedList = new LinkedList<CleanupRecord>();

		CleanupRecord lastEnabledRecord = null;
		for (int current = CONSTANTS_START_INDEX; current < this.endIndex; current = current + CONSTANTS_RECORD_SIZE + 1) {
			int index = (current - CONSTANTS_START_INDEX) / (CONSTANTS_RECORD_SIZE + 1);
			CleanupRecord record = this.recordList.get(index);

			int memoRecordFlag = record.getRecordFlag();
			boolean memoEnabled = record.isEnabled();
			boolean forgetFlag = (memoRecordFlag & 0x2) == 0x2;

			if (memoEnabled && forgetFlag) {
				this.delete(record); // change status & unRegister record
				removedList.add(record);
				continue;
			} else if (memoEnabled == false) {
				removedList.add(record);
				this.unRegisterRecord(record); // unRegister record
				continue;
			}

			CleanupRecord removedRecord = removedList.pollFirst();
			if (removedRecord == null) {
				lastEnabledRecord = record;
				continue;
			}

			removedRecord.setEnabled(record.isEnabled());
			removedRecord.setRecordFlag(record.getRecordFlag());
			removedRecord.setResource(record.getResource());
			// removedRecord.setStartIndex(); // dont set startIndex
			removedRecord.setXid(record.getXid());

			this.forget(removedRecord);
			this.delete(record);

			removedList.add(record);

			lastEnabledRecord = removedRecord;
		} // end-for

		int lastRecordEndIndex = lastEnabledRecord == null ? //
				CONSTANTS_START_INDEX : lastEnabledRecord.getStartIndex() + CONSTANTS_RECORD_SIZE + 1;
		this.updateEndIndex(lastRecordEndIndex);
	}

	public void forget(CleanupRecord record) throws RuntimeException {
		Xid xid = record.getXid();
		String resourceId = record.getResource();

		byte[] globalTransactionId = xid.getGlobalTransactionId();
		byte[] branchQualifier = xid.getBranchQualifier();
		byte[] keyByteArray = resourceId.getBytes();

		byte[] resourceByteArray = new byte[CONSTANTS_RES_ID_MAX_SIZE];
		System.arraycopy(keyByteArray, 0, resourceByteArray, 0, keyByteArray.length);

		ByteBuffer buffer = ByteBuffer.allocate(1 + CONSTANTS_RECORD_SIZE);
		buffer.put((byte) record.getRecordFlag());

		buffer.put(globalTransactionId);
		buffer.put(branchQualifier);
		buffer.put(resourceByteArray);

		buffer.flip();

		try {
			this.channel.position(record.getStartIndex());
			buffer.rewind();
			this.channel.write(buffer);
			buffer.rewind();
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}

		byte recordFlag = buffer.get();
		buffer.rewind();

		this.registerRecord(buffer, recordFlag, record.getStartIndex());
	}

	public void forget(Xid xid, String resourceId) throws RuntimeException {
		byte[] globalTransactionId = xid.getGlobalTransactionId();
		byte[] branchQualifier = xid.getBranchQualifier();
		byte[] keyByteArray = resourceId.getBytes();

		if (keyByteArray.length > CONSTANTS_RES_ID_MAX_SIZE) {
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
		this.increaseCapacityIfNecessary();

		int recordIndex = this.endIndex;
		try {
			this.channel.position(recordIndex);
			buffer.rewind();
			this.channel.write(buffer);
			buffer.rewind();
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}

		byte recordFlag = buffer.get();
		buffer.rewind();

		this.registerRecord(buffer, recordFlag, recordIndex);

		this.updateEndIndex(buffer.capacity() + this.endIndex); // update endIndex
	}

	private void registerRecord(ByteBuffer buffer, int recordFlag, int position) throws RuntimeException {
		XidFactory xidFactory = this.beanFactory.getTransactionXidFactory();

		byte[] resourceByteArray = new byte[CONSTANTS_RES_ID_MAX_SIZE];
		byte[] globalByteArray = new byte[XidFactory.GLOBAL_TRANSACTION_LENGTH];
		byte[] branchByteArray = new byte[XidFactory.BRANCH_QUALIFIER_LENGTH];

		buffer.rewind();
		buffer.get();
		buffer.get(globalByteArray);
		buffer.get(branchByteArray);
		buffer.get(resourceByteArray);

		TransactionXid globalXid = xidFactory.createGlobalXid(globalByteArray);
		TransactionXid branchXid = xidFactory.createBranchXid(globalXid, branchByteArray);
		String resourceId = StringUtils.trimToNull(new String(resourceByteArray));

		int index = (position - CONSTANTS_START_INDEX) / (CONSTANTS_RECORD_SIZE + 1);
		if (index < this.recordList.size()) {
			CleanupRecord record = this.recordList.get(index);
			// record.setStartIndex(position); // dont
			record.setRecordFlag(recordFlag);
			record.setEnabled((recordFlag & 0x1) == 0x1);
			record.setXid(branchXid);
			record.setResource(resourceId);

			this.registerRecord(record);
		} else if (index == this.recordList.size()) {
			CleanupRecord record = new CleanupRecord();
			record.setStartIndex(position);
			record.setRecordFlag(recordFlag);
			record.setEnabled((recordFlag & 0x1) == 0x1);
			record.setXid(branchXid);
			record.setResource(resourceId);

			this.recordList.add(record);
			this.registerRecord(record);
		} else {
			throw new IllegalStateException(String.format("Incorrect position: pos= %s, index= %s, size= %s.", position, index,
					this.recordList.size()));
		}

	}

	private void registerRecord(CleanupRecord record) throws RuntimeException {
		String resourceId = record.getResource();

		Set<CleanupRecord> records = this.recordMap.get(resourceId);
		if (records == null) {
			records = new HashSet<CleanupRecord>();
			this.recordMap.put(resourceId, records);
		}

		records.add(record);
	}

	private void unRegisterRecord(CleanupRecord record) throws RuntimeException {
		String resourceId = record.getResource();

		Set<CleanupRecord> records = this.recordMap.get(resourceId);
		if (records != null && records.isEmpty() == false) {
			records.remove(record);
		} // end-if (records != null && records.isEmpty() == false)

	}

	private void updateEndIndex(int position) {
		this.endIndex = position;
		this.header.position(IDENTIFIER.length + 2 + 1 + 4);
		this.header.putInt(position);

		int recordUnit = CONSTANTS_RECORD_SIZE + 1;
		int textLength = this.endIndex - CONSTANTS_START_INDEX;
		int startIndex = textLength / recordUnit;

		for (int index = this.recordList.size() - 1; index >= startIndex; index--) {
			CleanupRecord record = this.recordList.remove(index);
			if (record == null) {
				continue;
			} // end-if (record == null)

			this.unRegisterRecord(record);

		} // end-for (int index = this.recordList.size() - 1; index >= startIndex; index--)

	}

	private void increaseCapacityIfNecessary() {
		if (this.endIndex == this.sizeOfRaf) {
			int incremental = (CONSTANTS_RECORD_SIZE + 1) * 1024 * 4;
			try {
				this.raf.setLength(this.sizeOfRaf + incremental);
				this.sizeOfRaf = this.sizeOfRaf + incremental;
			} catch (IOException ex) {
				throw new IllegalStateException(ex);
			}
		}
	}

	public void delete(CleanupRecord record) throws RuntimeException {
		record.setEnabled(false);
		record.setRecordFlag(0x0);

		try {
			ByteBuffer buffer = ByteBuffer.allocate(1);
			int startIndex = record.getStartIndex();

			this.channel.position(startIndex);
			buffer.put((byte) 0x0);
			buffer.flip();
			this.channel.write(buffer);
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}

		this.unRegisterRecord(record); // unRegister record
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
				logger.debug(ex.getMessage(), ex);
			}
		}
	}

	public Map<String, Set<CleanupRecord>> getRecordMap() {
		return recordMap;
	}

	public CompensableBeanFactory getBeanFactory() {
		return this.beanFactory;
	}

	public void setBeanFactory(CompensableBeanFactory tbf) {
		this.beanFactory = tbf;
	}

	public String getEndpoint() {
		return this.endpoint;
	}

	public void setEndpoint(String identifier) {
		this.endpoint = identifier;
	}

	public File getDirectory() {
		return directory;
	}

	public void setDirectory(File directory) {
		this.directory = directory;
	}

}
