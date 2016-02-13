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
package org.bytesoft.bytetcc;

import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.transaction.HeuristicMixedException;
import javax.transaction.HeuristicRollbackException;
import javax.transaction.RollbackException;
import javax.transaction.Status;
import javax.transaction.Synchronization;
import javax.transaction.SystemException;
import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;

import org.apache.log4j.Logger;
import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.TransactionContext;
import org.bytesoft.compensable.archive.CompensableArchive;
import org.bytesoft.compensable.archive.CompensableResourceArchive;
import org.bytesoft.compensable.supports.logger.CompensableLogger;
import org.bytesoft.transaction.TransactionBeanFactory;
import org.bytesoft.transaction.archive.XAResourceArchive;
import org.bytesoft.transaction.supports.TransactionListener;
import org.bytesoft.transaction.xa.TransactionXid;
import org.bytesoft.transaction.xa.XidFactory;

public class CompensableTccTransaction extends CompensableTransaction {
	static final Logger logger = Logger.getLogger(CompensableTccTransaction.class.getSimpleName());

	public static int STATUS_UNKNOWN = 0;

	public static int STATUS_TRY_FAILURE = 1;
	public static int STATUS_TRIED = 2;
	public static int STATUS_TRY_MIXED = 3;

	public static int STATUS_CONFIRMING = 4;
	public static int STATUS_CONFIRM_FAILURE = 5;
	public static int STATUS_CONFIRMED = 6;

	public static int STATUS_CANCELLING = 7;
	public static int STATUS_CANCEL_FAILURE = 8;
	public static int STATUS_CANCELLED = 9;

	private int transactionStatus;
	private int compensableStatus;
	private final List<CompensableResourceArchive> coordinatorResourceArchiveList = new ArrayList<CompensableResourceArchive>();
	private final List<CompensableResourceArchive> participantResourceArchiveList = new ArrayList<CompensableResourceArchive>();
	private final Map<Xid, XAResourceArchive> resourceArchives = new ConcurrentHashMap<Xid, XAResourceArchive>();
	private ThreadLocal<TransactionContext> transientContexts = new ThreadLocal<TransactionContext>();

	private transient CompensableResourceArchive confirmArchive;
	private transient CompensableResourceArchive cancellArchive;

	private CompensableBeanFactory beanFactory;

	public CompensableTccTransaction(TransactionContext transactionContext) {
		super(transactionContext);
	}

	// public synchronized void markCoordinatorTriedSuccessfully() {
	// if (this.transactionContext.isCoordinator()) {
	// for (int i = 0; i < this.coordinatorArchives.size(); i++) {
	// CompensableArchive archive = this.coordinatorArchives.get(i);
	// archive.setCoordinatorTried(true);
	// }
	// }
	// }

	public synchronized void propagationBegin(TransactionContext lastestTransactionContext) {
		this.transientContexts.set(this.transactionContext);
		this.transactionContext = lastestTransactionContext;
	}

	public synchronized void propagationFinish(TransactionContext lastestTransactionContext) {
		TransactionContext originalTransactionContext = this.transientContexts.get();
		this.transientContexts.remove();
		if (originalTransactionContext != null) {
			this.transactionContext = originalTransactionContext;
		}
	}

	public synchronized void delistCompensableInvocation(CompensableInvocation compensable) {
		CompensableLogger transactionLogger = this.beanFactory.getCompensableLogger();

		Object identifier = compensable.getIdentifier();
		if (identifier == null) {
			TransactionXid currentXid = this.transactionContext.getXid();
			if (this.transactionContext.isCoordinator()) {
				CompensableResourceArchive archive = new CompensableResourceArchive();
				archive.setXid(currentXid);
				archive.setCompensable(compensable);
				archive.setCoordinator(true);
				this.coordinatorResourceArchiveList.add(archive);

				transactionLogger.updateTransaction(this.getTransactionArchive());
			} else {
				CompensableResourceArchive archive = new CompensableResourceArchive();
				archive.setXid(currentXid);
				archive.setCompensable(compensable);
				this.participantResourceArchiveList.add(archive);

				transactionLogger.updateTransaction(this.getTransactionArchive());
			}
			compensable.setIdentifier(currentXid);
		}

	}

	public synchronized void commit() throws RollbackException, HeuristicMixedException, HeuristicRollbackException,
			SecurityException, IllegalStateException, SystemException {
		throw new IllegalStateException();
	}

	public synchronized void nativeConfirm() {
		CompensableInvocationExecutor executor = this.beanFactory.getCompensableInvocationExecutor();
		CompensableLogger transactionLogger = this.beanFactory.getCompensableLogger();

		this.compensableStatus = CompensableTccTransaction.STATUS_CONFIRMING;

		if (this.transactionContext.isCoordinator()) {
			Iterator<CompensableResourceArchive> coordinatorItr = this.coordinatorResourceArchiveList.iterator();
			while (coordinatorItr.hasNext()) {
				CompensableResourceArchive archive = coordinatorItr.next();
				if (archive.isConfirmed()) {
					continue;
				}

				try {
					this.confirmArchive = archive;
					this.confirmArchive.setTxEnabled(false);
					executor.confirm(this.confirmArchive.getCompensable());
					if (this.confirmArchive.isTxEnabled() == false) {
						this.confirmArchive.setConfirmed(true);
					}
				} finally {
					this.confirmArchive.setTxEnabled(false);
					this.confirmArchive = null;
				}
			}
		}

		Iterator<CompensableResourceArchive> participantItr = this.participantResourceArchiveList.iterator();
		while (participantItr.hasNext()) {
			CompensableResourceArchive archive = participantItr.next();
			if (archive.isConfirmed()) {
				continue;
			}

			try {
				this.confirmArchive = archive;
				this.confirmArchive.setTxEnabled(false);
				executor.confirm(this.confirmArchive.getCompensable());
				if (this.confirmArchive.isTxEnabled() == false) {
					this.confirmArchive.setConfirmed(true);
				}
			} finally {
				this.confirmArchive.setTxEnabled(false);
				this.confirmArchive = null;
			}
		}
		this.compensableStatus = CompensableTccTransaction.STATUS_CONFIRMED;
		CompensableArchive archive = this.getTransactionArchive();
		transactionLogger.updateTransaction(archive);
	}

	public synchronized void remoteConfirm() throws SystemException, RemoteException {
		CompensableLogger transactionLogger = this.beanFactory.getCompensableLogger();

		// boolean confirmExists = false;
		boolean cancellExists = false;
		boolean errorExists = false;
		Set<Entry<Xid, XAResourceArchive>> entrySet = this.resourceArchives.entrySet();
		Iterator<Map.Entry<Xid, XAResourceArchive>> resourceItr = entrySet.iterator();
		while (resourceItr.hasNext()) {
			Map.Entry<Xid, XAResourceArchive> entry = resourceItr.next();
			Xid key = entry.getKey();
			XAResourceArchive archive = entry.getValue();
			if (archive.isCompleted()) {
				if (archive.isCommitted()) {
					// confirmExists = true;
				} else if (archive.isRolledback()) {
					cancellExists = true;
				} else {
					// ignore
				}
			} else {
				try {
					archive.commit(key, true);
					archive.setCommitted(true);
					archive.setCompleted(true);
					// confirmExists = true;
				} catch (XAException xaex) {
					switch (xaex.errorCode) {
					case XAException.XA_HEURCOM:
						archive.setCommitted(true);
						archive.setCompleted(true);
						// confirmExists = true;
						break;
					case XAException.XA_HEURRB:
						archive.setRolledback(true);
						archive.setCompleted(true);
						cancellExists = true;
						break;
					case XAException.XA_HEURMIX:
					case XAException.XAER_NOTA:
					case XAException.XAER_RMFAIL:
					case XAException.XAER_RMERR:
					default:
						errorExists = true;
					}
				}

				transactionLogger.updateCoordinator(archive);
			}

		} // end-while (resourceItr.hasNext())

		if (errorExists) {
			throw new SystemException();
		} else if (cancellExists) {
			throw new SystemException();
		}

	}

	public synchronized boolean delistResource(XAResource xaRes, int flag) throws IllegalStateException,
			SystemException {
		// if (XAResourceDescriptor.class.isInstance(xaRes)) {
		// XAResourceDescriptor descriptor = (XAResourceDescriptor) xaRes;
		// if (descriptor.isRemote()) {
		// Set<Entry<Xid, XAResourceArchive>> entrySet = this.resourceArchives.entrySet();
		// Iterator<Map.Entry<Xid, XAResourceArchive>> itr = entrySet.iterator();
		// while (itr.hasNext()) {
		// Map.Entry<Xid, XAResourceArchive> entry = itr.next();
		// XAResourceArchive archive = entry.getValue();
		// XAResourceDescriptor resource = archive.getDescriptor();
		// if (resource.equals(descriptor)) {
		// return true;
		// }
		// }
		//
		// Iterator<Map.Entry<Xid, XAResourceArchive>> iterator = entrySet.iterator();
		// while (iterator.hasNext()) {
		// Map.Entry<Xid, XAResourceArchive> entry = iterator.next();
		// XAResourceArchive archive = entry.getValue();
		// XAResourceDescriptor resource = archive.getDescriptor();
		// boolean isSameRM = false;
		// try {
		// isSameRM = resource.isSameRM(descriptor);
		// } catch (XAException ex) {
		// continue;
		// }
		// if (isSameRM) {
		// return true;
		// }
		// }
		//
		// XidFactory xidFactory = this.beanFactory.getCompensableXidFactory();
		// TransactionXid globalXid = this.transactionContext.getXid();
		// TransactionXid branchXid = xidFactory.createBranchXid(globalXid);
		// XAResourceArchive archive = new XAResourceArchive();
		// archive.setDescriptor(descriptor);
		// archive.setXid(branchXid);
		// this.resourceArchives.put(branchXid, archive);
		//
		// CompensableTransactionLogger transactionLogger = this.beanFactory.getTransactionLogger();
		// transactionLogger.updateTransaction(this.getTransactionArchive());
		//
		// return true;
		// } else {
		// return this.jtaTransaction.delistResource(xaRes, flag);
		// }
		// } else {
		// return this.jtaTransaction.delistResource(xaRes, flag);
		// }

		return false; // TODO
	}

	public synchronized boolean enlistResource(XAResource xaRes) throws RollbackException, IllegalStateException,
			SystemException {
		// if (XAResourceDescriptor.class.isInstance(xaRes)) {
		// XAResourceDescriptor descriptor = (XAResourceDescriptor) xaRes;
		// if (descriptor.isRemote()) {
		// Set<Entry<Xid, XAResourceArchive>> entrySet = this.resourceArchives.entrySet();
		// Iterator<Map.Entry<Xid, XAResourceArchive>> itr = entrySet.iterator();
		// while (itr.hasNext()) {
		// Map.Entry<Xid, XAResourceArchive> entry = itr.next();
		// XAResourceArchive archive = entry.getValue();
		// XAResourceDescriptor resource = archive.getDescriptor();
		// if (resource.equals(descriptor)) {
		// return true;
		// }
		// }
		//
		// Iterator<Map.Entry<Xid, XAResourceArchive>> iterator = entrySet.iterator();
		// while (iterator.hasNext()) {
		// Map.Entry<Xid, XAResourceArchive> entry = iterator.next();
		// XAResourceArchive archive = entry.getValue();
		// XAResourceDescriptor resource = archive.getDescriptor();
		// boolean isSameRM = false;
		// try {
		// isSameRM = resource.isSameRM(descriptor);
		// } catch (XAException ex) {
		// continue;
		// }
		// if (isSameRM) {
		// return true;
		// }
		// }
		//
		// XidFactory xidFactory = this.beanFactory.getCompensableXidFactory();
		// TransactionXid globalXid = this.transactionContext.getXid();
		// TransactionXid branchXid = xidFactory.createBranchXid(globalXid);
		// XAResourceArchive archive = new XAResourceArchive();
		// archive.setDescriptor(descriptor);
		// archive.setXid(branchXid);
		// this.resourceArchives.put(branchXid, archive);
		//
		// CompensableTransactionLogger transactionLogger = this.beanFactory.getTransactionLogger();
		// transactionLogger.updateTransaction(this.getTransactionArchive());
		//
		// return true;
		// } else {
		// return this.jtaTransaction.enlistResource(xaRes);
		// }
		// } else {
		// return this.jtaTransaction.enlistResource(xaRes);
		// }

		return false; // TODO
	}

	public int getStatus() /* throws SystemException */{
		return this.transactionStatus;
	}

	public synchronized void setTransactionStatus(int transactionStatus) {
		this.transactionStatus = transactionStatus;
	}

	public synchronized void registerSynchronization(Synchronization sync) throws RollbackException,
			IllegalStateException, SystemException {
		this.jtaTransaction.registerSynchronization(sync);
	}

	public synchronized void rollback() throws IllegalStateException, SystemException {
		throw new IllegalStateException();
	}

	public synchronized void nativeCancel(boolean coordinatorCancelRequired) {
		CompensableInvocationExecutor executor = this.beanFactory.getCompensableInvocationExecutor();
		CompensableLogger transactionLogger = this.beanFactory.getCompensableLogger();

		this.compensableStatus = CompensableTccTransaction.STATUS_CANCELLING;

		if (this.transactionContext.isCoordinator() && coordinatorCancelRequired) {
			Iterator<CompensableResourceArchive> coordinatorItr = this.coordinatorResourceArchiveList.iterator();
			while (coordinatorItr.hasNext()) {
				CompensableResourceArchive archive = coordinatorItr.next();
				if (archive.isCancelled()) {
					continue;
				}

				try {
					this.cancellArchive = archive;
					this.cancellArchive.setTxEnabled(false);
					executor.cancel(this.cancellArchive.getCompensable());
					if (this.cancellArchive.isTxEnabled() == false) {
						this.cancellArchive.setCancelled(true);
					}
				} finally {
					this.cancellArchive.setTxEnabled(false);
					this.cancellArchive = null;
				}
			}
		}

		Iterator<CompensableResourceArchive> participantItr = this.participantResourceArchiveList.iterator();
		while (participantItr.hasNext()) {
			CompensableResourceArchive archive = participantItr.next();
			if (archive.isCancelled()) {
				continue;
			}

			try {
				this.cancellArchive = archive;
				this.cancellArchive.setTxEnabled(false);
				executor.cancel(this.cancellArchive.getCompensable());
				if (this.cancellArchive.isTxEnabled() == false) {
					this.cancellArchive.setCancelled(true);
				}
			} finally {
				this.cancellArchive.setTxEnabled(false);
				this.cancellArchive = null;
			}
		}
		this.compensableStatus = CompensableTccTransaction.STATUS_CANCELLED;
		CompensableArchive archive = this.getTransactionArchive();
		transactionLogger.updateTransaction(archive);
	}

	public synchronized void remoteCancel() throws SystemException, RemoteException {
		CompensableLogger transactionLogger = this.beanFactory.getCompensableLogger();

		boolean confirmExists = false;
		// boolean cancellExists = false;
		boolean errorExists = false;
		Set<Entry<Xid, XAResourceArchive>> entrySet = this.resourceArchives.entrySet();
		Iterator<Map.Entry<Xid, XAResourceArchive>> resourceItr = entrySet.iterator();
		while (resourceItr.hasNext()) {
			Map.Entry<Xid, XAResourceArchive> entry = resourceItr.next();
			Xid key = entry.getKey();
			XAResourceArchive archive = entry.getValue();
			if (archive.isCompleted()) {
				if (archive.isCommitted()) {
					confirmExists = true;
				} else if (archive.isRolledback()) {
					// cancellExists = true;
				} else {
					// ignore
				}
			} else {
				try {
					archive.rollback(key);
					archive.setRolledback(true);
					archive.setCompleted(true);
					// cancellExists = true;
				} catch (XAException xaex) {
					switch (xaex.errorCode) {
					case XAException.XA_HEURCOM:
						archive.setCommitted(true);
						archive.setCompleted(true);
						confirmExists = true;
						break;
					case XAException.XA_HEURRB:
						archive.setRolledback(true);
						archive.setCompleted(true);
						// cancellExists = true;
						break;
					case XAException.XA_HEURMIX:
					case XAException.XAER_NOTA:
					case XAException.XAER_RMFAIL:
					case XAException.XAER_RMERR:
					default:
						errorExists = true;
					}
				}

				transactionLogger.updateCoordinator(archive);
			}

		} // end-while (resourceItr.hasNext())

		if (errorExists) {
			throw new SystemException();
		} else if (confirmExists) {
			throw new SystemException();
		}

	}

	public CompensableArchive getTransactionArchive() {

		XidFactory xidFactory = this.beanFactory.getCompensableXidFactory();

		TransactionContext transactionContext = this.getTransactionContext();

		CompensableArchive transactionArchive = new CompensableArchive();
		TransactionXid jtaGlobalXid = transactionContext.getXid();
		TransactionXid globalXid = xidFactory.createGlobalXid(jtaGlobalXid.getGlobalTransactionId());
		transactionArchive.setXid(globalXid);

		transactionArchive.setStatus(this.transactionStatus);
		transactionArchive.setCompensableStatus(this.compensableStatus);
		transactionArchive.setCompensable(transactionContext.isCompensable());
		transactionArchive.setCoordinator(transactionContext.isCoordinator());

		transactionArchive.getCompensableResourceList().addAll(this.coordinatorResourceArchiveList);
		transactionArchive.getCompensableResourceList().addAll(this.participantResourceArchiveList);

		transactionArchive.getRemoteResources().addAll(this.resourceArchives.values());

		return transactionArchive;
	}

	public synchronized void setRollbackOnly() throws IllegalStateException, SystemException {
		if (this.transactionStatus == Status.STATUS_ACTIVE || this.transactionStatus == Status.STATUS_MARKED_ROLLBACK) {
			if (this.jtaTransaction != null) {
				this.jtaTransaction.setRollbackOnlyQuietly();
			}
			this.transactionStatus = Status.STATUS_MARKED_ROLLBACK;
		} else {
			throw new IllegalStateException();
		}
	}

	public boolean isRollbackOnly() {
		return this.transactionStatus == Status.STATUS_MARKED_ROLLBACK;
	}

	private void markCompensableArchiveAsTxEnabledIfNeccessary() {
		if (this.transactionStatus == Status.STATUS_COMMITTING) {
			if (this.confirmArchive != null) {
				this.confirmArchive.setTxEnabled(true);
			}
		} else if (this.transactionStatus == Status.STATUS_ROLLING_BACK) {
			if (this.cancellArchive != null) {
				this.cancellArchive.setTxEnabled(true);
			}
		}
	}

	private void trySuccess() {
		this.compensableStatus = CompensableTccTransaction.STATUS_TRIED;
		CompensableArchive archive = this.getTransactionArchive();
		CompensableLogger transactionLogger = this.beanFactory.getCompensableLogger();
		transactionLogger.updateTransaction(archive);
	}

	private void tryFailure() {
		this.compensableStatus = CompensableTccTransaction.STATUS_TRY_FAILURE;
		CompensableArchive archive = this.getTransactionArchive();
		CompensableLogger transactionLogger = this.beanFactory.getCompensableLogger();
		transactionLogger.updateTransaction(archive);
	}

	private void tryMixed() {
		this.compensableStatus = CompensableTccTransaction.STATUS_TRY_MIXED;
		CompensableArchive archive = this.getTransactionArchive();
		CompensableLogger transactionLogger = this.beanFactory.getCompensableLogger();
		transactionLogger.updateTransaction(archive);
	}

	public void prepareStart() {
		this.markCompensableArchiveAsTxEnabledIfNeccessary();
	}

	public void prepareSuccess() {
		this.markCompensableArchiveAsTxEnabledIfNeccessary();
	}

	public void prepareFailure() {
		this.markCompensableArchiveAsTxEnabledIfNeccessary();
	}

	public void commitStart() {
		this.markCompensableArchiveAsTxEnabledIfNeccessary();
	}

	public void commitSuccess() {
		this.markCompensableArchiveAsTxEnabledIfNeccessary();

		if (this.transactionStatus == Status.STATUS_PREPARING) {
			this.trySuccess();
		} else if (this.transactionStatus == Status.STATUS_COMMITTING) {
			if (this.confirmArchive != null) {
				this.confirmArchive.setConfirmed(true);
			}
		} else if (this.transactionStatus == Status.STATUS_ROLLING_BACK) {
			if (this.cancellArchive != null) {
				this.cancellArchive.setCancelled(true);
			}
		}
	}

	public void commitFailure() {
		this.markCompensableArchiveAsTxEnabledIfNeccessary();

		if (this.transactionStatus == Status.STATUS_PREPARING) {
			this.tryFailure();
		} else if (this.transactionStatus == Status.STATUS_COMMITTING) {
			// TODO ignore?
		} else if (this.transactionStatus == Status.STATUS_ROLLING_BACK) {
			// TODO ignore?
		}
	}

	public void commitHeuristicMixed() {
		this.markCompensableArchiveAsTxEnabledIfNeccessary();
		if (this.transactionStatus == Status.STATUS_PREPARING) {
			this.tryMixed();
		} else if (this.transactionStatus == Status.STATUS_COMMITTING) {
			if (this.confirmArchive != null) {
				this.confirmArchive.setTxMixed(true);
			}
		} else if (this.transactionStatus == Status.STATUS_ROLLING_BACK) {
			// TODO ignore?
		}
	}

	public void commitHeuristicRolledback() {
		this.markCompensableArchiveAsTxEnabledIfNeccessary();
		if (this.transactionStatus == Status.STATUS_PREPARING) {
			this.tryFailure();
		} else if (this.transactionStatus == Status.STATUS_COMMITTING) {
			// TODO ignore?
		} else if (this.transactionStatus == Status.STATUS_ROLLING_BACK) {
			// TODO ignore?
		}
	}

	public void rollbackStart() {
		this.markCompensableArchiveAsTxEnabledIfNeccessary();
	}

	public void rollbackSuccess() {
		this.markCompensableArchiveAsTxEnabledIfNeccessary();

		if (this.transactionStatus == Status.STATUS_PREPARING) {
			this.tryFailure();
		} else if (this.transactionStatus == Status.STATUS_COMMITTING) {
			// ignore
		} else if (this.transactionStatus == Status.STATUS_ROLLING_BACK) {
			// ignore
		}
	}

	public void rollbackFailure() {
		this.markCompensableArchiveAsTxEnabledIfNeccessary();

		if (this.transactionStatus == Status.STATUS_PREPARING) {
			this.tryFailure();
		} else if (this.transactionStatus == Status.STATUS_COMMITTING) {
			// TODO ignore?
		} else if (this.transactionStatus == Status.STATUS_ROLLING_BACK) {
			// TODO ignore?
		}
	}

	public void setBeanFactory(TransactionBeanFactory beanFactory) {
		this.beanFactory = (CompensableBeanFactory) beanFactory;
	}

	public void registerTransactionListener(TransactionListener listener) {
	}

	public int getCompensableStatus() {
		return compensableStatus;
	}

	public void setCompensableStatus(int compensableStatus) {
		this.compensableStatus = compensableStatus;
	}

	public int getTransactionStatus() {
		return transactionStatus;
	}

	public List<CompensableResourceArchive> getCoordinatorResourceArchiveList() {
		return coordinatorResourceArchiveList;
	}

	public List<CompensableResourceArchive> getParticipantResourceArchiveList() {
		return participantResourceArchiveList;
	}

	public Map<Xid, XAResourceArchive> getResourceArchives() {
		return resourceArchives;
	}

}
