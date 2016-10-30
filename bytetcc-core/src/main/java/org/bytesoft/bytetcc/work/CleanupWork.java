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
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import javax.resource.spi.work.Work;
import javax.transaction.xa.XAException;
import javax.transaction.xa.Xid;

import org.apache.commons.lang3.StringUtils;
import org.bytesoft.bytejta.supports.jdbc.RecoveredResource;
import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.archive.CompensableArchive;
import org.bytesoft.compensable.aware.CompensableBeanFactoryAware;
import org.bytesoft.transaction.supports.serialize.XAResourceDeserializer;
import org.bytesoft.transaction.xa.XidFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CleanupWork implements Work, CompensableBeanFactoryAware {
	static final Logger logger = LoggerFactory.getLogger(CompensableWork.class);
	static final byte[] IDENTIFIER = "org.bytesoft.bytetcc.resource.cleanup".getBytes();

	static final int CONSTANTS_START_INDEX = IDENTIFIER.length + 2 + 4 + 4;

	static final long SECOND_MILLIS = 1000L;
	private long stopTimeMillis = -1;
	private long delayOfStoping = SECOND_MILLIS * 5;

	private CompensableBeanFactory beanFactory;
	private Lock lock = new ReentrantLock();
	private Condition condition = this.lock.newCondition();

	private int sizeOfRaf = -1;
	private int endIndex = CONSTANTS_START_INDEX;
	private File directory;
	private RandomAccessFile raf;
	private FileChannel channel;
	private MappedByteBuffer header;
	private final List<Record> recordList = new ArrayList<Record>();

	public void initialize() {
		if (this.directory.exists() == false) {
			if (this.directory.mkdirs() == false) {
				throw new RuntimeException();
			}
		}

		boolean created = false;
		File resource = new File(this.directory, "resource.log");
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
		} else {
			this.header.position(IDENTIFIER.length);
			this.header.put((byte) 0x0);
			this.header.put((byte) 0x1);
		}
	}

	private void checkStartIndex() {
		this.header.position(IDENTIFIER.length + 2);
		int start = this.header.getInt();
		if (start == IDENTIFIER.length + 2 + 8) {
			// ignore
		} else {
			throw new IllegalStateException();
		}
	}

	private int checkEndIndex() {
		this.header.position(IDENTIFIER.length + 2 + 4);
		int end = this.header.getInt();
		if (end < CONSTANTS_START_INDEX) {
			throw new IllegalStateException();
		}
		return end;
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

	public void forget(CompensableArchive archive) throws IllegalStateException {
		try {
			this.lock.lock();

			Xid transactionXid = archive.getTransactionXid();
			byte[] transactionGlobalTransactionId = transactionXid == null ? new byte[XidFactory.GLOBAL_TRANSACTION_LENGTH]
					: transactionXid.getGlobalTransactionId();
			byte[] transactionBranchQualifier = transactionXid == null ? new byte[XidFactory.BRANCH_QUALIFIER_LENGTH]
					: transactionXid.getBranchQualifier();
			String transactionKey = archive.getTransactionResourceKey();
			byte[] transactionByteArray = transactionKey == null ? new byte[0] : transactionKey.getBytes();

			Xid compensableXid = archive.getCompensableXid();
			byte[] compensableGlobalTransactionId = compensableXid == null ? new byte[XidFactory.GLOBAL_TRANSACTION_LENGTH]
					: compensableXid.getGlobalTransactionId();
			byte[] compensableBranchQualifier = compensableXid == null ? new byte[XidFactory.BRANCH_QUALIFIER_LENGTH]
					: compensableXid.getBranchQualifier();
			String compensableKey = archive.getCompensableResourceKey();
			byte[] compensableByteArray = compensableKey == null ? new byte[0] : compensableKey.getBytes();

			byte sizeOfTransactionKey = (byte) transactionByteArray.length;
			byte sizeOfCompensableKey = (byte) compensableByteArray.length;

			ByteBuffer buffer = ByteBuffer.allocate(1 + 2 + sizeOfTransactionKey + sizeOfCompensableKey
					+ XidFactory.GLOBAL_TRANSACTION_LENGTH * 2 + XidFactory.BRANCH_QUALIFIER_LENGTH * 2);
			buffer.put((byte) 0x1);
			buffer.put(transactionGlobalTransactionId);
			buffer.put(transactionBranchQualifier);
			buffer.put(compensableGlobalTransactionId);
			buffer.put(compensableBranchQualifier);

			buffer.put((byte) sizeOfTransactionKey);
			buffer.put(transactionByteArray);

			buffer.put((byte) sizeOfCompensableKey);
			buffer.put(compensableByteArray);

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

			this.header.position(IDENTIFIER.length + 2 + 4);
			this.header.putInt(position);

			Record record = new Record();
			record.startIndex = this.endIndex;
			record.archive = archive;
			this.recordList.add(record);

			this.condition.signalAll();
		} finally {
			this.lock.unlock();
		}
	}

	public void run() {
		while (this.currentActive()) {
			if (this.recordList.isEmpty()) {
				this.waitForMillis(10L);
				continue;
			}

			List<Record> selectedRecordList = new ArrayList<Record>();
			try {
				this.lock.lock();
				Iterator<Record> itr = this.recordList.iterator();
				for (int i = 0; i < 10 && itr.hasNext(); i++) {
					Record record = itr.next();
					selectedRecordList.add(record);
					itr.remove();
				}
			} finally {
				this.lock.unlock();
			}

			for (int i = 0; i < selectedRecordList.size(); i++) {
				Record record = selectedRecordList.get(i);
				int startIndex = record.startIndex;
				CompensableArchive archive = record.archive;
				try {
					this.invokeForget(archive);
				} catch (RuntimeException rex) {
					try {
						this.lock.lock();
						this.recordList.add(record);
					} finally {
						this.lock.unlock();
					}
				}

				try {
					this.lock.lock();

					this.channel.position(startIndex);
					ByteBuffer buffer = ByteBuffer.allocate(1);
					buffer.put((byte) 0x0);
					this.channel.write(buffer);
				} catch (IOException ex) {
					this.recordList.add(record);
				} finally {
					this.lock.unlock();
				}

			}

			this.compress();
		} // end-while (this.currentActive())

		this.destroy();
	}

	private void compress() {
		// TODO
	}

	private void invokeForget(CompensableArchive archive) {
		XAResourceDeserializer resourceDeserializer = this.beanFactory.getResourceDeserializer();
		String transactionKey = archive.getTransactionResourceKey();
		Xid transactionXid = archive.getTransactionXid();
		String compensableKey = archive.getCompensableResourceKey();
		Xid compensableXid = archive.getCompensableXid();
		if (StringUtils.isNotBlank(transactionKey)) {
			RecoveredResource resource = (RecoveredResource) resourceDeserializer.deserialize(transactionKey);
			try {
				resource.forget(transactionXid);
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
		if (StringUtils.isNotBlank(compensableKey)) {
			RecoveredResource resource = (RecoveredResource) resourceDeserializer.deserialize(compensableKey);
			try {
				resource.forget(compensableXid);
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

	private void waitForMillis(long millis) {
		try {
			Thread.sleep(millis);
		} catch (Exception ignore) {
			logger.debug(ignore.getMessage(), ignore);
		}
	}

	public void release() {
		this.stopTimeMillis = System.currentTimeMillis() + this.delayOfStoping;
	}

	protected boolean currentActive() {
		return this.stopTimeMillis <= 0 || System.currentTimeMillis() < this.stopTimeMillis;
	}

	public long getDelayOfStoping() {
		return delayOfStoping;
	}

	public void setDelayOfStoping(long delayOfStoping) {
		this.delayOfStoping = delayOfStoping;
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

	public static class Record {
		public int startIndex;
		public CompensableArchive archive;
	}

}
