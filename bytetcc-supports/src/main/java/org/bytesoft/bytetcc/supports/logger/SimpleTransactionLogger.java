/**
 * Copyright 2014-2015 yangming.liu<liuyangming@gmail.com>.
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
package org.bytesoft.bytetcc.supports.logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;

import org.apache.log4j.Logger;
import org.bytesoft.bytejta.utils.ByteUtils;
import org.bytesoft.bytetcc.CompensableInvocation;
import org.bytesoft.bytetcc.archive.CompensableArchive;
import org.bytesoft.bytetcc.archive.CompensableTransactionArchive;
import org.bytesoft.bytetcc.common.TransactionConfigurator;
import org.bytesoft.bytetcc.supports.CompensableTransactionLogger;
import org.bytesoft.transaction.archive.TransactionArchive;
import org.bytesoft.transaction.archive.XAResourceArchive;
import org.bytesoft.transaction.serialize.TransactionArchiveSerializer;
import org.bytesoft.transaction.serialize.XAResourceSerializer;
import org.bytesoft.transaction.xa.TransactionXid;
import org.bytesoft.transaction.xa.XAResourceDescriptor;
import org.bytesoft.transaction.xa.XidFactory;

import com.caucho.hessian.io.HessianInput;
import com.caucho.hessian.io.HessianOutput;

public class SimpleTransactionLogger implements CompensableTransactionLogger, TransactionArchiveSerializer {
	static final Logger logger = Logger.getLogger(SimpleTransactionLogger.class.getSimpleName());

	private boolean initialized = false;
	private File directory;
	private XAResourceSerializer resourceSerializer;
	private final Map<Xid, SimpleTransactionLoggerEntry> transactions = new ConcurrentHashMap<Xid, SimpleTransactionLoggerEntry>();

	public void initialize() {
		if (this.directory == null) {
			this.directory = new File("bytetcc/simple/");
		}

		if (directory.exists() == false) {
			directory.mkdirs();
		}
	}

	private synchronized void processInitializationIfNecessary() {
		if (this.initialized == false) {
			this.processInitialization();
		}
	}

	private synchronized void processInitialization() {
		File[] files = this.directory.listFiles();
		for (int i = 0; i < files.length; i++) {
			File file = files[i];
			boolean closeRequired = false;
			RandomAccessFile raf = null;
			FileChannel channel = null;
			try {
				raf = new RandomAccessFile(file, "rw");
				channel = raf.getChannel();
				ByteBuffer headerBuf = ByteBuffer.allocate(5);
				channel.read(headerBuf);

				headerBuf.flip();
				byte enabledByte = headerBuf.get();
				int length = headerBuf.getInt();
				if (enabledByte == 1) {
					ByteBuffer buffer = ByteBuffer.allocate(length);
					channel.read(buffer);
					buffer.flip();

					byte[] bytes = new byte[length];
					buffer.get(bytes);

					CompensableTransactionArchive archive = this.deserialize(bytes);
					Xid xid = archive.getXid();
					SimpleTransactionLoggerEntry entry = new SimpleTransactionLoggerEntry();
					entry.setAccessFile(raf);
					entry.setFileChannel(channel);
					entry.setArchive(archive);
					this.transactions.put(xid, entry);
				} else {
					closeRequired = true;
				}
			} catch (IOException ioex) {
				throw new RuntimeException(ioex.getMessage(), ioex);
			} finally {
				if (closeRequired) {
					this.closeIfNecessary(raf);
					if (file.delete() == false) {
						file.deleteOnExit();
					}
				} else {
					try {
						channel.position(0);
					} catch (Exception ex) {
						logger.debug(ex.getMessage());
					}
				}
			}
		}
	}

	public void createTransaction(TransactionArchive transactionArchive) {
		this.updateTransaction(transactionArchive);
	}

	public void deleteTransaction(TransactionArchive transactionArchive) {
		if (CompensableTransactionArchive.class.isInstance(transactionArchive) == false) {
			throw new IllegalArgumentException();
		}
		CompensableTransactionArchive archive = (CompensableTransactionArchive) transactionArchive;

		Xid xid = archive.getXid();
		SimpleTransactionLoggerEntry entry = this.transactions.remove(xid);
		String txid = ByteUtils.byteArrayToString(xid.getGlobalTransactionId());
		File storageFile = new File(this.directory, txid);
		if (entry != null) {
			RandomAccessFile raf = entry.getAccessFile();
			FileChannel channel = entry.getFileChannel();
			try {
				ByteBuffer buffer = ByteBuffer.allocate(1);
				buffer.put((byte) 0);
				buffer.flip();

				channel.position(0);
				channel.write(buffer);
			} catch (IOException ex) {
				logger.error(ex.getMessage(), ex);
			} finally {
				this.closeIfNecessary(raf);
			}
		}

		try {
			if (storageFile.delete() == false) {
				storageFile.deleteOnExit();
			}
		} catch (RuntimeException ex) {
			logger.error(ex.getMessage(), ex);
		}

	}

	public List<TransactionArchive> getTransactionArchiveList() {
		this.processInitializationIfNecessary();

		List<TransactionArchive> archives = new ArrayList<TransactionArchive>();
		Iterator<Map.Entry<Xid, SimpleTransactionLoggerEntry>> itr = this.transactions.entrySet().iterator();
		while (itr.hasNext()) {
			Map.Entry<Xid, SimpleTransactionLoggerEntry> entry = itr.next();
			SimpleTransactionLoggerEntry loggerEntry = entry.getValue();
			archives.add(loggerEntry.getArchive());
		}
		return archives;

	}

	public void updateResource(Xid transactionXid, XAResourceArchive resourceArchive) {
		TransactionArchive archive = this.uploadTransaction(transactionXid);
		List<XAResourceArchive> resources = archive.getRemoteResources();
		XAResourceArchive target = null;
		for (int i = 0; i < resources.size(); i++) {
			XAResourceArchive object = resources.get(i);
			if (object.equals(resourceArchive)) {
				target = object;
				break;
			}
		}
		if (target == null) {
			resources.add(resourceArchive);
		} else {
			target.setVote(resourceArchive.getVote());
			target.setReadonly(resourceArchive.isReadonly());
			target.setCommitted(resourceArchive.isCommitted());
			target.setRolledback(resourceArchive.isRolledback());
			target.setHeuristic(resourceArchive.isHeuristic());
			target.setCompleted(resourceArchive.isCompleted());
		}
		this.updateTransaction(archive);

	}

	public void updateTransaction(TransactionArchive transactionArchive) {
		if (CompensableTransactionArchive.class.isInstance(transactionArchive) == false) {
			throw new IllegalArgumentException();
		}
		CompensableTransactionArchive archive = (CompensableTransactionArchive) transactionArchive;

		byte[] bytes = null;
		try {
			bytes = this.serialize(archive);
		} catch (IOException ex) {
			logger.error(ex.getMessage(), ex);
			return;
		}

		Xid xid = archive.getXid();
		SimpleTransactionLoggerEntry entry = this.transactions.get(xid);
		if (entry == null) {
			String txid = ByteUtils.byteArrayToString(xid.getGlobalTransactionId());
			RandomAccessFile raf = null;
			FileChannel channel = null;
			try {
				File storageFile = new File(this.directory, txid);
				raf = new RandomAccessFile(storageFile, "rw");
				channel = raf.getChannel();
				entry = new SimpleTransactionLoggerEntry();
				entry.setAccessFile(raf);
				entry.setFileChannel(channel);
				entry.setArchive(transactionArchive);
				this.transactions.put(xid, entry);
			} catch (IOException ex) {
				logger.error(ex.getMessage(), ex);
				return;
			}
		}

		ByteBuffer buf = ByteBuffer.allocate(bytes.length + 5);
		buf.put((byte) 1);
		buf.putInt(bytes.length);
		buf.put(bytes);
		buf.flip();
		FileChannel channel = entry.getFileChannel();
		try {
			channel.position(0);
			channel.write(buf);
			channel.force(false);
		} catch (IOException ex) {
			logger.error(ex.getMessage(), ex);
		}
	}

	public CompensableTransactionArchive uploadTransaction(Xid xid) {
		SimpleTransactionLoggerEntry entry = this.transactions.get(xid);
		if (entry == null) {
			return null;
		}
		FileChannel channel = entry.getFileChannel();
		long pos = -1;
		try {
			ByteBuffer headerBuf = ByteBuffer.allocate(5);
			pos = channel.position();
			channel.position(0);
			channel.read(headerBuf);

			headerBuf.flip();
			// byte enabledByte=
			headerBuf.get();
			int length = headerBuf.getInt();

			ByteBuffer entityBuf = ByteBuffer.allocate(length);
			channel.read(entityBuf);
			entityBuf.flip();

			byte[] bytes = new byte[length];
			entityBuf.get(bytes);
			return this.deserialize(bytes);
		} catch (IOException ex) {
			logger.error(ex.getMessage(), ex);
			return null;
		} catch (RuntimeException ex) {
			logger.error(ex.getMessage(), ex);
			return null;
		} finally {
			if (pos != -1) {
				try {
					channel.position(pos);
				} catch (Exception ex) {
					logger.debug(ex.getMessage());
				}
			}
		}

	}

	public CompensableTransactionArchive deserialize(byte[] bytes) throws IOException {
		ByteBuffer buffer = ByteBuffer.allocate(bytes.length);
		buffer.put(bytes);
		buffer.flip();

		CompensableTransactionArchive archive = new CompensableTransactionArchive();
		byte[] transactionId = new byte[XidFactory.GLOBAL_TRANSACTION_LENGTH];
		buffer.get(transactionId);

		XidFactory xidFactory = TransactionConfigurator.getInstance().getXidFactory();
		TransactionXid globalXid = xidFactory.createGlobalXid(transactionId);
		archive.setXid(globalXid);

		byte transactionStatus = buffer.get();
		byte compensableStatus = buffer.get();
		byte coordinator = buffer.get();

		archive.setStatus(transactionStatus);
		archive.setCompensable(true);
		archive.setCoordinator(coordinator != 0);
		archive.setCompensableStatus(compensableStatus);

		byte compensableNumber = buffer.get();
		for (int i = 0; i < compensableNumber; i++) {
			CompensableArchive compensableArchive = null;
			compensableArchive = this.deserializeCompensableArchive(globalXid, buffer);
			archive.getCompensables().add(compensableArchive);
		}

		byte resourceNumber = buffer.get();
		for (int i = 0; i < resourceNumber; i++) {
			XAResourceArchive resourceArchive = null;
			resourceArchive = this.deserializeXAResourceArchive(globalXid, buffer);
			archive.getRemoteResources().add(resourceArchive);
		}

		return archive;
	}

	private CompensableArchive deserializeCompensableArchive(TransactionXid globalXid, ByteBuffer buffer) {
		CompensableArchive archive = new CompensableArchive();
		byte[] transactiondId = new byte[XidFactory.GLOBAL_TRANSACTION_LENGTH];
		byte[] branchQualifier = new byte[XidFactory.BRANCH_QUALIFIER_LENGTH];
		buffer.get(transactiondId);
		buffer.get(branchQualifier);

		byte coordinator = buffer.get();
		byte confirmed = buffer.get();
		byte cancelled = buffer.get();

		archive.setCoordinator(coordinator != 0);
		archive.setConfirmed(confirmed != 0);
		archive.setCancelled(cancelled != 0);

		XidFactory xidFactory = TransactionConfigurator.getInstance().getXidFactory();
		TransactionXid branchXid = xidFactory.createBranchXid(globalXid, branchQualifier);
		archive.setXid(branchXid);

		int length = buffer.getInt();
		byte[] bytes = new byte[length];
		buffer.get(bytes);
		CompensableInvocation compensable = (CompensableInvocation) this.deserializeObject(bytes);
		archive.setCompensable(compensable);

		return archive;

	}

	private XAResourceArchive deserializeXAResourceArchive(TransactionXid globalXid, ByteBuffer buffer) throws IOException {

		int length = buffer.getInt();
		int lengthOfidentifier = length - XidFactory.BRANCH_QUALIFIER_LENGTH - 4;

		byte[] branchQualifier = new byte[XidFactory.BRANCH_QUALIFIER_LENGTH];
		buffer.get(branchQualifier);
		int branchVote = buffer.get();
		int committedValue = buffer.get();
		int rolledbackValue = buffer.get();
		int heuristicValue = buffer.get();
		byte[] identifiers = new byte[lengthOfidentifier];
		buffer.get(identifiers);

		XAResourceArchive resourceArchive = new XAResourceArchive();
		XidFactory xidFactory = TransactionConfigurator.getInstance().getXidFactory();
		TransactionXid branchXid = xidFactory.createBranchXid(globalXid, branchQualifier);
		resourceArchive.setXid(branchXid);
		resourceArchive.setVote(branchVote);
		resourceArchive.setRolledback(rolledbackValue != 0);
		resourceArchive.setCommitted(committedValue != 0);
		resourceArchive.setHeuristic(heuristicValue != 0);
		if (branchVote == XAResource.XA_RDONLY) {
			resourceArchive.setCompleted(true);
		} else if (resourceArchive.isCommitted() || resourceArchive.isRolledback()) {
			resourceArchive.setCompleted(true);
		}

		String identifier = new String(identifiers);
		XAResourceDescriptor descriptor = this.resourceSerializer.deserialize(identifier);
		resourceArchive.setDescriptor(descriptor);

		return resourceArchive;
	}

	public byte[] serialize(TransactionArchive transactionArchive) throws IOException {
		if (CompensableTransactionArchive.class.isInstance(transactionArchive) == false) {
			throw new IllegalArgumentException();
		}
		CompensableTransactionArchive archive = (CompensableTransactionArchive) transactionArchive;

		ByteBuffer buffer = ByteBuffer.allocate(4096);
		Xid xid = archive.getXid();
		byte[] transactionId = xid.getGlobalTransactionId();
		buffer.put(transactionId);
		int status = archive.getStatus();
		buffer.put((byte) status);

		int compensableStatus = archive.getCompensableStatus();
		buffer.put((byte) compensableStatus);

		byte coordinator = archive.isCoordinator() ? (byte) 1 : (byte) 0;
		buffer.put((byte) coordinator);

		List<CompensableArchive> compensables = archive.getCompensables();
		int compensableNumber = compensables.size();
		buffer.put((byte) compensableNumber);
		for (int i = 0; i < compensableNumber; i++) {
			CompensableArchive compensableArchive = compensables.get(i);
			this.serializeCompensableArchive(buffer, compensableArchive);
		}

		List<XAResourceArchive> resources = archive.getRemoteResources();
		int resourceNumber = resources.size();
		buffer.put((byte) resourceNumber);

		for (int i = 0; i < resourceNumber; i++) {
			XAResourceArchive resourceArchive = resources.get(i);
			this.serializeXAResourceArchive(buffer, resourceArchive);
		}

		int pos = buffer.position();
		byte[] byteArray = new byte[pos];
		buffer.flip();
		buffer.get(byteArray);

		return byteArray;
	}

	private byte[] serializeObject(Serializable obj) {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		HessianOutput ho = new HessianOutput(baos);
		try {
			ho.writeObject(obj);
			return baos.toByteArray();
		} catch (IOException ex) {
			return new byte[0];
		} finally {
			this.closeIfNecessary(baos);
		}

	}

	private Serializable deserializeObject(byte[] bytes) {
		ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
		HessianInput hi = new HessianInput(bais);
		Object result;
		try {
			result = hi.readObject();
			return (Serializable) result;
		} catch (IOException ex) {
			return null;
		} finally {
			this.closeIfNecessary(bais);
		}
	}

	private void serializeCompensableArchive(ByteBuffer buffer, CompensableArchive archive) {
		Xid xid = archive.getXid();
		byte[] transactiondId = new byte[XidFactory.GLOBAL_TRANSACTION_LENGTH];
		byte[] branchQualifier = new byte[XidFactory.BRANCH_QUALIFIER_LENGTH];
		byte[] global = xid.getGlobalTransactionId();
		byte[] branch = xid.getBranchQualifier();
		System.arraycopy(global, 0, transactiondId, 0, global.length);
		if (branch != null) {
			System.arraycopy(branch, 0, branchQualifier, 0, branch.length);
		}
		buffer.put(transactiondId);
		buffer.put(branchQualifier);

		boolean coordinator = archive.isCoordinator();
		boolean confirmed = archive.isConfirmed();
		boolean cancelled = archive.isCancelled();

		buffer.put(coordinator ? (byte) 1 : (byte) 0);
		buffer.put(confirmed ? (byte) 1 : (byte) 0);
		buffer.put(cancelled ? (byte) 1 : (byte) 0);

		CompensableInvocation compensable = archive.getCompensable();

		byte[] bytes = this.serializeObject(compensable);
		buffer.putInt(bytes.length);
		buffer.put(bytes);

	}

	private void serializeXAResourceArchive(ByteBuffer buffer, XAResourceArchive resourceArchive) {
		XAResourceDescriptor descriptor = resourceArchive.getDescriptor();
		String identifier = descriptor.getIdentifier();
		Xid branchXid = resourceArchive.getXid();

		byte[] branchQualifier = branchXid.getBranchQualifier();
		byte[] identifiers = identifier.getBytes();
		byte branchVote = (byte) resourceArchive.getVote();
		byte rolledback = resourceArchive.isRolledback() ? (byte) 1 : (byte) 0;
		byte committed = resourceArchive.isCommitted() ? (byte) 1 : (byte) 0;
		byte heuristic = resourceArchive.isHeuristic() ? (byte) 1 : (byte) 0;

		int length = branchQualifier.length + identifiers.length + 4;
		buffer.putInt(length);
		buffer.put(branchQualifier);
		buffer.put(branchVote);
		buffer.put(committed);
		buffer.put(rolledback);
		buffer.put(heuristic);
		buffer.put(identifiers);
	}

	public void updateCompensable(Xid transactionXid, CompensableArchive archive) {
		CompensableTransactionArchive transactionArchive = this.uploadTransaction(transactionXid);
		List<CompensableArchive> compensables = transactionArchive.getCompensables();
		CompensableArchive target = null;
		for (int i = 0; i < compensables.size(); i++) {
			CompensableArchive object = compensables.get(i);
			if (object.equals(archive)) {
				target = object;
				break;
			}
		}
		if (target == null) {
			compensables.add(archive);
		} else {
			target.setCancelled(archive.isCancelled());
			target.setConfirmed(archive.isConfirmed());
		}
		this.updateTransaction(transactionArchive);

	}

	private void closeIfNecessary(Closeable closeable) {
		if (closeable != null) {
			try {
				closeable.close();
			} catch (IOException ex) {
				logger.debug(ex.getMessage());
			}
		}
	}

	public File getDirectory() {
		return directory;
	}

	public void setDirectory(File directory) {
		this.directory = directory;
	}

	public XAResourceSerializer getResourceSerializer() {
		return resourceSerializer;
	}

	public void setResourceSerializer(XAResourceSerializer resourceSerializer) {
		this.resourceSerializer = resourceSerializer;
	}

}
