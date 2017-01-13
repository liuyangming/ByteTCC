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
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

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
import org.bytesoft.transaction.internal.TransactionException;
import org.bytesoft.transaction.supports.TransactionListener;
import org.bytesoft.transaction.supports.TransactionListenerAdapter;
import org.bytesoft.transaction.supports.TransactionResourceListener;
import org.bytesoft.transaction.supports.resource.XAResourceDescriptor;
import org.bytesoft.transaction.supports.serialize.XAResourceDeserializer;
import org.bytesoft.transaction.xa.TransactionXid;
import org.bytesoft.transaction.xa.XidFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CompensableTransactionImpl extends TransactionListenerAdapter implements CompensableTransaction {
	static final Logger logger = LoggerFactory.getLogger(CompensableTransactionImpl.class);

	private final TransactionContext transactionContext;
	private final List<CompensableArchive> archiveList = new ArrayList<CompensableArchive>();
	private final List<XAResourceArchive> resourceList = new ArrayList<XAResourceArchive>();
	private Transaction transaction;
	private CompensableBeanFactory beanFactory;

	private int transactionVote;
	private int transactionStatus = Status.STATUS_ACTIVE;
	/* current comensable-decision in confirm/cancel phase. */
	private transient Boolean positive;
	/* current compense-archive in confirm/cancel phase. */
	private transient CompensableArchive archive;

	/* current compensable-archive list in try phase. */
	private transient final List<CompensableArchive> currentArchiveList = new ArrayList<CompensableArchive>();
	private transient final Map<Xid, List<CompensableArchive>> archiveMap = new HashMap<Xid, List<CompensableArchive>>();

	private boolean participantStickyRequired;

	private Map<String, Serializable> variables = new HashMap<String, Serializable>();

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
		return transactionArchive;
	}

	public synchronized void commit() throws RollbackException, HeuristicMixedException, HeuristicRollbackException,
			SecurityException, IllegalStateException, SystemException {

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
			systemEx = new SystemException(ex.getMessage());

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
		} else {
			this.transactionStatus = Status.STATUS_COMMITTED;
			compensableLogger.updateTransaction(this.getTransactionArchive());
			logger.info("{}| compensable transaction committed!",
					ByteUtils.byteArrayToString(transactionContext.getXid().getGlobalTransactionId()));
		}

	}

	private synchronized void fireNativeParticipantConfirm() throws SystemException {
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
						ByteUtils.byteArrayToString(this.transactionContext.getXid().getGlobalTransactionId()), this.archive,
						rex);
			} finally {
				this.archive = null;
				this.positive = null;
			}
		}

		if (errorExists) {
			throw new SystemException();
		}

	}

	private synchronized void fireRemoteParticipantConfirm() throws HeuristicMixedException, HeuristicRollbackException,
			SystemException {
		boolean commitExists = false;
		boolean rollbackExists = false;
		boolean errorExists = false;

		for (int i = 0; i < this.resourceList.size(); i++) {
			XAResourceArchive current = this.resourceList.get(i);
			if (current.isCommitted()) {
				commitExists = true;
				continue;
			}

			CompensableLogger transactionLogger = this.beanFactory.getCompensableLogger();
			XidFactory xidFactory = this.beanFactory.getCompensableXidFactory();
			TransactionXid branchXid = (TransactionXid) current.getXid();
			TransactionXid globalXid = xidFactory.createGlobalXid(branchXid.getGlobalTransactionId());
			try {
				current.commit(globalXid, true);
				commitExists = true;

				current.setCommitted(true);
				current.setCompleted(true);
				transactionLogger.updateCoordinator(current);

				logger.info("{}| confirm remote branch: {}", ByteUtils.byteArrayToString(branchXid.getGlobalTransactionId()),
						current.getDescriptor().getIdentifier());
			} catch (TransactionException transactionEx) {
				// TransactionException is thrown only by CompensableServiceFilter.
				logger.warn("{}| branch({}) should be confirmed by its own coordinator.", ByteUtils.byteArrayToString(branchXid
						.getGlobalTransactionId()), current.getDescriptor().getIdentifier());
			} catch (XAException ex) {
				switch (ex.errorCode) {
				case XAException.XAER_NOTA:
					logger.warn("{}| error occurred while confirming remote branch: {}, transaction is not exists!", ByteUtils
							.byteArrayToString(branchXid.getGlobalTransactionId()), current.getDescriptor().getIdentifier());
					break;
				case XAException.XA_HEURCOM:
					commitExists = true;

					current.setCommitted(true);
					current.setHeuristic(false);
					current.setCompleted(true);
					transactionLogger.updateCoordinator(current);

					logger.info("{}| confirm remote branch: {}",
							ByteUtils.byteArrayToString(branchXid.getGlobalTransactionId()), current.getDescriptor()
									.getIdentifier());
					break;
				case XAException.XA_HEURRB:
					rollbackExists = true;

					current.setRolledback(true);
					current.setHeuristic(false);
					current.setCompleted(true);
					transactionLogger.updateCoordinator(current);

					logger.error("{}| error occurred while confirming remote branch: {}",
							ByteUtils.byteArrayToString(branchXid.getGlobalTransactionId()), this.archive, ex);
					break;
				case XAException.XA_HEURMIX:
					// should never happen
					commitExists = true;
					rollbackExists = true;

					current.setHeuristic(true);
					// current.setCommitted(true);
					// current.setRolledback(true);
					// current.setCompleted(true);
					transactionLogger.updateCoordinator(current);

					logger.error("{}| error occurred while confirming remote branch: {}",
							ByteUtils.byteArrayToString(branchXid.getGlobalTransactionId()), this.archive, ex);
					break;
				default:
					errorExists = false;
					logger.error("{}| error occurred while confirming remote branch: {}",
							ByteUtils.byteArrayToString(branchXid.getGlobalTransactionId()), this.archive, ex);
				}

			} catch (RuntimeException rex) {
				errorExists = false;
				logger.error("{}| error occurred while confirming branch: {}",
						ByteUtils.byteArrayToString(branchXid.getGlobalTransactionId()), this.archive, rex);
			}
		}

		if (commitExists && rollbackExists) {
			throw new HeuristicMixedException();
		} else if (rollbackExists) {
			throw new HeuristicRollbackException();
		} else if (errorExists) {
			throw new SystemException();
		}

	}

	public void participantPrepare() throws RollbackRequiredException, CommitRequiredException {
		throw new RuntimeException("Not supported!");
	}

	public void participantCommit() throws RollbackException, HeuristicMixedException, HeuristicRollbackException,
			SecurityException, IllegalStateException, CommitRequiredException, SystemException {
		throw new SystemException("Not supported!");
	}

	private void markCurrentBranchTransactionRollbackIfNecessary() {
		Transaction branch = this.transaction;
		if (branch != null) /* used by participant only. */{
			try {
				branch.setRollbackOnly();
			} catch (IllegalStateException ex) {
				logger.info("The local transaction is not active.", ex); // tx in try-phase has been completed already.
			} catch (SystemException ex) {
				logger.warn("The local transaction is not active.", ex); // should never happen
			} catch (RuntimeException ex) {
				logger.warn("The local transaction is not active.", ex); // should never happen
			}
		}
	}

	public synchronized void rollback() throws IllegalStateException, SystemException {
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
			systemEx = new SystemException(ex.getMessage());

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
			throw new SystemException(ex.getMessage());
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

	private synchronized void fireNativeParticipantCancel() throws SystemException {
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
					this.archive.setCancelled(true);
					logger.info("{}| cancel: identifier= {}, resourceKey= {}, resourceXid= {}.",
							ByteUtils.byteArrayToString(this.transactionContext.getXid().getGlobalTransactionId()),
							ByteUtils.byteArrayToString(current.getIdentifier().getGlobalTransactionId()),
							current.getCompensableResourceKey(), current.getCompensableXid());
				}
			} catch (RuntimeException rex) {
				errorExists = true;
				logger.error("{}| error occurred while cancelling service: {}",
						ByteUtils.byteArrayToString(this.transactionContext.getXid().getGlobalTransactionId()), this.archive,
						rex);
			} finally {
				this.archive = null;
				this.positive = null;
			}
		}

		if (errorExists) {
			throw new SystemException();
		}

	}

	private synchronized void fireRemoteParticipantCancel() throws SystemException {
		boolean errorExists = false;

		for (int i = 0; i < this.resourceList.size(); i++) {
			XAResourceArchive current = this.resourceList.get(i);
			if (current.isRolledback()) {
				continue;
			}

			CompensableLogger transactionLogger = this.beanFactory.getCompensableLogger();
			XidFactory xidFactory = this.beanFactory.getCompensableXidFactory();
			TransactionXid branchXid = (TransactionXid) current.getXid();
			TransactionXid globalXid = xidFactory.createGlobalXid(branchXid.getGlobalTransactionId());
			try {
				current.rollback(globalXid);

				current.setRolledback(true);
				current.setCompleted(true);
				transactionLogger.updateCoordinator(current);

				logger.info("{}| cancel remote branch: {}", ByteUtils.byteArrayToString(branchXid.getGlobalTransactionId()),
						current.getDescriptor().getIdentifier());
			} catch (TransactionException transactionEx) {
				// TransactionException is thrown only by CompensableServiceFilter.
				logger.warn("{}| branch({}) should be cancelled by its own coordinator.", ByteUtils.byteArrayToString(branchXid
						.getGlobalTransactionId()), current.getDescriptor().getIdentifier());
			} catch (XAException ex) {
				switch (ex.errorCode) {
				case XAException.XAER_NOTA:
					logger.warn("{}| error occurred while cancelling remote branch: {}, transaction is not exists!", ByteUtils
							.byteArrayToString(branchXid.getGlobalTransactionId()), current.getDescriptor().getIdentifier());
					break;
				default:
					errorExists = true;
					logger.error("{}| error occurred while cancelling remote branch: {}",
							ByteUtils.byteArrayToString(branchXid.getGlobalTransactionId()), current, ex);
				}

			} catch (RuntimeException rex) {
				errorExists = true;
				logger.error("{}| error occurred while cancelling remote branch: {}",
						ByteUtils.byteArrayToString(branchXid.getGlobalTransactionId()), current, rex);
			}
		}

		if (errorExists) {
			throw new SystemException();
		}

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
		XAResourceArchive resourceArchive = null;
		RemoteResourceDescriptor descriptor = (RemoteResourceDescriptor) xaRes;
		String identifier = descriptor.getIdentifier();
		for (int i = 0; i < this.resourceList.size(); i++) {
			XAResourceArchive resource = this.resourceList.get(i);
			String resourceKey = resource.getDescriptor().getIdentifier();
			if (CommonUtils.equals(identifier, resourceKey)) {
				resourceArchive = resource;
				break;
			}
		}

		if (resourceArchive == null) {
			XidFactory xidFactory = this.beanFactory.getCompensableXidFactory();
			TransactionXid globalXid = this.transactionContext.getXid();
			TransactionXid branchXid = xidFactory.createBranchXid(globalXid);
			resourceArchive = new XAResourceArchive();
			resourceArchive.setXid(branchXid);
			resourceArchive.setDescriptor(descriptor);
			this.resourceList.add(resourceArchive);

			compensableLogger.createCoordinator(resourceArchive);

			logger.info("{}| enlist remote resource: {}.", ByteUtils.byteArrayToString(globalXid.getGlobalTransactionId()),
					identifier);

			return true;
		} else {
			return false;
		}

	}

	public boolean delistResource(XAResource xaRes, int flag) throws IllegalStateException, SystemException {
		CompensableLogger compensableLogger = this.beanFactory.getCompensableLogger();

		if (flag == XAResource.TMFAIL && RemoteResourceDescriptor.class.isInstance(xaRes)) {
			RemoteResourceDescriptor descriptor = (RemoteResourceDescriptor) xaRes;
			String identifier = descriptor.getIdentifier();
			for (Iterator<XAResourceArchive> itr = this.resourceList.iterator(); itr.hasNext();) {
				XAResourceArchive resource = itr.next();
				String resourceKey = resource.getDescriptor().getIdentifier();
				if (CommonUtils.equals(identifier, resourceKey)) {
					itr.remove();
					break;
				}
			}

			compensableLogger.updateTransaction(this.getTransactionArchive());
		}

		return true;
	}

	public void resume() throws SystemException {
		org.bytesoft.transaction.TransactionContext transactionContext = this.transaction.getTransactionContext();
		TransactionXid xid = transactionContext.getXid();
		List<CompensableArchive> compensableList = this.archiveMap.remove(xid);

		this.currentArchiveList.clear();
		this.currentArchiveList.addAll(compensableList);
	}

	public void suspend() throws SystemException {
		org.bytesoft.transaction.TransactionContext transactionContext = this.transaction.getTransactionContext();
		TransactionXid xid = transactionContext.getXid();

		List<CompensableArchive> compensableList = new ArrayList<CompensableArchive>();
		compensableList.addAll(this.currentArchiveList);

		this.currentArchiveList.clear();
		this.archiveMap.put(xid, compensableList);
	}

	public void registerCompensable(CompensableInvocation invocation) {
		XidFactory xidFactory = this.beanFactory.getTransactionXidFactory();

		CompensableArchive archive = new CompensableArchive();
		TransactionXid globalXid = xidFactory.createGlobalXid(this.transactionContext.getXid().getGlobalTransactionId());
		TransactionXid branchXid = xidFactory.createBranchXid(globalXid);
		archive.setIdentifier(branchXid);

		archive.setCompensable(invocation);

		this.archiveList.add(archive);
		this.currentArchiveList.add(archive);

		logger.info("{}| register compensable service: {}.",
				ByteUtils.byteArrayToString(this.transactionContext.getXid().getGlobalTransactionId()),
				ByteUtils.byteArrayToString(archive.getIdentifier().getGlobalTransactionId()));
	}

	public void registerSynchronization(Synchronization sync) throws RollbackException, IllegalStateException, SystemException {
	}

	public void registerTransactionListener(TransactionListener listener) {
	}

	public void registerTransactionResourceListener(TransactionResourceListener listener) {
	}

	public void onEnlistResource(Xid xid, XAResource xares) {
		String resourceKey = null;
		if (XAResourceDescriptor.class.isInstance(xares)) {
			XAResourceDescriptor descriptor = (XAResourceDescriptor) xares;
			resourceKey = descriptor.getIdentifier();
		} else if (XAResourceArchive.class.isInstance(xares)) {
			XAResourceArchive resourceArchive = (XAResourceArchive) xares;
			XAResourceDescriptor descriptor = resourceArchive.getDescriptor();
			resourceKey = descriptor == null ? null : descriptor.getIdentifier();
		}

		CompensableLogger compensableLogger = this.beanFactory.getCompensableLogger();
		if (this.transactionContext.isCompensating()) {
			this.archive.setCompensableXid(xid);
			this.archive.setCompensableResourceKey(resourceKey);
			compensableLogger.updateCompensable(this.archive);
		} else {
			for (int i = 0; i < this.currentArchiveList.size(); i++) {
				CompensableArchive compensableArchive = this.currentArchiveList.get(i);
				compensableArchive.setTransactionXid(xid);
				compensableArchive.setTransactionResourceKey(resourceKey);

				compensableLogger.createCompensable(compensableArchive);
			}
		}

	}

	public void onDelistResource(Xid xid, XAResource xares) {
	}

	public void onCommitSuccess(TransactionXid xid) {
		CompensableLogger compensableLogger = this.beanFactory.getCompensableLogger();

		if (this.transactionContext.isCompensating()) {
			if (this.positive == null) {
				// ignore
			} else if (this.positive) {
				this.archive.setConfirmed(true);

				logger.info("{}| confirm: identifier= {}, resourceKey= {}, resourceXid= {}.",
						ByteUtils.byteArrayToString(transactionContext.getXid().getGlobalTransactionId()),
						ByteUtils.byteArrayToString(this.archive.getIdentifier().getGlobalTransactionId()),
						this.archive.getCompensableResourceKey(), this.archive.getCompensableXid());
			} else {
				this.archive.setCancelled(true);

				logger.info("{}| cancel: identifier= {}, resourceKey= {}, resourceXid= {}.",
						ByteUtils.byteArrayToString(transactionContext.getXid().getGlobalTransactionId()),
						ByteUtils.byteArrayToString(this.archive.getIdentifier().getGlobalTransactionId()),
						this.archive.getCompensableResourceKey(), this.archive.getCompensableXid());
			}
			compensableLogger.updateCompensable(this.archive);
		} else if (this.transactionContext.isCoordinator() && this.transactionContext.isPropagated() == false
				&& this.transactionContext.getPropagationLevel() == 0) {
			for (Iterator<CompensableArchive> itr = this.currentArchiveList.iterator(); itr.hasNext();) {
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
			compensableLogger.updateTransaction(transactionArchive);

			logger.info("{}| try completed.", ByteUtils.byteArrayToString(transactionContext.getXid().getGlobalTransactionId()));
		} else {
			for (Iterator<CompensableArchive> itr = this.currentArchiveList.iterator(); itr.hasNext();) {
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

	}

	public synchronized void recoveryCommit() throws CommitRequiredException, SystemException {
		CompensableLogger compensableLogger = this.beanFactory.getCompensableLogger();

		this.transactionContext.setCompensating(true);
		this.transactionStatus = Status.STATUS_COMMITTING;
		compensableLogger.updateTransaction(this.getTransactionArchive());

		SystemException systemEx = null;
		try {
			this.fireNativeParticipantRecoveryConfirm();
		} catch (SystemException ex) {
			systemEx = ex;

			logger.info("{}| recovery-confirm native branchs failed!",
					ByteUtils.byteArrayToString(this.transactionContext.getXid().getGlobalTransactionId()), ex);
		} catch (RuntimeException ex) {
			systemEx = new SystemException(ex.getMessage());

			logger.info("{}| recovery-confirm native branchs failed!",
					ByteUtils.byteArrayToString(this.transactionContext.getXid().getGlobalTransactionId()), ex);
		}

		try {
			this.fireRemoteParticipantRecoveryConfirm();
		} catch (HeuristicMixedException ex) {
			logger.info("{}| recovery-confirm remote branchs failed!",
					ByteUtils.byteArrayToString(this.transactionContext.getXid().getGlobalTransactionId()), ex);
			throw new SystemException(ex.getMessage());
		} catch (HeuristicRollbackException ex) {
			logger.info("{}| recovery-confirm remote branchs failed!",
					ByteUtils.byteArrayToString(this.transactionContext.getXid().getGlobalTransactionId()), ex);
			throw new SystemException(ex.getMessage());
		} catch (SystemException ex) {
			logger.info("{}| recovery-confirm remote branchs failed!",
					ByteUtils.byteArrayToString(this.transactionContext.getXid().getGlobalTransactionId()), ex);
			throw ex;
		} catch (RuntimeException ex) {
			logger.info("{}| recovery-confirm remote branchs failed!",
					ByteUtils.byteArrayToString(this.transactionContext.getXid().getGlobalTransactionId()), ex);
			throw new SystemException(ex.getMessage());
		}

		if (systemEx != null) {
			throw systemEx;
		} else {
			this.transactionStatus = Status.STATUS_COMMITTED;
			compensableLogger.updateTransaction(this.getTransactionArchive());
			logger.info("{}| compensable transaction recovery committed!",
					ByteUtils.byteArrayToString(transactionContext.getXid().getGlobalTransactionId()));
		}

	}

	private synchronized void fireNativeParticipantRecoveryConfirm() throws SystemException {
		if (this.transactionContext.isRecoveried()) {
			this.fireNativeParticipantRecoveryConfirmForRecoveredTransaction();
		} else {
			this.fireNativeParticipantConfirm();
		}
	}

	private synchronized void fireNativeParticipantRecoveryConfirmForRecoveredTransaction() throws SystemException {
		boolean errorExists = false;

		ContainerContext container = this.beanFactory.getContainerContext();
		XAResourceDeserializer resourceDeserializer = this.beanFactory.getResourceDeserializer();
		CompensableLogger compensableLogger = this.beanFactory.getCompensableLogger();

		for (int i = this.archiveList.size() - 1; i >= 0; i--) {
			CompensableArchive current = this.archiveList.get(i);

			if (current.isConfirmed()) {
				continue;
			}

			TransactionXid compensableXid = (TransactionXid) current.getCompensableXid();
			try {
				this.positive = true;
				this.archive = current;

				String identifier = current.getCompensableResourceKey();
				if (StringUtils.isBlank(identifier)) {
					logger.warn("There is no valid resource participated in the current branch transaction!");
				} else {
					try {
						XAResource xares = resourceDeserializer.deserialize(identifier);
						RecoveredResource resource = (RecoveredResource) xares;
						resource.recoverable(compensableXid);
						current.setConfirmed(true);
						compensableLogger.updateCompensable(current);
					} catch (XAException xaex) {
						switch (xaex.errorCode) {
						case XAException.XAER_NOTA:
							break;
						case XAException.XAER_RMERR:
							logger.warn("The database table 'bytejta' cannot found, the status of the current branch transaction is unknown!");
							break;
						case XAException.XAER_RMFAIL:
							errorExists = true;
							logger.error("{}| error occurred while recovering the branch transaction service: {}",
									ByteUtils.byteArrayToString(this.transactionContext.getXid().getGlobalTransactionId()),
									ByteUtils.byteArrayToString(current.getIdentifier().getGlobalTransactionId()), xaex);
							break;
						default:
							logger.error("Illegal state, the status of the current branch transaction is unknown!");
						}
					} catch (RuntimeException rex) {
						logger.error("Illegal resources, the status of the current branch transaction is unknown!");
					}
				}

				CompensableInvocation invocation = current.getCompensable();
				if (current.isConfirmed()) {
					continue;
				} else if (invocation == null) {
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
							ByteUtils.byteArrayToString(this.transactionContext.getXid().getGlobalTransactionId()),
							ByteUtils.byteArrayToString(current.getIdentifier().getGlobalTransactionId()),
							current.getCompensableResourceKey(), current.getCompensableXid());
				}
			} catch (RuntimeException rex) {
				errorExists = true;

				TransactionXid transactionXid = this.transactionContext.getXid();
				logger.error("{}| error occurred while confirming service: {}",
						ByteUtils.byteArrayToString(transactionXid.getGlobalTransactionId()), this.archive, rex);
			} finally {
				this.archive = null;
				this.positive = null;
			}

		}

		if (errorExists) {
			throw new SystemException();
		}

	}

	private synchronized void fireRemoteParticipantRecoveryConfirm() throws HeuristicMixedException,
			HeuristicRollbackException, SystemException {
		boolean commitExists = false;
		boolean rollbackExists = false;
		boolean errorExists = false;

		for (int i = 0; i < this.resourceList.size(); i++) {
			XAResourceArchive current = this.resourceList.get(i);
			if (current.isCommitted()) {
				commitExists = true;
				continue;
			}

			CompensableLogger transactionLogger = this.beanFactory.getCompensableLogger();
			XidFactory xidFactory = this.beanFactory.getCompensableXidFactory();
			TransactionXid branchXid = (TransactionXid) current.getXid();
			TransactionXid globalXid = xidFactory.createGlobalXid(branchXid.getGlobalTransactionId());
			try {
				current.recoveryCommit(globalXid);
				commitExists = true;

				current.setCommitted(true);
				current.setCompleted(true);
				transactionLogger.updateCoordinator(current);

				logger.info("{}| recovery-confirm remote branch: {}", ByteUtils.byteArrayToString(branchXid
						.getGlobalTransactionId()), current.getDescriptor().getIdentifier());
			} catch (TransactionException transactionEx) {
				// TransactionException is thrown only by CompensableServiceFilter.
				logger.warn("{}| branch({}) should be confirmed by its own coordinator.", ByteUtils.byteArrayToString(branchXid
						.getGlobalTransactionId()), current.getDescriptor().getIdentifier());
			} catch (XAException ex) {
				switch (ex.errorCode) {
				case XAException.XAER_NOTA:
					logger.warn("{}| error occurred while confirming remote branch: {}, transaction is not exists!", ByteUtils
							.byteArrayToString(branchXid.getGlobalTransactionId()), current.getDescriptor().getIdentifier());
					break;
				case XAException.XA_HEURCOM:
					current.setCommitted(true);
					current.setHeuristic(false);
					current.setCompleted(true);
					transactionLogger.updateCoordinator(current);

					logger.info("{}| recovery-confirm remote branch: {}", ByteUtils.byteArrayToString(branchXid
							.getGlobalTransactionId()), current.getDescriptor().getIdentifier());
					break;
				case XAException.XA_HEURRB:
					rollbackExists = true;

					current.setRolledback(true);
					current.setHeuristic(false);
					current.setCompleted(true);
					transactionLogger.updateCoordinator(current);

					logger.error("{}| error occurred while confirming remote branch: {}",
							ByteUtils.byteArrayToString(branchXid.getGlobalTransactionId()), current, ex);
					break;
				case XAException.XA_HEURMIX:
					commitExists = true;
					rollbackExists = true;

					current.setHeuristic(true);
					// current.setCommitted(true);
					// current.setRolledback(true);
					// current.setCompleted(true);
					transactionLogger.updateCoordinator(current);

					logger.error("{}| error occurred while confirming remote branch: {}",
							ByteUtils.byteArrayToString(branchXid.getGlobalTransactionId()), current, ex);
					break;
				default:
					errorExists = false;
					logger.error("{}| error occurred while confirming remote branch: {}",
							ByteUtils.byteArrayToString(branchXid.getGlobalTransactionId()), current, ex);
				}

			} catch (RuntimeException rex) {
				errorExists = false;
				logger.error("{}| error occurred while confirming remote branch: {}",
						ByteUtils.byteArrayToString(branchXid.getGlobalTransactionId()), current, rex);
			}
		}

		if (commitExists && rollbackExists) {
			throw new HeuristicMixedException();
		} else if (rollbackExists) {
			throw new HeuristicRollbackException();
		} else if (errorExists) {
			throw new SystemException();
		}

	}

	public synchronized void recoveryRollback() throws RollbackRequiredException, SystemException {
		CompensableLogger compensableLogger = this.beanFactory.getCompensableLogger();

		this.transactionStatus = Status.STATUS_ROLLING_BACK;
		this.transactionContext.setCompensating(true);
		compensableLogger.updateTransaction(this.getTransactionArchive());

		SystemException systemEx = null;
		try {
			this.fireNativeParticipantRecoveryCancel();
		} catch (SystemException ex) {
			systemEx = ex;

			logger.info("{}| recovery-cancel native branchs failed!",
					ByteUtils.byteArrayToString(this.transactionContext.getXid().getGlobalTransactionId()), ex);
		} catch (RuntimeException ex) {
			systemEx = new SystemException(ex.getMessage());

			logger.info("{}| recovery-cancel native branchs failed!",
					ByteUtils.byteArrayToString(this.transactionContext.getXid().getGlobalTransactionId()), ex);
		}

		try {
			this.fireRemoteParticipantRecoveryCancel();
		} catch (SystemException ex) {
			logger.info("{}| recovery-cancel remote branchs failed!",
					ByteUtils.byteArrayToString(this.transactionContext.getXid().getGlobalTransactionId()), ex);
			throw ex;
		} catch (RuntimeException ex) {
			logger.info("{}| recovery-cancel remote branchs failed!",
					ByteUtils.byteArrayToString(this.transactionContext.getXid().getGlobalTransactionId()), ex);
			throw new SystemException(ex.getMessage());
		}

		if (systemEx != null) {
			throw systemEx;
		} else {
			this.transactionStatus = Status.STATUS_ROLLEDBACK;
			compensableLogger.updateTransaction(this.getTransactionArchive());
			logger.info("{}| compensable transaction recovery rolled back!",
					ByteUtils.byteArrayToString(transactionContext.getXid().getGlobalTransactionId()));
		}

	}

	private synchronized void fireNativeParticipantRecoveryCancel() throws SystemException {
		if (this.transactionContext.isRecoveried()) {
			this.fireNativeParticipantRecoveryCancelForRecoveredTransaction();
		} else {
			this.fireNativeParticipantCancel();
		}
	}

	private synchronized void fireNativeParticipantRecoveryCancelForRecoveredTransaction() throws SystemException {
		boolean errorExists = false;

		ContainerContext container = this.beanFactory.getContainerContext();
		XAResourceDeserializer resourceDeserializer = this.beanFactory.getResourceDeserializer();
		CompensableLogger compensableLogger = this.beanFactory.getCompensableLogger();

		for (int i = this.archiveList.size() - 1; i >= 0; i--) {
			CompensableArchive current = this.archiveList.get(i);

			if (current.isTried() == false) {
				logger.info(
						"{}| the operation in try phase is rolled back, so the cancel operation is ignored, compensable service: {}.",
						ByteUtils.byteArrayToString(transactionContext.getXid().getGlobalTransactionId()),
						ByteUtils.byteArrayToString(current.getIdentifier().getGlobalTransactionId()));
				continue;
			} else if (current.isCancelled()) {
				continue;
			}

			TransactionXid compensableXid = (TransactionXid) current.getCompensableXid();
			try {
				this.positive = false;
				this.archive = current;

				String identifier = current.getCompensableResourceKey();
				if (StringUtils.isBlank(identifier)) {
					logger.warn("There is no valid resource participated in the current branch transaction!");
				} else {
					try {
						XAResource xares = resourceDeserializer.deserialize(identifier);
						RecoveredResource resource = (RecoveredResource) xares;
						resource.recoverable(compensableXid);
						current.setCancelled(true);
						compensableLogger.updateCompensable(current);
					} catch (XAException xaex) {
						switch (xaex.errorCode) {
						case XAException.XAER_NOTA:
							break;
						case XAException.XAER_RMERR:
							logger.warn("The database table 'bytejta' cannot found, the status of the current branch transaction is unknown!");
							break;
						case XAException.XAER_RMFAIL:
							errorExists = true;
							logger.error("{}| error occurred while recovering the branch transaction service: {}",
									ByteUtils.byteArrayToString(this.transactionContext.getXid().getGlobalTransactionId()),
									ByteUtils.byteArrayToString(current.getIdentifier().getGlobalTransactionId()), xaex);
							break;
						default:
							logger.error("Illegal state, the status of the current branch transaction is unknown!");
						}
					} catch (RuntimeException rex) {
						logger.error("Illegal resources, the status of the current branch transaction is unknown!");
					}
				}

				CompensableInvocation invocation = current.getCompensable();
				if (current.isCancelled()) {
					continue;
				} else if (invocation == null) {
					errorExists = true;
					logger.error(
							"{}| error occurred while cancelling service: {}, please check whether the params of method(compensable-service) supports serialization.",
							ByteUtils.byteArrayToString(this.transactionContext.getXid().getGlobalTransactionId()),
							ByteUtils.byteArrayToString(current.getIdentifier().getGlobalTransactionId()));
				} else if (StringUtils.isNotBlank(invocation.getCancellableKey())) {
					container.cancel(invocation);
				} else {
					this.archive.setCancelled(true);
					logger.info("{}| cancel: identifier= {}, resourceKey= {}, resourceXid= {}.",
							ByteUtils.byteArrayToString(this.transactionContext.getXid().getGlobalTransactionId()),
							ByteUtils.byteArrayToString(current.getIdentifier().getGlobalTransactionId()),
							current.getCompensableResourceKey(), current.getCompensableXid());
				}
			} catch (RuntimeException rex) {
				errorExists = true;
				logger.error("{}| error occurred while cancelling service: {}",
						ByteUtils.byteArrayToString(this.transactionContext.getXid().getGlobalTransactionId()), this.archive,
						rex);
			} finally {
				this.archive = null;
				this.positive = null;
			}
		}

		if (errorExists) {
			throw new SystemException();
		}

	}

	private synchronized void fireRemoteParticipantRecoveryCancel() throws SystemException {
		boolean errorExists = false;

		for (int i = 0; i < this.resourceList.size(); i++) {
			XAResourceArchive current = this.resourceList.get(i);
			if (current.isRolledback()) {
				continue;
			}

			CompensableLogger transactionLogger = this.beanFactory.getCompensableLogger();
			XidFactory xidFactory = this.beanFactory.getCompensableXidFactory();
			TransactionXid branchXid = (TransactionXid) current.getXid();
			TransactionXid globalXid = xidFactory.createGlobalXid(branchXid.getGlobalTransactionId());
			try {
				current.recoveryRollback(globalXid);
				current.setRolledback(true);
				current.setCompleted(true);
				transactionLogger.updateCoordinator(current);

				logger.info("{}| recovery-cancel remote branch: {}", ByteUtils.byteArrayToString(branchXid
						.getGlobalTransactionId()), current.getDescriptor().getIdentifier());
			} catch (TransactionException transactionEx) {
				// TransactionException is thrown only by CompensableServiceFilter.
				logger.warn("{}| branch({}) should be cancelled by its own coordinator.", ByteUtils.byteArrayToString(branchXid
						.getGlobalTransactionId()), current.getDescriptor().getIdentifier());
			} catch (XAException ex) {
				switch (ex.errorCode) {
				case XAException.XAER_NOTA:
					logger.warn("{}| error occurred while recovery-cancelling remote branch: {}, transaction is not exists!",
							ByteUtils.byteArrayToString(branchXid.getGlobalTransactionId()), current.getDescriptor()
									.getIdentifier());
					break;
				default:
					errorExists = true;
					logger.error("{}| error occurred while recovery-cancelling remote branch: {}",
							ByteUtils.byteArrayToString(branchXid.getGlobalTransactionId()), current, ex);
				}
			} catch (RuntimeException rex) {
				errorExists = true;
				logger.error("{}| error occurred while recovery-cancelling remote branch: {}",
						ByteUtils.byteArrayToString(branchXid.getGlobalTransactionId()), current, rex);
			}
		}

		if (errorExists) {
			throw new SystemException();
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
			try {
				resourceCleaner.forget(xid, resource);
			} catch (RuntimeException rex) {
				success = false;
				logger.error("forget-transaction: error occurred while forgetting xid: {}", xid, rex);
			}
		}

		for (int i = 0; i < this.resourceList.size(); i++) {
			XAResourceArchive current = this.resourceList.get(i);

			XidFactory xidFactory = this.beanFactory.getCompensableXidFactory();
			TransactionXid branchXid = (TransactionXid) current.getXid();
			TransactionXid globalXid = xidFactory.createGlobalXid(branchXid.getGlobalTransactionId());
			try {
				current.forget(globalXid);
			} catch (TransactionException transactionEx) {
				// TransactionException is thrown only by CompensableServiceFilter.
				logger.warn("forget-transaction: branch({}) should be forgot by its own coordinator.", branchXid);
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
			throw new SystemException();
		}

	}

	public synchronized void recoveryForget() throws SystemException {
		LocalResourceCleaner resourceCleaner = this.beanFactory.getLocalResourceCleaner();

		boolean success = true;
		for (int i = 0; i < this.archiveList.size(); i++) {
			CompensableArchive current = this.archiveList.get(i);

			String transactionResourceKey = current.getTransactionResourceKey();
			String compensableResourceKey = current.getCompensableResourceKey();
			if (StringUtils.isNotBlank(transactionResourceKey)) {
				Xid branchXid = current.getTransactionXid();
				try {
					resourceCleaner.forget(branchXid, transactionResourceKey);
				} catch (RuntimeException rex) {
					success = false;
					logger.error("forget-transaction: error occurred while forgetting branch: {}", branchXid, rex);
				}
			}

			if (StringUtils.isNotBlank(compensableResourceKey)) {
				Xid branchXid = current.getCompensableXid();
				try {
					resourceCleaner.forget(branchXid, compensableResourceKey);
				} catch (RuntimeException rex) {
					success = false;
					logger.error("forget-transaction: error occurred while forgetting branch: {}", branchXid, rex);
				}
			}

		}

		for (int i = 0; i < this.resourceList.size(); i++) {
			XAResourceArchive current = this.resourceList.get(i);

			XidFactory xidFactory = this.beanFactory.getCompensableXidFactory();
			TransactionXid branchXid = (TransactionXid) current.getXid();
			TransactionXid globalXid = xidFactory.createGlobalXid(branchXid.getGlobalTransactionId());
			try {
				current.recoveryForget(globalXid);
			} catch (TransactionException transactionEx) {
				// TransactionException is thrown only by CompensableServiceFilter.
				logger.warn("forget-transaction: branch({}) should be forgot by its own coordinator.", branchXid);
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
			throw new SystemException();
		}

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
	public List<XAResourceArchive> getParticipantArchiveList() {
		return this.resourceList;
	}

	public void setRollbackOnly() throws IllegalStateException, SystemException {
		throw new IllegalStateException();
	}

	public void setRollbackOnlyQuietly() {
		throw new IllegalStateException();
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

	public boolean isParticipantStickyRequired() {
		return participantStickyRequired;
	}

	public void setParticipantStickyRequired(boolean participantStickyRequired) {
		this.participantStickyRequired = participantStickyRequired;
	}

	public Object getTransactionalExtra() {
		return transaction;
	}

	public void setTransactionalExtra(Object transactionalExtra) {
		this.transaction = (Transaction) transactionalExtra;
	}

	public Transaction getTransaction() {
		return (Transaction) this.getTransactionalExtra();
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

}
