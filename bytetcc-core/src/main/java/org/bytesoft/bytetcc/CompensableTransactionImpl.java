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
import org.bytesoft.compensable.archive.CompensableArchive;
import org.bytesoft.compensable.archive.TransactionArchive;
import org.bytesoft.compensable.logging.CompensableLogger;
import org.bytesoft.transaction.CommitRequiredException;
import org.bytesoft.transaction.RollbackRequiredException;
import org.bytesoft.transaction.Transaction;
import org.bytesoft.transaction.TransactionContext;
import org.bytesoft.transaction.TransactionRepository;
import org.bytesoft.transaction.archive.XAResourceArchive;
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

	private Serializable variable;
	private int transactionVote;
	private int transactionStatus = Status.STATUS_ACTIVE;
	/* current comensable-decision in confirm/cancel phase. */
	private transient Boolean positive;
	/* current compense-archive in confirm/cancel phase. */
	private transient CompensableArchive archive;

	/* current compensable-archive list in try phase. */
	private final transient List<CompensableArchive> transientArchiveList = new ArrayList<CompensableArchive>();

	private boolean participantStickyRequired;

	public CompensableTransactionImpl(TransactionContext txContext) {
		this.transactionContext = txContext;
	}

	public TransactionArchive getTransactionArchive() {
		TransactionArchive transactionArchive = new TransactionArchive();
		transactionArchive.setVariable(this.variable);
		transactionArchive.setCoordinator(this.transactionContext.isCoordinator());
		transactionArchive.setCompensable(this.transactionContext.isCompensable());
		transactionArchive.setCompensableStatus(this.transactionStatus);
		transactionArchive.setVote(this.transactionVote);
		transactionArchive.setXid(this.transactionContext.getXid());
		transactionArchive.getRemoteResources().addAll(this.resourceList);
		transactionArchive.getCompensableResourceList().addAll(this.archiveList);
		return transactionArchive;
	}

	public synchronized void commit() throws RollbackException, HeuristicMixedException, HeuristicRollbackException,
			SecurityException, IllegalStateException, SystemException {

		CompensableLogger compensableLogger = this.beanFactory.getCompensableLogger();

		this.transactionContext.setCompensating(true);
		this.transactionStatus = Status.STATUS_COMMITTING;
		compensableLogger.updateTransaction(this.getTransactionArchive());

		boolean nativeSuccess = this.fireNativeParticipantConfirm();
		boolean remoteSuccess = this.fireRemoteParticipantConfirm();
		if (nativeSuccess && remoteSuccess) {
			this.transactionStatus = Status.STATUS_COMMITTED;
			compensableLogger.updateTransaction(this.getTransactionArchive());
			logger.info("{}| compensable transaction committed!",
					ByteUtils.byteArrayToString(transactionContext.getXid().getGlobalTransactionId()));
		} else {
			throw new SystemException();
		}

	}

	private synchronized boolean fireNativeParticipantConfirm() {
		boolean success = true;

		ContainerContext container = this.beanFactory.getContainerContext();
		for (int i = this.archiveList.size() - 1; i >= 0; i--) {
			CompensableArchive current = this.archiveList.get(i);
			if (current.isConfirmed()) {
				continue;
			}

			TransactionXid transactionXid = this.transactionContext.getXid();
			try {
				this.positive = true;
				this.archive = current;
				CompensableInvocation invocation = current.getCompensable();
				if (invocation == null) {
					success = false;

					byte[] globalTransactionId = transactionXid.getGlobalTransactionId();
					byte[] branchQualifier = current.getTransactionXid().getBranchQualifier();
					logger.error(
							"{}| error occurred while confirming service: {}, please check whether the params of method(compensable-service) supports serialization.",
							ByteUtils.byteArrayToString(globalTransactionId),
							ByteUtils.byteArrayToString(branchQualifier));
				} else {
					container.confirm(invocation);
				}
			} catch (RuntimeException rex) {
				success = false;

				logger.error("{}| error occurred while confirming service: {}",
						ByteUtils.byteArrayToString(transactionXid.getGlobalTransactionId()), this.archive, rex);
			} finally {
				this.archive = null;
				this.positive = null;
			}
		}

		return success;
	}

	private synchronized boolean fireRemoteParticipantConfirm() {
		boolean success = true;

		for (int i = 0; i < this.resourceList.size(); i++) {
			XAResourceArchive current = this.resourceList.get(i);
			if (current.isCommitted()) {
				continue;
			}

			CompensableLogger transactionLogger = this.beanFactory.getCompensableLogger();
			XidFactory xidFactory = this.beanFactory.getCompensableXidFactory();
			TransactionXid branchXid = (TransactionXid) current.getXid();
			TransactionXid globalXid = xidFactory.createGlobalXid(branchXid.getGlobalTransactionId());
			try {
				current.commit(globalXid, true);
				current.setCommitted(true);
				current.setCompleted(true);
				transactionLogger.updateCoordinator(current);

				logger.info("{}| confirm remote branch: {}",
						ByteUtils.byteArrayToString(branchXid.getGlobalTransactionId()),
						current.getDescriptor().getIdentifier());
			} catch (XAException ex) {
				switch (ex.errorCode) {
				case XAException.XA_HEURCOM:
					current.setCommitted(true);
					current.setHeuristic(false);
					current.setCompleted(true);
					transactionLogger.updateCoordinator(current);
					break;
				case XAException.XA_HEURRB:
					success = false;
					current.setRolledback(true);
					current.setHeuristic(false);
					current.setCompleted(true);
					transactionLogger.updateCoordinator(current);
					break;
				case XAException.XA_HEURMIX:
					// should never happen
					success = false;
					current.setHeuristic(true);
					// current.setCommitted(true);
					// current.setRolledback(true);
					// current.setCompleted(true);
					transactionLogger.updateCoordinator(current);
					break;
				default:
					success = false;
				}

				if (success) {
					logger.info("{}| confirm remote branch: {}",
							ByteUtils.byteArrayToString(branchXid.getGlobalTransactionId()),
							current.getDescriptor().getIdentifier());
				} else {
					logger.error("{}| error occurred while confirming branch: {}",
							ByteUtils.byteArrayToString(branchXid.getGlobalTransactionId()), this.archive, ex);
				}
			} catch (RuntimeException rex) {
				success = false;

				TransactionXid transactionXid = this.transactionContext.getXid();
				logger.error("{}| error occurred while confirming branch: {}",
						ByteUtils.byteArrayToString(transactionXid.getGlobalTransactionId()), this.archive, rex);
			}
		}

		return success;
	}

	public void participantPrepare() throws RollbackRequiredException, CommitRequiredException {
		throw new RuntimeException("Not supported!");
	}

	public void participantCommit() throws RollbackException, HeuristicMixedException, HeuristicRollbackException,
			SecurityException, IllegalStateException, CommitRequiredException, SystemException {
		throw new SystemException("Not supported!");
	}

	public synchronized void rollback() throws IllegalStateException, SystemException {
		CompensableLogger compensableLogger = this.beanFactory.getCompensableLogger();

		this.transactionStatus = Status.STATUS_ROLLING_BACK;
		this.transactionContext.setCompensating(true);

		compensableLogger.updateTransaction(this.getTransactionArchive());

		boolean coordinator = this.transactionContext.isCoordinator();
		boolean coordinatorTried = false;
		for (int i = 0; coordinator && i < this.archiveList.size(); i++) {
			CompensableArchive compensableArchive = this.archiveList.get(i);
			coordinatorTried = compensableArchive.isTried() ? true : coordinatorTried;
		}

		if (coordinator && coordinatorTried == false) {
			boolean remoteSuccess = this.fireRemoteParticipantCancel();
			if (remoteSuccess) {
				this.transactionStatus = Status.STATUS_ROLLEDBACK;
			} else {
				throw new SystemException();
			}
		} else {
			boolean nativeSuccess = this.fireNativeParticipantCancel();
			boolean remoteSuccess = this.fireRemoteParticipantCancel();
			if (nativeSuccess && remoteSuccess) {
				this.transactionStatus = Status.STATUS_ROLLEDBACK;
				compensableLogger.updateTransaction(this.getTransactionArchive());
				logger.info("{}| compensable transaction rolled back!",
						ByteUtils.byteArrayToString(transactionContext.getXid().getGlobalTransactionId()));
			} else {
				throw new SystemException();
			}
		}
	}

	private synchronized boolean fireNativeParticipantCancel() {
		boolean success = true;

		ContainerContext container = this.beanFactory.getContainerContext();
		for (int i = this.archiveList.size() - 1; i >= 0; i--) {
			CompensableArchive current = this.archiveList.get(i);
			if (current.isCancelled()) {
				continue;
			}

			TransactionXid transactionXid = this.transactionContext.getXid();
			try {
				this.positive = false;
				this.archive = current;
				CompensableInvocation invocation = current.getCompensable();
				if (invocation == null) {
					success = false;

					byte[] globalTransactionId = transactionXid.getGlobalTransactionId();
					byte[] branchQualifier = current.getTransactionXid().getBranchQualifier();
					logger.error(
							"{}| error occurred while cancelling service: {}, please check whether the params of method(compensable-service) supports serialization.",
							ByteUtils.byteArrayToString(globalTransactionId),
							ByteUtils.byteArrayToString(branchQualifier));
				} else {
					container.cancel(invocation);
				}
			} catch (RuntimeException rex) {
				success = false;

				logger.error("{}| error occurred while cancelling service: {}",
						ByteUtils.byteArrayToString(transactionXid.getGlobalTransactionId()), this.archive, rex);
			} finally {
				this.archive = null;
				this.positive = null;
			}
		}

		return success;
	}

	private synchronized boolean fireRemoteParticipantCancel() {
		boolean success = true;

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

				logger.info("{}| cancel remote branch: {}",
						ByteUtils.byteArrayToString(branchXid.getGlobalTransactionId()),
						current.getDescriptor().getIdentifier());
			} catch (XAException ex) {
				success = false;

				logger.error("{}| error occurred while cancelling branch: {}",
						ByteUtils.byteArrayToString(branchXid.getGlobalTransactionId()), this.archive, ex);
			} catch (RuntimeException rex) {
				success = false;

				TransactionXid transactionXid = this.transactionContext.getXid();
				logger.error("{}| error occurred while cancelling branch: {}",
						ByteUtils.byteArrayToString(transactionXid.getGlobalTransactionId()), this.archive, rex);
			}
		}

		return success;
	}

	public boolean enlistResource(XAResource xaRes) throws RollbackException, IllegalStateException, SystemException {
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
			logger.info("{}| enlist remote resource: {}.",
					ByteUtils.byteArrayToString(globalXid.getGlobalTransactionId()), identifier);
		}

		return true;
	}

	public boolean delistResource(XAResource xaRes, int flag) throws IllegalStateException, SystemException {
		return true;
	}

	public void resume() throws SystemException {
		throw new SystemException();
	}

	public void suspend() throws SystemException {
		throw new SystemException();
	}

	public void registerCompensable(CompensableInvocation invocation) {
		XidFactory xidFactory = this.beanFactory.getTransactionXidFactory();

		CompensableArchive archive = new CompensableArchive();
		archive.setIdentifier(xidFactory.createGlobalXid());

		archive.setCompensable(invocation);

		this.archiveList.add(archive);
		this.transientArchiveList.add(archive);
	}

	public void registerSynchronization(Synchronization sync)
			throws RollbackException, IllegalStateException, SystemException {
	}

	public void registerTransactionListener(TransactionListener listener) {
	}

	public void registerTransactionResourceListener(TransactionResourceListener listener) {
	}

	public void onEnlistResource(Xid xid, XAResource xares) {
	}

	public void onDelistResource(Xid xid, XAResource xares) {
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
			for (int i = 0; i < this.transientArchiveList.size(); i++) {
				CompensableArchive compensableArchive = this.transientArchiveList.get(i);
				compensableArchive.setTransactionXid(xid);
				compensableArchive.setTransactionResourceKey(resourceKey);

				compensableLogger.createCompensable(compensableArchive);
			}
		}

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
						this.archive.getIdentifier(), this.archive.getCompensableResourceKey(),
						this.archive.getCompensableXid());
			} else {
				this.archive.setCancelled(true);

				logger.info("{}| cancel: identifier= {}, resourceKey= {}, resourceXid= {}.",
						ByteUtils.byteArrayToString(transactionContext.getXid().getGlobalTransactionId()),
						this.archive.getIdentifier(), this.archive.getCompensableResourceKey(),
						this.archive.getCompensableXid());
			}
			compensableLogger.updateCompensable(this.archive);
		} else if (this.transactionContext.isCoordinator()) {
			for (Iterator<CompensableArchive> itr = this.transientArchiveList.iterator(); itr.hasNext();) {
				CompensableArchive compensableArchive = itr.next();
				itr.remove(); // remove

				compensableArchive.setTried(true);
				// compensableLogger.updateCompensable(compensableArchive);

				logger.info("{}| try: identifier= {}, resourceKey= {}, resourceXid= {}.",
						ByteUtils.byteArrayToString(transactionContext.getXid().getGlobalTransactionId()),
						compensableArchive.getIdentifier(), compensableArchive.getTransactionResourceKey(),
						compensableArchive.getTransactionXid());
			}

			TransactionArchive transactionArchive = this.getTransactionArchive();
			transactionArchive.setCompensableStatus(Status.STATUS_COMMITTING);
			compensableLogger.updateTransaction(transactionArchive);

			logger.info("{}| try completed.",
					ByteUtils.byteArrayToString(transactionContext.getXid().getGlobalTransactionId()));
		} else {
			for (Iterator<CompensableArchive> itr = this.transientArchiveList.iterator(); itr.hasNext();) {
				CompensableArchive compensableArchive = itr.next();
				itr.remove(); // remove
				compensableArchive.setTried(true);
				compensableLogger.updateCompensable(compensableArchive);

				logger.info("{}| try: identifier= {}, resourceKey= {}, resourceXid= {}.",
						ByteUtils.byteArrayToString(transactionContext.getXid().getGlobalTransactionId()),
						compensableArchive.getIdentifier(), compensableArchive.getTransactionResourceKey(),
						compensableArchive.getTransactionXid());
			}
		}

	}

	public synchronized void recoveryCommit() throws CommitRequiredException, SystemException {
		CompensableLogger compensableLogger = this.beanFactory.getCompensableLogger();

		this.transactionContext.setCompensating(true);
		this.transactionStatus = Status.STATUS_COMMITTING;
		compensableLogger.updateTransaction(this.getTransactionArchive());

		boolean nativeSuccess = this.fireNativeParticipantRecoveryConfirm();
		boolean remoteSuccess = this.fireRemoteParticipantRecoveryConfirm();
		if (nativeSuccess && remoteSuccess) {
			this.transactionStatus = Status.STATUS_COMMITTED;
			compensableLogger.updateTransaction(this.getTransactionArchive());
			logger.info("{}| compensable transaction recovery committed!",
					ByteUtils.byteArrayToString(transactionContext.getXid().getGlobalTransactionId()));
		} else {
			throw new SystemException();
		}

	}

	private synchronized boolean fireNativeParticipantRecoveryConfirm() {
		boolean success = true;

		ContainerContext container = this.beanFactory.getContainerContext();
		XAResourceDeserializer resourceDeserializer = this.beanFactory.getResourceDeserializer();
		CompensableLogger compensableLogger = this.beanFactory.getCompensableLogger();

		boolean previouConfirmed = false;
		for (int i = this.archiveList.size() - 1; i >= 0; i--) {
			CompensableArchive current = this.archiveList.get(i);
			boolean currentConfirmed = current.isConfirmed();

			if (currentConfirmed) {
				continue;
			}

			TransactionXid compensableXid = (TransactionXid) current.getCompensableXid();
			try {
				this.positive = true;
				this.archive = current;

				String identifier = current.getCompensableResourceKey();
				if (StringUtils.isBlank(identifier)) {
					if (previouConfirmed) {
						logger.warn(
								"There is no valid resource participated in the current branch transaction, the status of the current branch transaction is unknown!");
					} else {
						logger.debug("There is no valid resource participated in the current branch transaction!");
					}
				} else {
					XAResource xares = resourceDeserializer.deserialize(identifier);
					if (RecoveredResource.class.isInstance(xares)) {
						RecoveredResource resource = (RecoveredResource) xares;
						try {
							resource.recoverable(compensableXid);
							current.setConfirmed(true);
							compensableLogger.updateCompensable(current);
							continue;
						} catch (XAException xaex) {
							switch (xaex.errorCode) {
							case XAException.XAER_NOTA:
								break;
							case XAException.XAER_RMERR:
								logger.warn(
										"The database table 'bytejta' cannot found, the status of the current branch transaction is unknown!");
								break;
							case XAException.XAER_RMFAIL:
								success = false;

								Xid xid = current.getTransactionXid();
								logger.error("Error occurred while recovering the branch transaction service: {}",
										ByteUtils.byteArrayToString(xid.getGlobalTransactionId()), xaex);
								break;
							default:
								logger.error("Illegal state, the status of the current branch transaction is unknown!");
							}
						}
					} else {
						logger.error("Illegal resources, the status of the current branch transaction is unknown!");
					}
				}

				CompensableInvocation invocation = current.getCompensable();
				if (invocation == null) {
					throw new IllegalArgumentException();
				} else {
					container.confirm(invocation);
				}
			} catch (IllegalArgumentException rex) {
				success = false;

				TransactionXid transactionXid = this.transactionContext.getXid();
				byte[] globalTransactionId = transactionXid.getGlobalTransactionId();
				byte[] branchQualifier = compensableXid.getBranchQualifier();
				logger.error(
						"{}| error occurred while confirming service: {}, please check whether the params of method(compensable-service) supports serialization.",
						ByteUtils.byteArrayToString(globalTransactionId), ByteUtils.byteArrayToString(branchQualifier));
			} catch (RuntimeException rex) {
				success = false;

				TransactionXid transactionXid = this.transactionContext.getXid();
				logger.error("{}| error occurred while confirming service: {}",
						ByteUtils.byteArrayToString(transactionXid.getGlobalTransactionId()), this.archive, rex);
			} finally {
				this.archive = null;
				this.positive = null;

				previouConfirmed = currentConfirmed;
			}

		}

		return success;
	}

	private synchronized boolean fireRemoteParticipantRecoveryConfirm() {
		boolean success = true;

		for (int i = 0; i < this.resourceList.size(); i++) {
			XAResourceArchive current = this.resourceList.get(i);
			if (current.isCommitted()) {
				continue;
			}

			CompensableLogger transactionLogger = this.beanFactory.getCompensableLogger();
			XidFactory xidFactory = this.beanFactory.getCompensableXidFactory();
			TransactionXid branchXid = (TransactionXid) current.getXid();
			TransactionXid globalXid = xidFactory.createGlobalXid(branchXid.getGlobalTransactionId());
			try {
				current.recoveryCommit(globalXid);
				current.setCommitted(true);
				current.setCompleted(true);
				transactionLogger.updateCoordinator(current);

				logger.info("{}| recovery-confirm remote branch: {}",
						ByteUtils.byteArrayToString(branchXid.getGlobalTransactionId()),
						current.getDescriptor().getIdentifier());
			} catch (XAException ex) {
				switch (ex.errorCode) {
				case XAException.XA_HEURCOM:
					current.setCommitted(true);
					current.setHeuristic(false);
					current.setCompleted(true);
					transactionLogger.updateCoordinator(current);
					break;
				case XAException.XA_HEURRB:
					success = false;
					current.setRolledback(true);
					current.setHeuristic(false);
					current.setCompleted(true);
					transactionLogger.updateCoordinator(current);
					break;
				case XAException.XA_HEURMIX:
					success = false;
					current.setHeuristic(true);
					// current.setCommitted(true);
					// current.setRolledback(true);
					// current.setCompleted(true);
					transactionLogger.updateCoordinator(current);
					break;
				default:
					success = false;
				}

				if (success) {
					logger.info("{}| recovery-confirm remote branch: {}",
							ByteUtils.byteArrayToString(branchXid.getGlobalTransactionId()),
							current.getDescriptor().getIdentifier());
				} else {
					logger.error("{}| error occurred while confirming branch: {}",
							ByteUtils.byteArrayToString(branchXid.getGlobalTransactionId()), this.archive, ex);
				}
			} catch (RuntimeException rex) {
				success = false;

				TransactionXid transactionXid = this.transactionContext.getXid();
				logger.error("{}| error occurred while confirming branch: {}",
						ByteUtils.byteArrayToString(transactionXid.getGlobalTransactionId()), this.archive, rex);
			}
		}

		return success;
	}

	public synchronized void recoveryRollback() throws RollbackRequiredException, SystemException {
		CompensableLogger compensableLogger = this.beanFactory.getCompensableLogger();

		this.transactionStatus = Status.STATUS_ROLLING_BACK;
		this.transactionContext.setCompensating(true);
		compensableLogger.updateTransaction(this.getTransactionArchive());

		boolean nativeSuccess = this.fireNativeParticipantRecoveryCancel();
		boolean remoteSuccess = this.fireRemoteParticipantRecoveryCancel();
		if (nativeSuccess && remoteSuccess) {
			this.transactionStatus = Status.STATUS_ROLLEDBACK;
			compensableLogger.updateTransaction(this.getTransactionArchive());
			logger.info("{}| compensable transaction recovery rolled back!",
					ByteUtils.byteArrayToString(transactionContext.getXid().getGlobalTransactionId()));
		} else {
			throw new SystemException();
		}

	}

	private synchronized boolean fireNativeParticipantRecoveryCancel() {
		boolean success = true;

		ContainerContext container = this.beanFactory.getContainerContext();
		XAResourceDeserializer resourceDeserializer = this.beanFactory.getResourceDeserializer();
		CompensableLogger compensableLogger = this.beanFactory.getCompensableLogger();

		boolean previouCancelled = false;
		for (int i = this.archiveList.size() - 1; i >= 0; i--) {
			CompensableArchive current = this.archiveList.get(i);
			boolean currentCancelled = current.isCancelled();

			if (currentCancelled) {
				continue;
			}

			if (current.isTried() == false) /* this.transactionContext.isCoordinator() == false && */ {
				String identifier = current.getTransactionResourceKey();
				if (StringUtils.isBlank(identifier)) {
					logger.warn(
							"There is no valid resource participated in the trying branch transaction, the status of the branch transaction is unknown!");
				} else {
					XAResource xares = resourceDeserializer.deserialize(identifier);
					if (RecoveredResource.class.isInstance(xares)) {
						RecoveredResource resource = (RecoveredResource) xares;
						try {
							resource.recoverable(current.getTransactionXid());
							current.setTried(true);
							compensableLogger.updateCompensable(current);
						} catch (XAException xaex) {
							switch (xaex.errorCode) {
							case XAException.XAER_NOTA:
								current.setTried(false);
								continue;
							case XAException.XAER_RMERR:
								logger.warn(
										"The database table 'bytejta' cannot found, the status of the trying branch transaction is unknown!");
								break;
							case XAException.XAER_RMFAIL:
								success = false;

								Xid xid = current.getTransactionXid();
								logger.error("Error occurred while recovering the branch transaction service: {}",
										ByteUtils.byteArrayToString(xid.getGlobalTransactionId()), xaex);
								break;
							default:
								logger.error("Illegal state, the status of the trying branch transaction is unknown!");
							}
						}
					} else {
						logger.error("Illegal resources, the status of the trying branch transaction is unknown!");
					}
				}
			}

			TransactionXid compensableXid = (TransactionXid) current.getCompensableXid();
			TransactionXid transactionXid = this.transactionContext.getXid();
			try {
				this.positive = false;
				this.archive = current;

				String identifier = current.getCompensableResourceKey();
				if (StringUtils.isBlank(identifier)) {
					if (previouCancelled) {
						logger.warn(
								"There is no valid resource participated in the current branch transaction, the status of the current branch transaction is unknown!");
					} else {
						logger.debug("There is no valid resource participated in the current branch transaction!");
					}
				} else {
					XAResource xares = resourceDeserializer.deserialize(identifier);
					if (RecoveredResource.class.isInstance(xares)) {
						RecoveredResource resource = (RecoveredResource) xares;
						try {
							resource.recoverable(compensableXid);
							current.setCancelled(true);
							compensableLogger.updateCompensable(current);
							continue;
						} catch (XAException xaex) {
							switch (xaex.errorCode) {
							case XAException.XAER_NOTA:
								break;
							case XAException.XAER_RMERR:
								logger.warn(
										"The database table 'bytejta' cannot found, the status of the current branch transaction is unknown!");
								break;
							case XAException.XAER_RMFAIL:
								success = false;

								Xid xid = current.getTransactionXid();
								logger.error("Error occurred while recovering the branch transaction service: {}",
										ByteUtils.byteArrayToString(xid.getGlobalTransactionId()), xaex);
								break;
							default:
								logger.error("Illegal state, the status of the current branch transaction is unknown!");
							}
						}
					} else {
						logger.error("Illegal resources, the status of the current branch transaction is unknown!");
					}
				}

				CompensableInvocation invocation = current.getCompensable();
				if (invocation == null) {
					throw new IllegalArgumentException();
				} else {
					container.cancel(invocation);
				}
			} catch (IllegalArgumentException rex) {
				success = false;

				byte[] globalTransactionId = transactionXid.getGlobalTransactionId();
				byte[] branchQualifier = current.getTransactionXid().getBranchQualifier();
				logger.error(
						"{}| error occurred while cancelling service: {}, please check whether the params of method(compensable-service) supports serialization.",
						ByteUtils.byteArrayToString(globalTransactionId), ByteUtils.byteArrayToString(branchQualifier));
			} catch (RuntimeException rex) {
				success = false;

				logger.error("{}| error occurred while cancelling service: {}",
						ByteUtils.byteArrayToString(transactionXid.getGlobalTransactionId()), this.archive, rex);
			} finally {
				this.archive = null;
				this.positive = null;

				previouCancelled = currentCancelled;
			}
		}

		return success;
	}

	private synchronized boolean fireRemoteParticipantRecoveryCancel() {
		boolean success = true;

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

				logger.info("{}| recovery-cancel remote branch: {}",
						ByteUtils.byteArrayToString(branchXid.getGlobalTransactionId()),
						current.getDescriptor().getIdentifier());
			} catch (XAException ex) {
				success = false;

				logger.error("{}| error occurred while cancelling branch: {}",
						ByteUtils.byteArrayToString(branchXid.getGlobalTransactionId()), this.archive, ex);
			} catch (RuntimeException rex) {
				success = false;

				TransactionXid transactionXid = this.transactionContext.getXid();
				logger.error("{}| error occurred while cancelling branch: {}",
						ByteUtils.byteArrayToString(transactionXid.getGlobalTransactionId()), this.archive, rex);
			}
		}

		return success;
	}

	public synchronized void forget() throws SystemException {
		LocalResourceCleaner resourceCleaner = this.beanFactory.getLocalResourceCleaner();
		boolean success = true;

		Map<Xid, String> xidMap = new HashMap<Xid, String>();
		for (int i = 0; i < this.archiveList.size(); i++) {
			CompensableArchive current = this.archiveList.get(i);
			Xid transactionXid = current.getTransactionXid();
			Xid compensableXid = current.getCompensableXid();
			if (transactionXid != null) {
				xidMap.put(transactionXid, current.getTransactionResourceKey());
			}
			if (compensableXid != null) {
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

	public Serializable getVariable() {
		return variable;
	}

	public void setVariable(Serializable variable) {
		this.variable = variable;
	}

}
