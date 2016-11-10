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
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.transaction.xa.Xid;

import org.apache.commons.lang3.StringUtils;
import org.bytesoft.bytejta.logging.store.VirtualLoggingSystemImpl;
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

	public void createCoordinator(XAResourceArchive archive) {
		ArchiveDeserializer deserializer = this.beanFactory.getArchiveDeserializer();

		try {
			byte[] byteArray = deserializer.serialize((TransactionXid) archive.getXid(), archive);
			this.create(archive.getXid(), byteArray);
		} catch (RuntimeException rex) {
			logger.error("Error occurred while modifying resource-archive.", rex);
		}
	}

	public void updateCoordinator(XAResourceArchive archive) {
		ArchiveDeserializer deserializer = this.beanFactory.getArchiveDeserializer();

		try {
			byte[] byteArray = deserializer.serialize((TransactionXid) archive.getXid(), archive);
			this.modify(archive.getXid(), byteArray);
		} catch (RuntimeException rex) {
			logger.error("Error occurred while modifying resource-archive.", rex);
		}
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
		File directory = new File(String.format("bytetcc/%s", address.replaceAll("\\:|\\.", "_")));
		if (directory.exists() == false) {
			try {
				directory.mkdirs();
			} catch (SecurityException ex) {
				logger.error("Error occurred while creating directory {}!", directory.getAbsolutePath(), ex);
			}
		}
		return directory;
	}

	public String getLoggingFilePrefix() {
		return "bytetcc-";
	}

	public String getLoggingIdentifier() {
		return "org.bytesoft.bytetcc.logging.sample";
	}

	public void setBeanFactory(CompensableBeanFactory tbf) {
		this.beanFactory = tbf;
	}

	public void setEndpoint(String endpoint) {
		this.endpoint = endpoint;
	}

}
