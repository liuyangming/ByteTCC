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
import java.util.HashSet;
import java.util.Iterator;
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

public class CleanupFile implements CompensableEndpointAware, CompensableBeanFactoryAware, CleanupCallback {
	static final Logger logger = LoggerFactory.getLogger(CleanupFile.class);
	static final byte[] IDENTIFIER = "org.bytesoft.bytetcc.resource.cleanup".getBytes();

	static final int CONSTANTS_START_INDEX = IDENTIFIER.length + 2 + 1 + 4 + 4;
	static final int CONSTANTS_RES_ID_MAX_SIZE = 23;
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

	private final Map<String, Set<CleanupRecord>> recordMap = new HashMap<String, Set<CleanupRecord>>();

	public CleanupFile(String resourceName) {
		this.resourceName = resourceName;
	}

	public byte initialize(boolean master) {
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
		File resource = new File(this.directory, this.resourceName);
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
		byte masterFlag = this.checkMasterFlag(created ? master : null);
		this.checkStartIndex();
		this.endIndex = this.checkEndIndex();

		this.traversal(); // initialize

		if (this.lastEnabledRecord != null) {
			this.updateEndIndex(this.lastEnabledRecord.getStartIndex() + CONSTANTS_RECORD_SIZE + 1);
		}

		return masterFlag;
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
		if (major == 0 && minor == 2) {
			// ignore
		} else if (major == 0 && minor == 0) {
			this.header.position(IDENTIFIER.length);
			this.header.put((byte) 0x0);
			this.header.put((byte) 0x2);
		} else {
			throw new IllegalStateException();
		}
	}

	private byte checkMasterFlag(Boolean master) {
		this.header.position(IDENTIFIER.length + 2);
		if (master == null) {
			// ignore
		} else if (master) {
			this.header.put((byte) 0x1);
		} else {
			this.header.put((byte) 0x0);
		}
		return this.header.get();
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

	private void checkStartIndex() {
		this.header.position(IDENTIFIER.length + 2 + 1);
		int start = this.header.getInt();
		if (start == IDENTIFIER.length + 2 + 1 + 8) {
			// ignore
		} else if (start == 0) {
			this.header.position(IDENTIFIER.length + 2 + 1);
			this.header.putInt(IDENTIFIER.length + 2 + 1 + 8);
		} else {
			throw new IllegalStateException();
		}
	}

	private int checkEndIndex() {
		this.header.position(IDENTIFIER.length + 2 + 1 + 4);
		int end = this.header.getInt();
		if (end == 0) {
			this.header.position(IDENTIFIER.length + 2 + 1 + 4);
			this.header.putInt(IDENTIFIER.length + 2 + 1 + 8);
			return IDENTIFIER.length + 2 + 1 + 8;
		} else if (end < CONSTANTS_START_INDEX) {
			throw new IllegalStateException();
		} else {
			return end;
		}
	}

	// public void traversal(CleanupCallback callback) throws RuntimeException {
	// Set<Entry<String, Set<CleanupRecord>>> entrySet = this.recordMap.entrySet();
	// Iterator<Map.Entry<String, Set<CleanupRecord>>> itr = entrySet.iterator();
	//
	// boolean interrupted = false;
	// while (interrupted == false && itr.hasNext()) {
	// Map.Entry<String, Set<CleanupRecord>> entry = itr.next();
	// Set<CleanupRecord> records = entry.getValue();
	// if (records == null || records.isEmpty()) {
	// continue;
	// } // end-if (records == null || records.isEmpty())
	//
	// Iterator<CleanupRecord> recordItr = records.iterator();
	// while (interrupted == false && recordItr.hasNext()) {
	// CleanupRecord record = recordItr.next();
	// try {
	// callback.callback(record);
	// } catch (Exception ex) {
	// interrupted = true;
	// }
	// } // end-while (recordItr.hasNext())
	//
	// } // end-while (itr.hasNext())
	// }

	private void traversal() throws RuntimeException {
		XidFactory xidFactory = this.beanFactory.getTransactionXidFactory();

		for (int current = CONSTANTS_START_INDEX; current < this.endIndex; current = current + CONSTANTS_RECORD_SIZE + 1) {
			ByteBuffer buffer = ByteBuffer.allocate(1 + CONSTANTS_RECORD_SIZE);

			try {
				this.channel.position(this.endIndex);
				this.channel.read(buffer);
			} catch (Exception ex) {
				throw new IllegalStateException();
			}

			byte[] resourceByteArray = new byte[CONSTANTS_RES_ID_MAX_SIZE];
			byte[] globalByteArray = new byte[XidFactory.GLOBAL_TRANSACTION_LENGTH];
			byte[] branchByteArray = new byte[XidFactory.BRANCH_QUALIFIER_LENGTH];

			buffer.flip();
			byte recordFlag = buffer.get();
			buffer.get(resourceByteArray);
			buffer.get(globalByteArray);
			buffer.get(branchByteArray);

			TransactionXid globalXid = xidFactory.createGlobalXid(globalByteArray);
			TransactionXid branchXid = xidFactory.createBranchXid(globalXid, branchByteArray);

			CleanupRecord record = new CleanupRecord();
			record.setStartIndex(current);
			record.setXid(branchXid);
			record.setRecordFlag(recordFlag);
			record.setEnabled((recordFlag & 0x1) == 0x1);
			record.setResource(StringUtils.trimToNull(new String(resourceByteArray)));

			this.callback(record);
		}
	}

	private final List<CleanupRecord> disabledList = new ArrayList<CleanupRecord>();
	private CleanupRecord lastEnabledRecord;

	public void callback(CleanupRecord record) throws RuntimeException {
		if (record.isEnabled() == false) {
			this.disabledList.add(record);
		} else {
			Iterator<CleanupRecord> itr = this.disabledList.iterator();
			if (itr.hasNext()) {
				CleanupRecord element = itr.next();
				element.setEnabled(record.isEnabled());
				element.setRecordFlag(record.getRecordFlag());
				element.setResource(record.getResource());
				// element.setStartIndex(); // dont set startIndex
				element.setXid(record.getXid());

				try {
					ByteBuffer buffer = ByteBuffer.allocate(1);
					int startIndex = record.getStartIndex();

					this.forget(element, false);

					this.channel.position(startIndex);
					buffer.put((byte) 0x0);
					buffer.flip();
					this.channel.write(buffer);
				} catch (IllegalStateException ex) {
					throw ex;
				} catch (Exception ex) {
					throw new IllegalStateException(ex.getMessage());
				}

				itr.remove();

				record.setRecordFlag(0x0);
				record.setEnabled(false);
				this.disabledList.add(record);

				this.registerRecord(element);

				if (this.lastEnabledRecord != null //
						&& this.lastEnabledRecord.getStartIndex() < element.getStartIndex()) {
					this.lastEnabledRecord = element;
				}
			} else {
				this.registerRecord(record);
				this.lastEnabledRecord = record;
			}

		}
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

	public void forget(CleanupRecord record, boolean migrateFromOtherFile) throws RuntimeException {
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

		if (migrateFromOtherFile) {
			this.invokeForget(xid, resourceId, buffer);
		} else {
			this.invokeForget(xid, resourceId, buffer, record.getStartIndex());
		}

	}

	private void invokeForget(Xid xid, String resource, ByteBuffer buffer) throws IllegalStateException {
		int position = buffer.capacity() + this.endIndex;
		if (position > this.sizeOfRaf) {
			int incremental = (CONSTANTS_RECORD_SIZE + 1) * 1024 * 4;
			try {
				this.raf.setLength(this.sizeOfRaf + incremental);
				this.sizeOfRaf = this.sizeOfRaf + incremental;
			} catch (IOException ex) {
				throw new IllegalStateException(ex.getMessage());
			}
		}

		int recordIndex = this.endIndex;
		try {
			this.channel.position(recordIndex);
			buffer.flip();
			this.channel.write(buffer);
		} catch (Exception ex) {
			throw new IllegalStateException(ex.getMessage());
		}

		buffer.flip();
		byte recordFlag = buffer.get();
		buffer.flip();

		this.registerRecord(buffer, recordFlag, recordIndex);

		this.updateEndIndex(position); // update endIndex
	}

	private void invokeForget(Xid xid, String resource, ByteBuffer buffer, int position) throws IllegalStateException {
		try {
			this.channel.position(position);
			buffer.flip();
			this.channel.write(buffer);
		} catch (Exception ex) {
			throw new IllegalStateException(ex.getMessage());
		}
	}

	private void registerRecord(ByteBuffer buffer, int recordFlag, int position) throws RuntimeException {
		XidFactory xidFactory = this.beanFactory.getTransactionXidFactory();

		byte[] resourceByteArray = new byte[CONSTANTS_RES_ID_MAX_SIZE];
		byte[] globalByteArray = new byte[XidFactory.GLOBAL_TRANSACTION_LENGTH];
		byte[] branchByteArray = new byte[XidFactory.BRANCH_QUALIFIER_LENGTH];

		buffer.flip();
		buffer.get(resourceByteArray);
		buffer.get(globalByteArray);
		buffer.get(branchByteArray);

		TransactionXid globalXid = xidFactory.createGlobalXid(globalByteArray);
		TransactionXid branchXid = xidFactory.createBranchXid(globalXid, branchByteArray);

		String resourceId = StringUtils.trimToNull(new String(resourceByteArray));

		CleanupRecord record = new CleanupRecord();
		record.setStartIndex(position);
		record.setRecordFlag(recordFlag);
		record.setEnabled((recordFlag & 0x1) == 0x1);
		record.setXid(branchXid);
		record.setResource(resourceId);

		this.registerRecord(record);
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

	private void updateEndIndex(int position) {
		this.endIndex = position;
		this.header.position(IDENTIFIER.length + 2 + 1 + 4);
		this.header.putInt(position);
	}

	public void removeIfNecessary(CleanupRecord record) throws RuntimeException {
		int recordFlag = record.getRecordFlag();
		boolean forgetOne = (recordFlag & 0x2) == 0x2;
		boolean forgetTwo = (recordFlag & 0x4) == 0x4;
		if (forgetOne && forgetTwo) {
			this.delete(record);
		} else {
			try {
				ByteBuffer buffer = ByteBuffer.allocate(1);
				int startIndex = record.getStartIndex();

				this.channel.position(startIndex);
				buffer.put((byte) recordFlag);
				buffer.flip();
				this.channel.write(buffer);
			} catch (Exception ex) {
				throw new IllegalStateException(ex.getMessage());
			}
		}
	}

	public void delete(CleanupRecord record) throws RuntimeException {
		String resource = record.getResource();

		try {
			ByteBuffer buffer = ByteBuffer.allocate(1);
			int startIndex = record.getStartIndex();

			this.channel.position(startIndex);
			buffer.put((byte) 0x0);
			buffer.flip();
			this.channel.write(buffer);
		} catch (Exception ex) {
			throw new IllegalStateException(ex.getMessage());
		}

		Set<CleanupRecord> records = this.recordMap.get(resource);
		if (records != null && records.isEmpty() == false) {
			records.remove(record);
		} // end-if (records != null && records.isEmpty() == false)

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

	public Map<String, Set<CleanupRecord>> getRecordMap() {
		return recordMap;
	}

	public void setBeanFactory(CompensableBeanFactory tbf) {
		this.beanFactory = tbf;
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
