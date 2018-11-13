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
package org.bytesoft.bytetcc.logging;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.transaction.xa.Xid;

import org.apache.commons.lang3.StringUtils;
import org.bytesoft.bytejta.logging.store.VirtualLoggingSystemImpl;
import org.bytesoft.common.utils.ByteUtils;
import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.archive.CompensableArchive;
import org.bytesoft.compensable.archive.TransactionArchive;
import org.bytesoft.compensable.aware.CompensableBeanFactoryAware;
import org.bytesoft.compensable.aware.CompensableEndpointAware;
import org.bytesoft.compensable.logging.CompensableLogger;
import org.bytesoft.transaction.archive.XAResourceArchive;
import org.bytesoft.transaction.logging.ArchiveDeserializer;
import org.bytesoft.transaction.logging.LoggingFlushable;
import org.bytesoft.transaction.logging.store.VirtualLoggingListener;
import org.bytesoft.transaction.logging.store.VirtualLoggingRecord;
import org.bytesoft.transaction.logging.store.VirtualLoggingSystem;
import org.bytesoft.transaction.recovery.TransactionRecoveryCallback;
import org.bytesoft.transaction.xa.TransactionXid;
import org.bytesoft.transaction.xa.XidFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SampleCompensableLogger extends VirtualLoggingSystemImpl
		implements CompensableLogger, LoggingFlushable, CompensableBeanFactoryAware, CompensableEndpointAware {
	static final Logger logger = LoggerFactory.getLogger(SampleCompensableLogger.class);

	@javax.inject.Inject
	private CompensableBeanFactory beanFactory;
	private String endpoint;

	public void createTransaction(TransactionArchive archive) {
		ArchiveDeserializer deserializer = this.beanFactory.getArchiveDeserializer();

		try {
			byte[] byteArray = deserializer.serialize((TransactionXid) archive.getXid(), archive);
			this.create(archive.getXid(), byteArray);
		} catch (RuntimeException rex) {
			logger.error("Error occurred while creating transaction-archive.", rex);
		}
	}

	public void updateTransaction(TransactionArchive archive) {
		ArchiveDeserializer deserializer = this.beanFactory.getArchiveDeserializer();

		try {
			byte[] byteArray = deserializer.serialize((TransactionXid) archive.getXid(), archive);
			this.modify(archive.getXid(), byteArray);
		} catch (RuntimeException rex) {
			logger.error("Error occurred while modifying transaction-archive.", rex);
		}
	}

	public void deleteTransaction(TransactionArchive archive) {
		try {
			this.delete(archive.getXid());
		} catch (RuntimeException rex) {
			logger.error("Error occurred while deleting transaction-archive.", rex);
		}
	}

	public void createParticipant(XAResourceArchive archive) {
		ArchiveDeserializer deserializer = this.beanFactory.getArchiveDeserializer();

		try {
			byte[] byteArray = deserializer.serialize((TransactionXid) archive.getXid(), archive);
			this.create(archive.getXid(), byteArray);
		} catch (RuntimeException rex) {
			logger.error("Error occurred while modifying resource-archive.", rex);
		}
	}

	public void updateParticipant(XAResourceArchive archive) {
		ArchiveDeserializer deserializer = this.beanFactory.getArchiveDeserializer();

		try {
			byte[] byteArray = deserializer.serialize((TransactionXid) archive.getXid(), archive);
			this.modify(archive.getXid(), byteArray);
		} catch (RuntimeException rex) {
			logger.error("Error occurred while modifying resource-archive.", rex);
		}
	}

	public void deleteParticipant(XAResourceArchive archive) {
	}

	public void createCompensable(CompensableArchive archive) {
		ArchiveDeserializer deserializer = this.beanFactory.getArchiveDeserializer();

		try {
			TransactionXid xid = (TransactionXid) archive.getIdentifier();
			byte[] byteArray = deserializer.serialize(xid, archive);
			this.create(xid, byteArray);
		} catch (RuntimeException rex) {
			logger.error("Error occurred while creating compensable-archive.", rex);
		}
	}

	public void updateCompensable(CompensableArchive archive) {
		ArchiveDeserializer deserializer = this.beanFactory.getArchiveDeserializer();

		try {
			TransactionXid xid = (TransactionXid) archive.getIdentifier();
			byte[] byteArray = deserializer.serialize(xid, archive);
			this.modify(xid, byteArray);
		} catch (RuntimeException rex) {
			logger.error("Error occurred while modifying compensable-archive.", rex);
		}
	}

	public List<VirtualLoggingRecord> compressIfNecessary(List<VirtualLoggingRecord> recordList) {
		ArchiveDeserializer deserializer = this.beanFactory.getArchiveDeserializer();
		XidFactory xidFactory = this.beanFactory.getCompensableXidFactory();

		List<VirtualLoggingRecord> resultList = new ArrayList<VirtualLoggingRecord>();

		Map<TransactionXid, TransactionArchive> xidMap = new HashMap<TransactionXid, TransactionArchive>();
		for (int index = 0; recordList != null && index < recordList.size(); index++) {
			VirtualLoggingRecord record = recordList.get(index);
			byte[] byteArray = record.getContent();
			byte[] keyByteArray = new byte[XidFactory.GLOBAL_TRANSACTION_LENGTH];
			System.arraycopy(byteArray, 0, keyByteArray, 0, keyByteArray.length);
			byte[] valueByteArray = new byte[byteArray.length - XidFactory.GLOBAL_TRANSACTION_LENGTH - 1 - 4];
			System.arraycopy(byteArray, XidFactory.GLOBAL_TRANSACTION_LENGTH + 1 + 4, valueByteArray, 0, valueByteArray.length);

			TransactionXid xid = xidFactory.createGlobalXid(keyByteArray);

			Object obj = deserializer.deserialize(xid, valueByteArray);
			if (TransactionArchive.class.isInstance(obj)) {
				xidMap.put(xid, (TransactionArchive) obj);
			} else if (XAResourceArchive.class.isInstance(obj)) {
				TransactionArchive archive = xidMap.get(xid);
				if (archive == null) {
					logger.error("Error occurred while compressing resource archive: {}", obj);
					continue;
				}

				XAResourceArchive resourceArchive = (XAResourceArchive) obj;
				boolean matched = false;

				List<XAResourceArchive> remoteResources = archive.getRemoteResources();
				for (int i = 0; matched == false && remoteResources != null && i < remoteResources.size(); i++) {
					XAResourceArchive element = remoteResources.get(i);
					if (resourceArchive.getXid().equals(element.getXid())) {
						matched = true;
						remoteResources.set(i, resourceArchive);
					}
				}

				if (matched == false) {
					remoteResources.add(resourceArchive);
				}
			} else if (CompensableArchive.class.isInstance(obj)) {
				TransactionArchive archive = xidMap.get(xid);
				if (archive == null) {
					logger.error("Error occurred while compressing compensable archive: {}", obj);
					continue;
				}

				List<CompensableArchive> compensables = archive.getCompensableResourceList();
				CompensableArchive resourceArchive = (CompensableArchive) obj;

				boolean matched = false;
				for (int i = 0; matched == false && compensables != null && i < compensables.size(); i++) {
					CompensableArchive element = compensables.get(i);
					if (resourceArchive.getIdentifier().equals(element.getIdentifier())) {
						matched = true;
						compensables.set(i, resourceArchive);
					}
				}

				if (matched == false) {
					compensables.add(resourceArchive);
				}

			} else {
				logger.error("unkown resource: {}!", obj);
			}
		} // end-for (int index = 0; recordList != null && index < recordList.size(); index++)

		for (Iterator<Map.Entry<TransactionXid, TransactionArchive>> itr = xidMap.entrySet().iterator(); itr.hasNext();) {
			Map.Entry<TransactionXid, TransactionArchive> entry = itr.next();
			TransactionXid xid = entry.getKey();
			TransactionArchive value = entry.getValue();

			byte[] globalByteArray = xid.getGlobalTransactionId();

			byte[] keyByteArray = new byte[XidFactory.GLOBAL_TRANSACTION_LENGTH];
			byte[] valueByteArray = deserializer.serialize(xid, value);
			byte[] sizeByteArray = ByteUtils.intToByteArray(valueByteArray.length);

			System.arraycopy(globalByteArray, 0, keyByteArray, 0, XidFactory.GLOBAL_TRANSACTION_LENGTH);

			byte[] byteArray = new byte[XidFactory.GLOBAL_TRANSACTION_LENGTH + 1 + 4 + valueByteArray.length];

			System.arraycopy(keyByteArray, 0, byteArray, 0, keyByteArray.length);
			byteArray[keyByteArray.length] = OPERATOR_CREATE;
			System.arraycopy(sizeByteArray, 0, byteArray, XidFactory.GLOBAL_TRANSACTION_LENGTH + 1, sizeByteArray.length);
			System.arraycopy(valueByteArray, 0, byteArray, XidFactory.GLOBAL_TRANSACTION_LENGTH + 1 + 4, valueByteArray.length);

			VirtualLoggingRecord record = new VirtualLoggingRecord();
			record.setIdentifier(xid);
			record.setOperator(OPERATOR_CREATE);
			record.setValue(valueByteArray);
			record.setContent(byteArray);

			resultList.add(record);
		}

		return resultList;
	}

	public void recover(TransactionRecoveryCallback callback) {

		final Map<Xid, TransactionArchive> xidMap = new HashMap<Xid, TransactionArchive>();
		final ArchiveDeserializer deserializer = this.beanFactory.getArchiveDeserializer();
		final XidFactory xidFactory = this.beanFactory.getCompensableXidFactory();

		this.traversal(new VirtualLoggingListener() {
			public void recvOperation(VirtualLoggingRecord action) {
				Xid xid = action.getIdentifier();
				int operator = action.getOperator();
				if (VirtualLoggingSystem.OPERATOR_DELETE == operator) {
					xidMap.remove(xid);
				} else if (xidMap.containsKey(xid) == false) {
					xidMap.put(xid, null);
				}
			}
		});

		this.traversal(new VirtualLoggingListener() {
			public void recvOperation(VirtualLoggingRecord action) {
				Xid xid = action.getIdentifier();
				if (xidMap.containsKey(xid)) {
					this.execOperation(action);
				}
			}

			public void execOperation(VirtualLoggingRecord action) {
				Xid identifier = action.getIdentifier();

				TransactionXid xid = xidFactory.createGlobalXid(identifier.getGlobalTransactionId());

				Object obj = deserializer.deserialize(xid, action.getValue());
				if (TransactionArchive.class.isInstance(obj)) {
					TransactionArchive archive = (TransactionArchive) obj;
					xidMap.put(identifier, archive);
				} else if (XAResourceArchive.class.isInstance(obj)) {
					TransactionArchive archive = xidMap.get(identifier);
					if (archive == null) {
						logger.error("Error occurred while recovering resource archive: {}", obj);
						return;
					}

					XAResourceArchive resourceArchive = (XAResourceArchive) obj;
					boolean matched = false;

					List<XAResourceArchive> remoteResources = archive.getRemoteResources();
					for (int i = 0; matched == false && remoteResources != null && i < remoteResources.size(); i++) {
						XAResourceArchive element = remoteResources.get(i);
						if (resourceArchive.getXid().equals(element.getXid())) {
							matched = true;
							remoteResources.set(i, resourceArchive);
						}
					}

					if (matched == false) {
						remoteResources.add(resourceArchive);
					}

				} else if (CompensableArchive.class.isInstance(obj)) {
					TransactionArchive archive = xidMap.get(identifier);
					if (archive == null) {
						logger.error("Error occurred while recovering compensable archive: {}", obj);
						return;
					}

					List<CompensableArchive> compensables = archive.getCompensableResourceList();
					CompensableArchive resourceArchive = (CompensableArchive) obj;

					// if (VirtualLoggingSystem.OPERATOR_CREATE == action.getOperator()) {
					// compensables.add(resourceArchive);
					// } else {
					boolean matched = false;
					for (int i = 0; matched == false && compensables != null && i < compensables.size(); i++) {
						CompensableArchive element = compensables.get(i);
						if (resourceArchive.getIdentifier().equals(element.getIdentifier())) {
							matched = true;
							compensables.set(i, resourceArchive);
						}
					}

					if (matched == false) {
						compensables.add(resourceArchive);
					}

					// }

				}

			}
		});

		for (Iterator<Map.Entry<Xid, TransactionArchive>> itr = xidMap.entrySet().iterator(); itr.hasNext();) {
			Map.Entry<Xid, TransactionArchive> entry = itr.next();
			TransactionArchive archive = entry.getValue();
			if (archive == null) {
				continue;
			} else {
				try {
					callback.recover(archive);
				} catch (RuntimeException rex) {
					logger.error("Error occurred while recovering transaction(xid= {}).", archive.getXid(), rex);
				}
			}
		}

	}

	public File getDefaultDirectory() {
		String address = StringUtils.trimToEmpty(this.endpoint);
		File directory = new File(String.format("bytetcc/%s", address.replaceAll("\\W", "_")));
		if (directory.exists() == false) {
			try {
				directory.mkdirs();
			} catch (SecurityException ex) {
				logger.error("Error occurred while creating directory {}!", directory.getAbsolutePath(), ex);
			}
		}
		return directory;
	}

	public int getMajorVersion() {
		return 1;
	}

	public int getMinorVersion() {
		return 0;
	}

	public String getLoggingFilePrefix() {
		return "bytetcc-";
	}

	public String getLoggingIdentifier() {
		return "org.bytesoft.bytetcc.logging.sample";
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

	public void setEndpoint(String endpoint) {
		this.endpoint = endpoint;
	}

}
