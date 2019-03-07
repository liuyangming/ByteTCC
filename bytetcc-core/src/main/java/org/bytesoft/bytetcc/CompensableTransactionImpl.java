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

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import javax.transaction.HeuristicMixedException;
import javax.transaction.HeuristicRollbackException;
import javax.transaction.RollbackException;
import javax.transaction.Status;
import javax.transaction.Synchronization;
import javax.transaction.SystemException;
import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;

import org.apache.commons.lang3.StringUtils;
import org.bytesoft.bytejta.supports.jdbc.RecoveredResource;
import org.bytesoft.bytejta.supports.resource.RemoteResourceDescriptor;
import org.bytesoft.bytetcc.supports.CompensableRolledbackMarker;
import org.bytesoft.bytetcc.supports.resource.LocalResourceCleaner;
import org.bytesoft.common.utils.ByteUtils;
import org.bytesoft.common.utils.CommonUtils;
import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.CompensableInvocation;
import org.bytesoft.compensable.CompensableTransaction;
import org.bytesoft.compensable.ContainerContext;
import org.bytesoft.compensable.TransactionContext;
import org.bytesoft.compensable.archive.CompensableArchive;
import org.bytesoft.compensable.archive.TransactionArchive;
import org.bytesoft.compensable.logging.CompensableLogger;
import org.bytesoft.transaction.CommitRequiredException;
import org.bytesoft.transaction.RollbackRequiredException;
import org.bytesoft.transaction.Transaction;
import org.bytesoft.transaction.TransactionRepository;
import org.bytesoft.transaction.archive.XAResourceArchive;
import org.bytesoft.transaction.remote.RemoteCoordinator;
import org.bytesoft.transaction.remote.RemoteNode;
import org.bytesoft.transaction.remote.RemoteSvc;
import org.bytesoft.transaction.supports.TransactionExtra;
import org.bytesoft.transaction.supports.TransactionListener;
import org.bytesoft.transaction.supports.TransactionListenerAdapter;
import org.bytesoft.transaction.supports.TransactionResourceListener;
import org.bytesoft.transaction.supports.resource.XAResourceDescriptor;
import org.bytesoft.transaction.supports.serialize.XAResourceDeserializer;
import org.bytesoft.transaction.xa.TransactionXid;
import org.bytesoft.transaction.xa.XidFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CompensableTransactionImpl extends TransactionListenerAdapter
		implements CompensableTransaction, CompensableRolledbackMarker {
	static final Logger logger = LoggerFactory.getLogger(CompensableTransactionImpl.class);

	private final TransactionContext transactionContext;
	private final List<CompensableArchive> archiveList = new ArrayList<CompensableArchive>();
	private final Map<RemoteSvc, XAResourceArchive> resourceMap = new HashMap<RemoteSvc, XAResourceArchive>();
	private final List<XAResourceArchive> resourceList = new ArrayList<XAResourceArchive>();
	private final Map<Thread, Transaction> transactionMap = new ConcurrentHashMap<Thread, Transaction>();
	private CompensableBeanFactory beanFactory;

	private int transactionVote;
	private int transactionStatus = Status.STATUS_ACTIVE;
	/* current compensable-decision in confirm/cancel phase. */
	private transient Boolean positive;
	/* current compensable-archive in confirm/cancel phase. */
	private transient CompensableArchive archive;

	private transient final Map<Xid, List<CompensableArchive>> xidToArchivesMap = new HashMap<Xid, List<CompensableArchive>>();
	private transient final Map<Xid, TransactionBranch> xidToBranchMap = new HashMap<Xid, TransactionBranch>();

	private Map<String, Serializable> variables = new HashMap<String, Serializable>();

	private Thread currentThread;
	private final Lock lock = new ReentrantLock();

	private transient Exception createdAt;

	public CompensableTransactionImpl(TransactionContext txContext) {
		this.transactionContext = txContext;
	}

	public TransactionArchive getTransactionArchive() {
		TransactionArchive transactionArchive = new TransactionArchive();
		transactionArchive.setVariables(this.variables);
		transactionArchive.setCoordinator(this.transactionContext.isCoordinator());
		transactionArchive.setPropagated(this.transactionContext.isPropagated());
		transactionArchive.setCompensable(this.transactionContext.isCompensable());
		transactionArchive.setCompensableStatus(this.transactionStatus);
		transactionArchive.setVote(this.transactionVote);
		transactionArchive.setXid(this.transactionContext.getXid());
		transactionArchive.getRemoteResources().addAll(this.resourceList);
		transactionArchive.getCompensableResourceList().addAll(this.archiveList);
		transactionArchive.setPropagatedBy(this.transactionContext.getPropagatedBy());
		transactionArchive.setRecoveredAt(this.transactionContext.getCreatedTime());
		transactionArchive.setRecoveredTimes(this.transactionContext.getRecoveredTimes());
		return transactionArchive;
	}

	public synchronized void participantCommit(boolean opc) throws RollbackException, HeuristicMixedException,
			HeuristicRollbackException, SecurityException, IllegalStateException, CommitRequiredException, SystemException {

		// Recover if transaction is recovered from tx-log.
		this.recoverIfNecessary();

		if (this.transactionStatus != Status.STATUS_COMMITTED) {
			this.fireCommit(); // TODO
		}

	}

	public synchronized void commit() throws RollbackException, HeuristicMixedException, HeuristicRollbackException,
			SecurityException, IllegalStateException, SystemException {

		if (this.transactionStatus == Status.STATUS_ACTIVE) {
			this.fireCommit();
		} else if (this.transactionStatus == Status.STATUS_MARKED_ROLLBACK) {
			this.fireRollback();
			throw new HeuristicRollbackException();
		} else if (this.transactionStatus == Status.STATUS_ROLLEDBACK) /* should never happen */ {
			throw new RollbackException();
		} else if (this.transactionStatus == Status.STATUS_COMMITTED) /* should never happen */ {
			logger.debug("Current transaction has already been committed.");
		} else {
			throw new IllegalStateException();
		}

	}

	private void fireCommit() throws RollbackException, HeuristicMixedException, HeuristicRollbackException, SecurityException,
			IllegalStateException, SystemException {
		CompensableLogger compensableLogger = this.beanFactory.getCompensableLogger();

		this.transactionContext.setCompensating(true);
		this.transactionStatus = Status.STATUS_COMMITTING;
		compensableLogger.updateTransaction(this.getTransactionArchive());

		SystemException systemEx = null;
		try {
			this.fireNativeParticipantConfirm();
		} catch (SystemException ex) {
			systemEx = ex;

			logger.info("{}| confirm native branchs failed!",
					ByteUtils.byteArrayToString(this.transactionContext.getXid().getGlobalTransactionId()), ex);
		} catch (RuntimeException ex) {
			systemEx = new SystemException(XAException.XAER_RMERR);
			systemEx.initCause(ex);

			logger.info("{}| confirm native branchs failed!",
					ByteUtils.byteArrayToString(this.transactionContext.getXid().getGlobalTransactionId()), ex);
		}

		try {
			this.fireRemoteParticipantConfirm();
		} catch (HeuristicMixedException ex) {
			logger.info("{}| confirm remote branchs failed!",
					ByteUtils.byteArrayToString(this.transactionContext.getXid().getGlobalTransactionId()), ex);
			throw ex;
		} catch (HeuristicRollbackException ex) {
			logger.info("{}| confirm remote branchs failed!",
					ByteUtils.byteArrayToString(this.transactionContext.getXid().getGlobalTransactionId()), ex);
			throw ex;
		} catch (SystemException ex) {
			logger.info("{}| confirm remote branchs failed!",
					ByteUtils.byteArrayToString(this.transactionContext.getXid().getGlobalTransactionId()), ex);
			throw ex;
		} catch (RuntimeException ex) {
			logger.info("{}| confirm remote branchs failed!",
					ByteUtils.byteArrayToString(this.transactionContext.getXid().getGlobalTransactionId()), ex);
			throw ex;
		}

		if (systemEx != null) {
			throw systemEx;
		}

		this.transactionStatus = Status.STATUS_COMMITTED;
		compensableLogger.updateTransaction(this.getTransactionArchive());
		logger.info("{}| compensable transaction committed!",
				ByteUtils.byteArrayToString(transactionContext.getXid().getGlobalTransactionId()));
	}

	public synchronized void recoveryCommit() throws CommitRequiredException, SystemException {
		this.recoverIfNecessary(); // Recover if transaction is recovered from tx-log.

		this.transactionContext.setRecoveredTimes(this.transactionContext.getRecoveredTimes() + 1);
		this.transactionContext.setCreatedTime(System.currentTimeMillis());

		try {
			this.fireCommit();
		} catch (SecurityException ex) {
			logger.error("{}| confirm native/remote branchs failed!",
					ByteUtils.byteArrayToString(this.transactionContext.getXid().getGlobalTransactionId()), ex);
			SystemException sysEx = new SystemException(XAException.XAER_RMERR);
			sysEx.initCause(ex);
			throw sysEx;
		} catch (RollbackException ex) {
			logger.error("{}| confirm native/remote branchs failed!",
					ByteUtils.byteArrayToString(this.transactionContext.getXid().getGlobalTransactionId()), ex);
			SystemException sysEx = new SystemException(XAException.XAER_RMERR);
			sysEx.initCause(ex);
			throw sysEx;
		} catch (HeuristicMixedException ex) {
			logger.error("{}| confirm native/remote branchs failed!",
					ByteUtils.byteArrayToString(this.transactionContext.getXid().getGlobalTransactionId()), ex);
			SystemException sysEx = new SystemException(XAException.XA_HEURMIX);
			sysEx.initCause(ex);
			throw sysEx;
		} catch (HeuristicRollbackException ex) {
			logger.error("{}| confirm native/remote branchs failed!",
					ByteUtils.byteArrayToString(this.transactionContext.getXid().getGlobalTransactionId()), ex);
			SystemException sysEx = new SystemException(XAException.XA_HEURRB);
			sysEx.initCause(ex);
			throw sysEx;
		}

	}

	private void fireNativeParticipantConfirm() throws SystemException {
		boolean errorExists = false;

		ContainerContext container = this.beanFactory.getContainerContext();
		for (int i = this.archiveList.size() - 1; i >= 0; i--) {
			CompensableArchive current = this.archiveList.get(i);
			if (current.isConfirmed()) {
				continue;
			}

			try {
				this.positive = true;
				this.archive = current;
				CompensableInvocation invocation = current.getCompensable();
				if (invocation == null) {
					errorExists = true;
					logger.error(
							"{}| error occurred while confirming service: {}, please check whether the params of method(compensable-service) supports serialization.",
							ByteUtils.byteArrayToString(this.transactionContext.getXid().getGlobalTransactionId()),
							ByteUtils.byteArrayToString(current.getIdentifier().getGlobalTransactionId()));
				} else if (StringUtils.isNotBlank(invocation.getConfirmableKey())) {
					container.confirm(invocation);
				} else {
					current.setConfirmed(true);
					logger.info("{}| confirm: identifier= {}, resourceKey= {}, resourceXid= {}.",
							ByteUtils.byteArrayToString(transactionContext.getXid().getGlobalTransactionId()),
							ByteUtils.byteArrayToString(current.getIdentifier().getGlobalTransactionId()),
							current.getCompensableResourceKey(), current.getCompensableXid());
				}
			} catch (RuntimeException rex) {
				errorExists = true;
				logger.error("{}| error occurred while confirming service: {}",
						ByteUtils.byteArrayToString(this.transactionContext.getXid().getGlobalTransactionId()), current, rex);
			} finally {
				this.archive = null;
				this.positive = null;
			}
		}

		if (errorExists) {
			throw new SystemException(XAException.XAER_RMERR);
		}

	}

	private void fireRemoteParticipantConfirm()
			throws HeuristicMixedException, HeuristicRollbackException, CommitRequiredException, SystemException {
		boolean committedExists = false;
		boolean rolledbackExists = false;
		boolean unFinishExists = false;
		boolean errorExists = false;

		for (int i = 0; i < this.resourceList.size(); i++) {
			XAResourceArchive current = this.resourceList.get(i);
			if (current.isCommitted()) {
				committedExists = true;
				continue;
			} else if (current.isRolledback()) {
				rolledbackExists = true;
				continue;
			} else if (current.isReadonly()) {
				continue;
			}

			CompensableLogger transactionLogger = this.beanFactory.getCompensableLogger();
			XidFactory xidFactory = this.beanFactory.getCompensableXidFactory();
			TransactionXid branchXid = (TransactionXid) current.getXid();
			TransactionXid globalXid = xidFactory.createGlobalXid(branchXid.getGlobalTransactionId());
			try {
				current.commit(globalXid, true);
				committedExists = true;

				current.setCommitted(true);
				current.setCompleted(true);

				logger.info("{}| confirm remote branch: {}", ByteUtils.byteArrayToString(branchXid.getGlobalTransactionId()),
						current.getDescriptor().getIdentifier());
			} catch (XAException ex) {
				switch (ex.errorCode) {
				case XAException.XA_HEURCOM:
					committedExists = true;
					current.setHeuristic(true);
					current.setCommitted(true);
					current.setCompleted(true);
					break;
				case XAException.XA_HEURMIX:
					committedExists = true;
					rolledbackExists = true;

					current.setHeuristic(true);
					current.setCommitted(true);
					current.setRolledback(true);
					current.setCompleted(true);

					logger.error("{}| error occurred while confirming remote branch: {}, transaction has been completed!",
							ByteUtils.byteArrayToString(branchXid.getGlobalTransactionId()),
							current.getDescriptor().getIdentifier(), ex);

					break;
				case XAException.XA_HEURRB:
					rolledbackExists = true;

					current.setHeuristic(true);
					current.setRolledback(true);
					current.setCompleted(true);
					break;
				case XAException.XA_HEURHAZ:
					unFinishExists = true;

					current.setHeuristic(true);
					logger.warn("{}| error occurred while confirming remote branch: {}, transaction may has been completd!",
							ByteUtils.byteArrayToString(branchXid.getGlobalTransactionId()),
							current.getDescriptor().getIdentifier(), ex);
					break;
				case XAException.XAER_RMFAIL:
					unFinishExists = true;

					logger.warn("{}| error occurred while confirming remote branch: {}, the remote branch is unreachable!",
							ByteUtils.byteArrayToString(branchXid.getGlobalTransactionId()),
							current.getDescriptor().getIdentifier(), ex);
					break;
				case XAException.XAER_NOTA:
					committedExists = true; // TODO 1) tried & committed; 2) have not tried
					current.setCommitted(true);
					current.setCompleted(true);
					break;
				case XAException.XAER_RMERR:
				case XAException.XAER_INVAL:
				case XAException.XAER_PROTO:
					errorExists = true;

					logger.warn("{}| error occurred while confirming remote branch: {}!",
							ByteUtils.byteArrayToString(branchXid.getGlobalTransactionId()),
							current.getDescriptor().getIdentifier(), ex);
					break;
				case XAException.XA_RBCOMMFAIL:
				case XAException.XA_RBDEADLOCK:
				case XAException.XA_RBINTEGRITY:
				case XAException.XA_RBOTHER:
				case XAException.XA_RBPROTO:
				case XAException.XA_RBROLLBACK:
				case XAException.XA_RBTIMEOUT:
				case XAException.XA_RBTRANSIENT:
				default:
					rolledbackExists = true;

					current.setRolledback(true);
					current.setCompleted(true);

					logger.error("{}| error occurred while confirming remote branch: {}, transaction has been rolled back!",
							ByteUtils.byteArrayToString(branchXid.getGlobalTransactionId()),
							current.getDescriptor().getIdentifier(), ex);
				}

			} catch (RuntimeException rex) {
				errorExists = true;
				logger.warn("{}| error occurred while confirming remote branch: {}!",
						ByteUtils.byteArrayToString(branchXid.getGlobalTransactionId()),
						current.getDescriptor().getIdentifier(), rex);
			} finally {
				if (current.isCompleted()) {
					transactionLogger.updateParticipant(current);
				}
			}
		}

		if (committedExists && rolledbackExists) {
			throw new HeuristicMixedException();
		} else if (unFinishExists) {
			throw new CommitRequiredException();
		} else if (errorExists) {
			throw new SystemException(XAException.XAER_RMERR);
		} else if (rolledbackExists) {
			throw new HeuristicRollbackException();
		}
		// else if (committedExists == false) { throw new XAException(XAException.XA_RDONLY); }
	}

	public int participantPrepare() throws RollbackRequiredException, CommitRequiredException {
		throw new RuntimeException("Not supported!");
	}

	public synchronized void participantRollback() throws IllegalStateException, SystemException {

		// Recover if transaction is recovered from tx-log.
		this.recoverIfNecessary();

		if (this.transactionStatus != Status.STATUS_ROLLEDBACK) {
			this.fireRollback(); // TODO
		}

	}

	public synchronized void rollback() throws IllegalStateException, SystemException {
		if (this.transactionStatus == Status.STATUS_UNKNOWN) {
			throw new IllegalStateException();
		} else if (this.transactionStatus == Status.STATUS_NO_TRANSACTION) {
			throw new IllegalStateException();
		} else if (this.transactionStatus == Status.STATUS_COMMITTED) /* should never happen */ {
			throw new IllegalStateException();
		} else if (this.transactionStatus == Status.STATUS_ROLLEDBACK) /* should never happen */ {
			logger.debug("Current transaction has already been rolled back.");
		} else {
			this.fireRollback();
		}
	}

	private void markCurrentBranchTransactionRollbackIfNecessary() throws SystemException {
		CompensableRolledbackMarker compensableRolledbackMarker = this.beanFactory.getCompensableRolledbackMarker();
		TransactionXid transactionXid = this.transactionContext.getXid();

		this.markBusinessStageRollbackOnly(transactionXid);

		if (compensableRolledbackMarker != null) {
			compensableRolledbackMarker.markBusinessStageRollbackOnly(transactionXid);
		} // end-if (compensableRolledbackMarker != null)
	}

	public void markBusinessStageRollbackOnly(TransactionXid transactionXid) throws SystemException {
		List<Transaction> transactions = new ArrayList<Transaction>(this.transactionMap.values());
		boolean recoveried = this.transactionContext.isRecoveried();
		if (recoveried == false && transactions.isEmpty() == false) /* used by participant only. */ {
			for (int i = 0; i < transactions.size(); i++) {
				Transaction branch = transactions.get(i);
				try {
					branch.setRollbackOnly();
				} catch (IllegalStateException ex) {
					logger.info("The local transaction is not active.", ex); // tx in try-phase has been completed already.
				} catch (SystemException ex) {
					logger.warn("The local transaction is not active.", ex); // should never happen
				} catch (RuntimeException ex) {
					logger.warn("The local transaction is not active.", ex); // should never happen
				}
			} // end-for (int i = 0; i < transactions.size(); i++)
		}
	}

	private void fireRollback() throws IllegalStateException, SystemException {
		CompensableLogger compensableLogger = this.beanFactory.getCompensableLogger();

		this.transactionStatus = Status.STATUS_ROLLING_BACK;

		this.markCurrentBranchTransactionRollbackIfNecessary();

		this.transactionContext.setCompensating(true);
		compensableLogger.updateTransaction(this.getTransactionArchive());

		SystemException systemEx = null;
		try {
			this.fireNativeParticipantCancel();
		} catch (SystemException ex) {
			systemEx = ex;

			logger.info("{}| cancel native branchs failed!",
					ByteUtils.byteArrayToString(this.transactionContext.getXid().getGlobalTransactionId()), ex);
		} catch (RuntimeException ex) {
			systemEx = new SystemException(XAException.XAER_RMERR);
			systemEx.initCause(ex);

			logger.info("{}| cancel native branchs failed!",
					ByteUtils.byteArrayToString(this.transactionContext.getXid().getGlobalTransactionId()), ex);
		}

		try {
			this.fireRemoteParticipantCancel();
		} catch (SystemException ex) {
			logger.info("{}| cancel remote branchs failed!",
					ByteUtils.byteArrayToString(this.transactionContext.getXid().getGlobalTransactionId()), ex);
			throw ex;
		} catch (RuntimeException ex) {
			logger.info("{}| cancel remote branchs failed!",
					ByteUtils.byteArrayToString(this.transactionContext.getXid().getGlobalTransactionId()), ex);
			SystemException sysEx = new SystemException(XAException.XAER_RMERR);
			sysEx.initCause(ex);
			throw sysEx;
		}

		if (systemEx != null) {
			throw systemEx;
		} else {
			this.transactionStatus = Status.STATUS_ROLLEDBACK;
			compensableLogger.updateTransaction(this.getTransactionArchive());
			logger.info("{}| compensable transaction rolled back!",
					ByteUtils.byteArrayToString(transactionContext.getXid().getGlobalTransactionId()));
		}

	}

	public synchronized void recoveryRollback() throws RollbackRequiredException, SystemException {
		this.recoverIfNecessary(); // Recover if transaction is recovered from tx-log.

		this.transactionContext.setRecoveredTimes(this.transactionContext.getRecoveredTimes() + 1);
		this.transactionContext.setCreatedTime(System.currentTimeMillis());

		this.fireRollback();
	}

	private void fireNativeParticipantCancel() throws SystemException {
		boolean errorExists = false;

		ContainerContext container = this.beanFactory.getContainerContext();
		for (int i = this.archiveList.size() - 1; i >= 0; i--) {
			CompensableArchive current = this.archiveList.get(i);
			if (current.isTried() == false) {
				logger.info(
						"{}| The operation in try phase is rolled back, so the cancel operation is ignored, compensable service: {}.",
						ByteUtils.byteArrayToString(transactionContext.getXid().getGlobalTransactionId()),
						ByteUtils.byteArrayToString(current.getIdentifier().getGlobalTransactionId()));
				continue;
			} else if (current.isCancelled()) {
				continue;
			}

			try {
				this.positive = false;
				this.archive = current;
				CompensableInvocation invocation = current.getCompensable();
				if (invocation == null) {
					errorExists = true;
					logger.error(
							"{}| error occurred while cancelling service: {}, please check whether the params of method(compensable-service) supports serialization.",
							ByteUtils.byteArrayToString(this.transactionContext.getXid().getGlobalTransactionId()),
							ByteUtils.byteArrayToString(current.getIdentifier().getGlobalTransactionId()));
				} else if (StringUtils.isNotBlank(invocation.getCancellableKey())) {
					container.cancel(invocation);
				} else {
					current.setCancelled(true);
					logger.info("{}| cancel: identifier= {}, resourceKey= {}, resourceXid= {}.",
							ByteUtils.byteArrayToString(this.transactionContext.getXid().getGlobalTransactionId()),
							ByteUtils.byteArrayToString(current.getIdentifier().getGlobalTransactionId()),
							current.getCompensableResourceKey(), current.getCompensableXid());
				}
			} catch (RuntimeException rex) {
				errorExists = true;
				logger.error("{}| error occurred while cancelling service: {}",
						ByteUtils.byteArrayToString(this.transactionContext.getXid().getGlobalTransactionId()), current, rex);
			} finally {
				this.archive = null;
				this.positive = null;
			}
		}

		if (errorExists) {
			throw new SystemException(XAException.XAER_RMERR);
		}

	}

	private void fireRemoteParticipantCancel() throws RollbackRequiredException, SystemException {
		boolean committedExists = false;
		boolean rolledbackExists = false;
		boolean unFinishExists = false;
		boolean errorExists = false;

		for (int i = 0; i < this.resourceList.size(); i++) {
			XAResourceArchive current = this.resourceList.get(i);
			if (current.isCommitted()) {
				committedExists = true;
				continue;
			} else if (current.isRolledback()) {
				rolledbackExists = true;
				continue;
			} else if (current.isReadonly()) {
				continue;
			}

			CompensableLogger transactionLogger = this.beanFactory.getCompensableLogger();
			XidFactory xidFactory = this.beanFactory.getCompensableXidFactory();
			TransactionXid branchXid = (TransactionXid) current.getXid();
			TransactionXid globalXid = xidFactory.createGlobalXid(branchXid.getGlobalTransactionId());
			try {
				current.rollback(globalXid);
				rolledbackExists = true;

				current.setRolledback(true);
				current.setCompleted(true);

				logger.info("{}| cancel remote branch: {}", ByteUtils.byteArrayToString(branchXid.getGlobalTransactionId()),
						current.getDescriptor().getIdentifier());
			} catch (XAException xaex) {
				switch (xaex.errorCode) {
				case XAException.XA_HEURHAZ:
					unFinishExists = true;
					current.setHeuristic(true);
					logger.error("{}| error occurred while cancelling remote branch: {}",
							ByteUtils.byteArrayToString(branchXid.getGlobalTransactionId()), current, xaex);
					break;
				case XAException.XA_HEURMIX:
					committedExists = true;
					rolledbackExists = true;
					current.setCommitted(true);
					current.setRolledback(true);
					current.setHeuristic(true);
					current.setCompleted(true);
					break;
				case XAException.XA_HEURCOM:
					committedExists = true;
					current.setCommitted(true);
					current.setHeuristic(true);
					current.setCompleted(true);
					break;
				case XAException.XA_HEURRB:
					rolledbackExists = true;
					current.setRolledback(true);
					current.setHeuristic(true);
					current.setCompleted(true);
					break;
				case XAException.XA_RDONLY:
					current.setReadonly(true);
					current.setCompleted(true);
					break;
				case XAException.XAER_RMFAIL:
					unFinishExists = true;
					logger.error("{}| error occurred while cancelling remote branch: {}, the remote branch is unreachable!",
							ByteUtils.byteArrayToString(branchXid.getGlobalTransactionId()), current, xaex);
					break;
				case XAException.XAER_NOTA:
					rolledbackExists = true;
					current.setRolledback(true);
					current.setCompleted(true);
					break;
				case XAException.XAER_RMERR:
				default:
					errorExists = true;
					logger.error("{}| error occurred while cancelling remote branch: {}",
							ByteUtils.byteArrayToString(branchXid.getGlobalTransactionId()), current, xaex);
				}
			} catch (RuntimeException rex) {
				errorExists = true;
				logger.error("{}| error occurred while cancelling remote branch: {}",
						ByteUtils.byteArrayToString(branchXid.getGlobalTransactionId()), current, rex);
			} finally {
				if (current.isCompleted()) {
					transactionLogger.updateParticipant(current);
				}
			}
		}

		if (committedExists && rolledbackExists) {
			throw new SystemException(XAException.XA_HEURMIX);
		} else if (unFinishExists) {
			throw new RollbackRequiredException();
		} else if (errorExists) {
			throw new SystemException(XAException.XAER_RMERR);
		} else if (committedExists) {
			throw new SystemException(XAException.XA_HEURCOM);
		}
		// else if (rolledbackExists == false) { throw new SystemException(XAException.XA_RDONLY); }

	}

	public boolean enlistResource(XAResource xaRes) throws RollbackException, IllegalStateException, SystemException {
		if (this.transactionStatus == Status.STATUS_MARKED_ROLLBACK) {
			throw new RollbackException();
		} else if (this.transactionStatus != Status.STATUS_ACTIVE) {
			throw new IllegalStateException();
		}

		CompensableLogger compensableLogger = this.beanFactory.getCompensableLogger();
		if (RemoteResourceDescriptor.class.isInstance(xaRes) == false) {
			throw new SystemException("Invalid resource!");
		}

		RemoteResourceDescriptor descriptor = (RemoteResourceDescriptor) xaRes;
		try {
			this.checkRemoteResourceDescriptor(descriptor);
		} catch (IllegalStateException error) {
			logger.warn("Endpoint {} can not be its own remote branch!", descriptor.getIdentifier());
			return false;
		}

		XidFactory xidFactory = this.beanFactory.getCompensableXidFactory();
		TransactionXid globalXid = this.transactionContext.getXid();
		TransactionXid branchXid = xidFactory.createBranchXid(globalXid);
		try {
			descriptor.start(branchXid, XAResource.TMNOFLAGS);
		} catch (XAException ex) {
			throw new RollbackException(); // should never happen
		}

		RemoteSvc remoteSvc = descriptor.getRemoteSvc();
		XAResourceArchive resourceArchive = this.resourceMap.get(remoteSvc);
		if (resourceArchive == null) {
			resourceArchive = new XAResourceArchive();
			resourceArchive.setXid(branchXid);
			resourceArchive.setDescriptor(descriptor);
			this.resourceList.add(resourceArchive);
			this.resourceMap.put(remoteSvc, resourceArchive);

			compensableLogger.createParticipant(resourceArchive);

			logger.info("{}| enlist remote resource: {}." //
					, ByteUtils.byteArrayToString(globalXid.getGlobalTransactionId()), descriptor.getIdentifier());

			return true;
		} else if (this.transactionContext.isStatefully()) {
			XAResourceDescriptor xaResource = resourceArchive.getDescriptor();
			RemoteNode oldNode = CommonUtils.getRemoteNode(xaResource.getIdentifier());
			RemoteNode newNode = CommonUtils.getRemoteNode(descriptor.getIdentifier());
			boolean nodeEquals = oldNode == null && newNode == null ? false
					: (oldNode == null ? newNode.equals(oldNode) : oldNode.equals(newNode));
			if (nodeEquals) {
				return false;
			}
			throw new SystemException(XAException.XAER_PROTO);
		} else {
			return false;
		}
	}

	public boolean delistResource(XAResource xaRes, int flag) throws IllegalStateException, SystemException {
		CompensableLogger compensableLogger = this.beanFactory.getCompensableLogger();

		if (RemoteResourceDescriptor.class.isInstance(xaRes)) {
			RemoteResourceDescriptor descriptor = (RemoteResourceDescriptor) xaRes;
			try {
				this.checkRemoteResourceDescriptor(descriptor);
			} catch (IllegalStateException error) {
				logger.debug("Endpoint {} can not be its own remote branch!", descriptor.getIdentifier());
				return true;
			}

			if (flag == XAResource.TMFAIL) {
				RemoteSvc remoteSvc = descriptor.getRemoteSvc();

				XAResourceArchive archive = this.resourceMap.get(remoteSvc);
				if (archive != null) {
					this.resourceList.remove(archive);
				} // end-if (archive != null)

				this.resourceMap.remove(remoteSvc);

				compensableLogger.updateTransaction(this.getTransactionArchive());
			} // end-if (flag == XAResource.TMFAIL)
		} // end-if (RemoteResourceDescriptor.class.isInstance(xaRes))

		return true;
	}

	private void checkRemoteResourceDescriptor(RemoteResourceDescriptor descriptor) throws IllegalStateException {
		RemoteCoordinator transactionCoordinator = (RemoteCoordinator) this.beanFactory.getCompensableNativeParticipant();

		RemoteSvc nativeSvc = CommonUtils.getRemoteSvc(transactionCoordinator.getIdentifier());
		RemoteSvc parentSvc = CommonUtils.getRemoteSvc(String.valueOf(this.transactionContext.getPropagatedBy()));
		RemoteSvc remoteSvc = descriptor.getRemoteSvc();

		boolean nativeFlag = StringUtils.equalsIgnoreCase(remoteSvc.getServiceKey(), nativeSvc.getServiceKey());
		boolean parentFlag = StringUtils.equalsIgnoreCase(remoteSvc.getServiceKey(), parentSvc.getServiceKey());
		if (nativeFlag || parentFlag) {
			throw new IllegalStateException("Endpoint can not be its own remote branch!");
		} // end-if (nativeFlag || parentFlag)
	}

	public void resume() throws SystemException {
	}

	public void suspend() throws SystemException {
	}

	public synchronized void registerCompensable(CompensableInvocation invocation) {
		XidFactory transactionXidFactory = this.beanFactory.getTransactionXidFactory();
		CompensableLogger compensableLogger = this.beanFactory.getCompensableLogger();

		Transaction transaction = (Transaction) this.getTransactionalExtra();
		org.bytesoft.transaction.TransactionContext transactionContext = transaction.getTransactionContext();
		TransactionXid transactionXid = transactionContext.getXid();

		invocation.setEnlisted(true);

		CompensableArchive compensableArchive = new CompensableArchive();
		TransactionXid globalXid = transactionXidFactory
				.createGlobalXid(this.transactionContext.getXid().getGlobalTransactionId());
		TransactionXid branchXid = transactionXidFactory.createBranchXid(globalXid);
		compensableArchive.setIdentifier(branchXid);

		compensableArchive.setCompensable(invocation);

		this.archiveList.add(compensableArchive);

		List<CompensableArchive> archiveList = this.xidToArchivesMap.get(transactionXid);
		if (archiveList == null) {
			archiveList = new ArrayList<CompensableArchive>();
			archiveList.add(compensableArchive);
			this.xidToArchivesMap.put(transactionXid, archiveList);
		} else {
			archiveList.add(compensableArchive);
		}

		TransactionBranch branch = this.xidToBranchMap.get(transactionXid);
		if (branch != null) {
			compensableArchive.setTransactionResourceKey(branch.resourceKey);
			compensableArchive.setTransactionXid(branch.branchXid);

			TransactionXid gxid = transactionXidFactory.createGlobalXid();
			TransactionXid bxid = transactionXidFactory.createBranchXid(gxid, branch.branchXid.getGlobalTransactionId());
			compensableArchive.setCompensableXid(bxid);

			compensableLogger.createCompensable(compensableArchive);
		}

		logger.info("{}| register compensable service: {}.",
				ByteUtils.byteArrayToString(this.transactionContext.getXid().getGlobalTransactionId()),
				ByteUtils.byteArrayToString(compensableArchive.getIdentifier().getGlobalTransactionId()));
	}

	public void completeCompensable(CompensableInvocation invocation) {
	}

	public void registerSynchronization(Synchronization sync) throws RollbackException, IllegalStateException, SystemException {
	}

	public void registerTransactionListener(TransactionListener listener) {
	}

	public void registerTransactionResourceListener(TransactionResourceListener listener) {
	}

	public synchronized void onEnlistResource(Xid xid, XAResource xares) {
		XAResourceDescriptor descriptor = null;
		if (XAResourceArchive.class.isInstance(xares)) {
			descriptor = ((XAResourceArchive) xares).getDescriptor();
		} else if (XAResourceDescriptor.class.isInstance(xares)) {
			descriptor = (XAResourceDescriptor) xares;
		}

		if (this.transactionContext.isCompensating()) {
			this.onCompletionPhaseEnlistResource(xid, descriptor);
		} else {
			this.onInvocationPhaseEnlistResource(xid, descriptor);
		}
	}

	private void onInvocationPhaseEnlistResource(Xid xid, XAResourceDescriptor descriptor) {
		XidFactory transactionXidFactory = this.beanFactory.getTransactionXidFactory();
		CompensableLogger compensableLogger = this.beanFactory.getCompensableLogger();

		TransactionXid transactionXid = transactionXidFactory.createGlobalXid(xid.getGlobalTransactionId());

		String resourceKey = descriptor == null ? null : descriptor.getIdentifier();

		TransactionBranch branch = new TransactionBranch();
		branch.branchXid = (TransactionXid) xid;
		branch.resourceKey = resourceKey;
		this.xidToBranchMap.put(transactionXid, branch);

		List<CompensableArchive> archiveList = this.xidToArchivesMap.get(transactionXid);

		for (int i = 0; archiveList != null && i < archiveList.size(); i++) {
			CompensableArchive compensableArchive = archiveList.get(i);
			compensableArchive.setTransactionXid((TransactionXid) xid);
			compensableArchive.setTransactionResourceKey(resourceKey);

			TransactionXid globalXid = transactionXidFactory.createGlobalXid();
			TransactionXid branchXid = transactionXidFactory.createBranchXid(globalXid, xid.getGlobalTransactionId());
			compensableArchive.setCompensableXid(branchXid);

			compensableLogger.createCompensable(compensableArchive);
		}
	}

	private void onCompletionPhaseEnlistResource(Xid actualXid, XAResourceDescriptor descriptor) {
		Xid expectXid = this.archive == null ? null : this.archive.getCompensableXid();
		// byte[] expectKey = expectXid == null ? null : expectXid.getBranchQualifier();
		// byte[] actualKey = actualXid.getGlobalTransactionId();
		if (CommonUtils.equals(expectXid, actualXid) == false) {
			// enlist by the try operation, and current tx is rollingback/committing.
			throw new IllegalStateException("Illegal state: maybe the try phase operation has timed out.!");
		} // end-if (CommonUtils.equals(expectXid, actualXid) == false)

		String resourceKey = descriptor == null ? null : descriptor.getIdentifier();
		// this.archive.setCompensableXid(xid); // preset the compensable-xid.
		this.archive.setCompensableResourceKey(resourceKey);
		this.beanFactory.getCompensableLogger().updateCompensable(this.archive);
	}

	public void onDelistResource(Xid transactionXid, XAResource xares) {
	}

	public void onCommitSuccess(TransactionXid xid) {
		if (this.transactionContext.isCompensating()) {
			this.onCompletionPhaseCommitSuccess(xid);
		} else {
			this.onInvocationPhaseCommitSuccess(xid);
		}
	}

	private void onInvocationPhaseCommitSuccess(Xid xid) {
		if (this.transactionContext.isCoordinator() && this.transactionContext.isPropagated() == false
				&& this.transactionContext.getPropagationLevel() == 0) {
			this.onInvocationPhaseCoordinatorCommitSuccess(xid);
		} else {
			this.onInvocationPhaseParticipantCommitSuccess(xid);
		}
	}

	private void onInvocationPhaseCoordinatorCommitSuccess(Xid xid) {
		List<CompensableArchive> archiveList = this.xidToArchivesMap.get(xid);
		for (Iterator<CompensableArchive> itr = (archiveList == null) ? null : archiveList.iterator(); itr != null
				&& itr.hasNext();) {
			CompensableArchive compensableArchive = itr.next();
			itr.remove(); // remove
			compensableArchive.setTried(true);
			// compensableLogger.updateCompensable(compensableArchive);

			logger.info("{}| try: identifier= {}, resourceKey= {}, resourceXid= {}.",
					ByteUtils.byteArrayToString(transactionContext.getXid().getGlobalTransactionId()),
					ByteUtils.byteArrayToString(compensableArchive.getIdentifier().getGlobalTransactionId()),
					compensableArchive.getTransactionResourceKey(), compensableArchive.getTransactionXid());
		}

		TransactionArchive transactionArchive = this.getTransactionArchive();
		transactionArchive.setCompensableStatus(Status.STATUS_COMMITTING);
		this.beanFactory.getCompensableLogger().updateTransaction(transactionArchive);

		logger.info("{}| try completed.", ByteUtils.byteArrayToString(transactionContext.getXid().getGlobalTransactionId()));
	}

	private void onInvocationPhaseParticipantCommitSuccess(Xid xid) {
		CompensableLogger compensableLogger = this.beanFactory.getCompensableLogger();
		List<CompensableArchive> archiveList = this.xidToArchivesMap.get(xid);
		for (Iterator<CompensableArchive> itr = (archiveList == null) ? null : archiveList.iterator(); itr != null
				&& itr.hasNext();) {
			CompensableArchive compensableArchive = itr.next();
			itr.remove(); // remove
			compensableArchive.setTried(true);
			compensableLogger.updateCompensable(compensableArchive);

			logger.info("{}| try: identifier= {}, resourceKey= {}, resourceXid= {}.",
					ByteUtils.byteArrayToString(transactionContext.getXid().getGlobalTransactionId()),
					ByteUtils.byteArrayToString(compensableArchive.getIdentifier().getGlobalTransactionId()),
					compensableArchive.getTransactionResourceKey(), compensableArchive.getTransactionXid());
		}
	}

	private void onCompletionPhaseCommitSuccess(Xid actualXid) {
		Xid expectXid = this.archive == null ? null : this.archive.getCompensableXid();
		byte[] expectKey = expectXid == null ? null : expectXid.getGlobalTransactionId();
		byte[] actualKey = actualXid.getGlobalTransactionId();
		if (Arrays.equals(expectKey, actualKey) == false) {
			// this.onInvocationPhaseParticipantCommitSuccess(actualXid);
			throw new IllegalStateException("Illegal state: maybe the try phase operation has timed out.!");
		} // end-if (CommonUtils.equals(expectXid, actualXid) == false)

		if (this.positive == null) {
			this.beanFactory.getCompensableLogger().updateCompensable(this.archive);
			return;
		}

		if (this.positive) {
			logger.info("{}| confirm: identifier= {}, resourceKey= {}, resourceXid= {}.",
					ByteUtils.byteArrayToString(transactionContext.getXid().getGlobalTransactionId()),
					ByteUtils.byteArrayToString(this.archive.getIdentifier().getGlobalTransactionId()),
					this.archive.getCompensableResourceKey(), this.archive.getCompensableXid());

			this.archive.setConfirmed(true);
		} else {
			logger.info("{}| cancel: identifier= {}, resourceKey= {}, resourceXid= {}.",
					ByteUtils.byteArrayToString(transactionContext.getXid().getGlobalTransactionId()),
					ByteUtils.byteArrayToString(this.archive.getIdentifier().getGlobalTransactionId()),
					this.archive.getCompensableResourceKey(), this.archive.getCompensableXid());

			this.archive.setCancelled(true);
		}

		this.beanFactory.getCompensableLogger().updateCompensable(this.archive);
	}

	public void recoverIfNecessary() throws SystemException {
		if (this.transactionContext.isRecoveried()) {
			this.recover(); // recover transaction status
		}
	}

	public synchronized void recover() throws SystemException {
		if (this.transactionStatus == Status.STATUS_PREPARED //
				|| this.transactionStatus == Status.STATUS_COMMITTING) {
			this.recoverNativeResource(true);
			this.recoverRemoteResource(true);
		} else if (this.transactionStatus == Status.STATUS_PREPARING //
				|| this.transactionStatus == Status.STATUS_ROLLING_BACK) {
			this.recoverNativeResource(false);
			this.recoverRemoteResource(false);
		}
	}

	private void recoverNativeResource(boolean positiveFlag) throws SystemException {
		XAResourceDeserializer resourceDeserializer = this.beanFactory.getResourceDeserializer();
		CompensableLogger compensableLogger = this.beanFactory.getCompensableLogger();

		boolean errorExists = false;
		for (int i = this.archiveList.size() - 1; i >= 0; i--) {
			CompensableArchive current = this.archiveList.get(i);
			String identifier = current.getCompensableResourceKey();

			if (StringUtils.isBlank(identifier)) {
				continue;
			} else if (current.isConfirmed()) {
				continue;
			} else if (current.isCancelled()) {
				continue;
			} else if (current.isTried() == false) {
				logger.info("{}| the try operation is rolled back, so the cancel may be ignored, service: {}.",
						ByteUtils.byteArrayToString(transactionContext.getXid().getGlobalTransactionId()),
						ByteUtils.byteArrayToString(current.getIdentifier().getGlobalTransactionId()));
				continue;
			}

			try {
				try {
					XAResourceDescriptor descriptor = resourceDeserializer.deserialize(identifier);
					RecoveredResource resource = (RecoveredResource) descriptor.getDelegate();
					resource.recoverable(current.getCompensableXid());

					if (positiveFlag) {
						current.setConfirmed(true);
					} else {
						current.setCancelled(true);
					}
					compensableLogger.updateCompensable(current);
				} catch (XAException xaex) {
					switch (xaex.errorCode) {
					case XAException.XAER_NOTA:
						break;
					case XAException.XAER_RMERR:
						logger.warn(
								"The database table 'bytejta' cannot found, the status of the current branch transaction is unknown!");
						break;
					case XAException.XAER_RMFAIL:
						errorExists = true;
						logger.error("{}| error occurred while recovering the branch transaction service: {}",
								ByteUtils.byteArrayToString(this.transactionContext.getXid().getGlobalTransactionId()),
								ByteUtils.byteArrayToString(current.getIdentifier().getGlobalTransactionId()), xaex);
						break;
					default:
						logger.error("Illegal state, the status of the current branch transaction is unknown!", xaex);
					}
				} catch (RuntimeException rex) {
					logger.error("Illegal resources, the status of the current branch transaction is unknown!", rex);
				}
			} catch (RuntimeException rex) {
				errorExists = true;

				TransactionXid transactionXid = this.transactionContext.getXid();
				logger.error("{}| error occurred while recovering the branch transaction service: {}",
						ByteUtils.byteArrayToString(transactionXid.getGlobalTransactionId()), current, rex);
			}
		} // end-for

		if (errorExists) {
			throw new SystemException(XAException.XAER_RMERR);
		}

	}

	private void recoverRemoteResource(boolean positiveFlag) throws SystemException {
		// for (int i = 0; i < this.resourceList.size(); i++) {
		// XAResourceArchive archive = this.resourceList.get(i);
		// boolean xidExists = this.recover(archive);
		// }
	}

	// private boolean recover(XAResourceArchive archive) throws SystemException
	// {
	// boolean xidRecovered = false;
	//
	// Xid thisXid = archive.getXid();
	// byte[] thisGlobalTransactionId = thisXid.getGlobalTransactionId();
	// try {
	// Xid[] array = archive.recover(XAResource.TMSTARTRSCAN |
	// XAResource.TMENDRSCAN);
	// for (int j = 0; xidRecovered == false && array != null && j <
	// array.length; j++) {
	// Xid thatXid = array[j];
	// byte[] thatGlobalTransactionId = thatXid.getGlobalTransactionId();
	// boolean formatIdEquals = thisXid.getFormatId() == thatXid.getFormatId();
	// boolean transactionIdEquals = Arrays.equals(thisGlobalTransactionId,
	// thatGlobalTransactionId);
	// xidRecovered = formatIdEquals && transactionIdEquals;
	// }
	// } catch (Exception ex) {
	// TransactionXid globalXid = this.transactionContext.getXid();
	// logger.error("[{}] recover-resource failed. branch= {}",
	// ByteUtils.byteArrayToString(globalXid.getGlobalTransactionId()),
	// ByteUtils.byteArrayToString(globalXid.getBranchQualifier()), ex);
	// throw new SystemException();
	// }
	//
	// archive.setRecovered(true);
	//
	// return xidRecovered;
	// }

	public synchronized void forgetQuietly() {
		TransactionXid xid = this.transactionContext.getXid();
		try {
			this.forget();
		} catch (SystemException ex) {
			logger.error("Error occurred while forgetting transaction: {}",
					ByteUtils.byteArrayToInt(xid.getGlobalTransactionId()), ex);
		} catch (RuntimeException ex) {
			logger.error("Error occurred while forgetting transaction: {}",
					ByteUtils.byteArrayToInt(xid.getGlobalTransactionId()), ex);
		}
	}

	public synchronized void forget() throws SystemException {
		LocalResourceCleaner resourceCleaner = this.beanFactory.getLocalResourceCleaner();
		boolean success = true;

		Map<Xid, String> xidMap = new HashMap<Xid, String>();
		for (int i = 0; i < this.archiveList.size(); i++) {
			CompensableArchive current = this.archiveList.get(i);
			Xid transactionXid = current.getTransactionXid();
			Xid compensableXid = current.getCompensableXid();
			if (transactionXid != null && current.isTried()) {
				xidMap.put(transactionXid, current.getTransactionResourceKey());
			}
			if (compensableXid != null && (current.isConfirmed() || current.isCancelled())) {
				xidMap.put(compensableXid, current.getCompensableResourceKey());
			}
		}

		for (Iterator<Map.Entry<Xid, String>> itr = xidMap.entrySet().iterator(); itr.hasNext();) {
			Map.Entry<Xid, String> entry = itr.next();
			Xid xid = entry.getKey();
			String resource = entry.getValue();
			if (StringUtils.isBlank(resource)) {
				continue;
			}

			try {
				resourceCleaner.forget(xid, resource);
			} catch (RuntimeException rex) {
				success = false;
				logger.error("forget-transaction: error occurred while forgetting xid: {}", xid, rex);
			}
		}

		for (int i = 0; i < this.resourceList.size(); i++) {
			XAResourceArchive current = this.resourceList.get(i);

			if (current.isCompleted()) /* current.isHeuristic() */ {
				continue; // ignore
			}

			XidFactory xidFactory = this.beanFactory.getCompensableXidFactory();
			TransactionXid branchXid = (TransactionXid) current.getXid();
			TransactionXid globalXid = xidFactory.createGlobalXid(branchXid.getGlobalTransactionId());
			try {
				current.forget(globalXid);
			} catch (XAException ex) {
				switch (ex.errorCode) {
				case XAException.XAER_NOTA:
					break;
				default:
					success = false;
					logger.error("forget-transaction: error occurred while forgetting branch: {}", branchXid, ex);
				}
			} catch (RuntimeException rex) {
				success = false;
				logger.error("forget-transaction: error occurred while forgetting branch: {}", branchXid, rex);
			}
		}

		if (success) {
			CompensableLogger compensableLogger = this.beanFactory.getCompensableLogger();
			TransactionRepository compensableRepository = this.beanFactory.getCompensableRepository();

			compensableLogger.deleteTransaction(this.getTransactionArchive());

			compensableRepository.removeErrorTransaction(this.transactionContext.getXid());
			compensableRepository.removeTransaction(this.transactionContext.getXid());

			logger.info("{}| forget transaction.",
					ByteUtils.byteArrayToString(this.transactionContext.getXid().getGlobalTransactionId()));
		} else {
			throw new SystemException(XAException.XAER_RMERR);
		}

	}

	public XAResourceDescriptor getResourceDescriptor(String beanId) {
		Transaction transaction = this.getTransaction();
		return transaction.getResourceDescriptor(beanId);
	}

	public XAResourceDescriptor getRemoteCoordinator(RemoteSvc remoteSvc) {
		Transaction transaction = this.getTransaction();

		XAResourceDescriptor descriptor = null;
		if (transaction != null) {
			descriptor = transaction.getRemoteCoordinator(remoteSvc);
		}

		if (descriptor == null) {
			XAResourceArchive archive = this.resourceMap.get(remoteSvc);
			descriptor = archive == null ? descriptor : archive.getDescriptor();
		}

		return descriptor;
	}

	public XAResourceDescriptor getRemoteCoordinator(String application) {
		RemoteSvc remoteSvc = new RemoteSvc();
		remoteSvc.setServiceKey(application);
		XAResourceArchive archive = this.resourceMap.get(remoteSvc);
		return archive == null ? null : archive.getDescriptor();
	}

	public boolean lock(boolean tryFlag) {
		if (tryFlag) {
			boolean locked = this.lock.tryLock();
			if (locked) {
				this.currentThread = Thread.currentThread();
			} // end-if (locked)
			return locked;
		} else {
			this.lock.lock();
			this.currentThread = Thread.currentThread();
			return true;
		}
	}

	public void release() {
		Thread current = Thread.currentThread();
		if (current == this.currentThread) {
			this.currentThread = null;
			this.lock.unlock();
		} else {
			logger.warn("Illegal thread: expect= {}, actual= {}.", this.currentThread, current);
		}
	}

	public void fireBeforeTransactionCompletion() throws RollbackRequiredException, SystemException {
	}

	public void fireBeforeTransactionCompletionQuietly() {
	}

	public void fireAfterTransactionCompletion() {
	}

	public CompensableArchive getCompensableArchive() {
		return this.archive;
	}

	/**
	 * only for recovery.
	 */
	public List<CompensableArchive> getCompensableArchiveList() {
		return this.archiveList;
	}

	/**
	 * only for recovery.
	 */
	public Map<RemoteSvc, XAResourceArchive> getParticipantArchiveMap() {
		return this.resourceMap;
	}

	/**
	 * only for recovery.
	 */
	public List<XAResourceArchive> getParticipantArchiveList() {
		return this.resourceList;
	}

	public boolean isMarkedRollbackOnly() {
		return this.transactionContext.isRollbackOnly();
	}

	private synchronized void setTransactionRollbackOnlyQuietly() {
		Transaction transactionalExtra = this.getTransaction();
		if (transactionalExtra != null) {
			transactionalExtra.setRollbackOnlyQuietly();
		}
	}

	public synchronized void setRollbackOnly() throws IllegalStateException, SystemException {
		if (this.transactionContext.isCompensating()) {
			this.setTransactionRollbackOnlyQuietly();
		} else if (this.transactionStatus == Status.STATUS_ACTIVE) {
			this.transactionStatus = Status.STATUS_MARKED_ROLLBACK;
			this.setTransactionRollbackOnlyQuietly();
			this.transactionContext.setRollbackOnly(true);
		} else if (this.transactionStatus == Status.STATUS_MARKED_ROLLBACK) {
			this.setTransactionRollbackOnlyQuietly();
			this.transactionContext.setRollbackOnly(true);
		} else {
			this.setTransactionRollbackOnlyQuietly();
		}
	}

	public synchronized void setRollbackOnlyQuietly() {
		try {
			this.setRollbackOnly();
		} catch (Exception ex) {
			logger.debug(ex.getMessage(), ex);
		}
	}

	public TransactionXid getTransactionXid() {
		if (this.transactionContext.isCompensating() == false) {
			return null;
		} else if (this.archive == null) {
			return null;
		}

		return (TransactionXid) this.archive.getCompensableXid();
	}

	public boolean isLocalTransaction() {
		throw new IllegalStateException();
	}

	public int getStatus() throws SystemException {
		return this.transactionStatus;
	}

	public int getTransactionStatus() {
		return this.transactionStatus;
	}

	public void setTransactionStatus(int status) {
		this.transactionStatus = status;
	}

	public boolean isTiming() {
		throw new IllegalStateException();
	}

	public void setTransactionTimeout(int seconds) {
		throw new IllegalStateException();
	}

	public TransactionContext getTransactionContext() {
		return this.transactionContext;
	}

	public void setBeanFactory(CompensableBeanFactory tbf) {
		this.beanFactory = tbf;
	}

	public Serializable getVariable(String key) {
		return this.variables.get(key);
	}

	public boolean isCurrentCompensableServiceTried() {
		return this.archive.isTried();
	}

	public void setVariable(String key, Serializable variable) {
		this.variables.put(key, variable);
	}

	public TransactionExtra getTransactionalExtra() {
		return this.transactionMap.get(Thread.currentThread());
	}

	public void setTransactionalExtra(TransactionExtra transactionalExtra) {
		if (transactionalExtra == null) {
			this.transactionMap.remove(Thread.currentThread());
		} else {
			this.transactionMap.put(Thread.currentThread(), (Transaction) transactionalExtra);
		}
	}

	public Transaction getTransaction() {
		return (Transaction) this.getTransactionalExtra();
	}

	public Exception getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(Exception createdAt) {
		this.createdAt = createdAt;
	}

	public int getTransactionVote() {
		return transactionVote;
	}

	public void setTransactionVote(int transactionVote) {
		this.transactionVote = transactionVote;
	}

	public Map<String, Serializable> getVariables() {
		return variables;
	}

	public void setVariables(Map<String, Serializable> variables) {
		this.variables = variables;
	}

	private static class TransactionBranch {
		public TransactionXid branchXid;
		public String resourceKey;
	}

}
