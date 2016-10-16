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

import java.util.ArrayList;
import java.util.List;

import javax.transaction.HeuristicMixedException;
import javax.transaction.HeuristicRollbackException;
import javax.transaction.RollbackException;
import javax.transaction.Status;
import javax.transaction.Synchronization;
import javax.transaction.SystemException;
import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;

import org.bytesoft.bytejta.supports.resource.RemoteResourceDescriptor;
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
import org.bytesoft.transaction.archive.XAResourceArchive;
import org.bytesoft.transaction.supports.TransactionListener;
import org.bytesoft.transaction.supports.TransactionListenerAdapter;
import org.bytesoft.transaction.supports.TransactionResourceListener;
import org.bytesoft.transaction.supports.resource.XAResourceDescriptor;
import org.bytesoft.transaction.xa.TransactionXid;
import org.bytesoft.transaction.xa.XidFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CompensableTransactionImpl extends TransactionListenerAdapter implements CompensableTransaction {
	static final Logger logger = LoggerFactory.getLogger(CompensableTransactionImpl.class.getSimpleName());

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

	private transient String resourceKey;

	/* current compensable-archive list in try phase, only used by participant. */
	private final transient List<CompensableArchive> transientArchiveList = new ArrayList<CompensableArchive>();

	private boolean participantStickyRequired;

	public CompensableTransactionImpl(TransactionContext txContext) {
		this.transactionContext = txContext;
	}

	public TransactionArchive getTransactionArchive() {
		TransactionArchive transactionArchive = new TransactionArchive();
		transactionArchive.setCoordinator(this.transactionContext.isCoordinator());
		transactionArchive.setCompensable(this.transactionContext.isCompensable());
		transactionArchive.setCompensableStatus(this.transactionStatus);
		transactionArchive.setVote(this.transactionVote);
		transactionArchive.setXid(this.transactionContext.getXid());
		transactionArchive.getRemoteResources().addAll(this.resourceList);
		transactionArchive.getCompensableResourceList().addAll(this.archiveList);
		return transactionArchive;
	}

	public void commit() throws RollbackException, HeuristicMixedException, HeuristicRollbackException,
			SecurityException, IllegalStateException, SystemException {

		CompensableLogger compensableLogger = this.beanFactory.getCompensableLogger();

		this.transactionContext.setCompensating(true);
		this.transactionStatus = Status.STATUS_COMMITTING;
		compensableLogger.updateTransaction(this.getTransactionArchive());

		boolean nativeSuccess = this.fireNativeParticipantConfirm();
		boolean remoteSuccess = this.fireRemoteParticipantConfirm();
		if (nativeSuccess && remoteSuccess) {
			this.transactionStatus = Status.STATUS_COMMITTED;
			compensableLogger.deleteTransaction(this.getTransactionArchive());
		} else {
			throw new SystemException();
		}

	}

	private boolean fireNativeParticipantConfirm() {
		boolean success = true;

		ContainerContext executor = this.beanFactory.getContainerContext();
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
							"[{}] commit-transaction: error occurred while confirming service: {}, please check whether the params of method(compensable-service) supports serialization.",
							ByteUtils.byteArrayToString(globalTransactionId),
							ByteUtils.byteArrayToString(branchQualifier));
				} else {
					executor.confirm(invocation);
				}
			} catch (RuntimeException rex) {
				success = false;

				logger.error("[{}] commit-transaction: error occurred while confirming service: {}",
						ByteUtils.byteArrayToString(transactionXid.getGlobalTransactionId()), this.archive, rex);
			} finally {
				this.archive = null;
				this.positive = null;
			}
		}

		return success;
	}

	private boolean fireRemoteParticipantConfirm() {
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
			} catch (XAException ex) {
				success = false;

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

				if (success == false) {
					logger.error("[{}] commit-transaction: error occurred while confirming branch: {}",
							ByteUtils.byteArrayToString(branchXid.getGlobalTransactionId()), this.archive, ex);
				}
			} catch (RuntimeException rex) {
				success = false;

				TransactionXid transactionXid = this.transactionContext.getXid();
				logger.error("[{}] commit-transaction: error occurred while confirming branch: {}",
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

	public void rollback() throws IllegalStateException, SystemException {
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
				compensableLogger.deleteTransaction(this.getTransactionArchive());
			} else {
				throw new SystemException();
			}
		}
	}

	private boolean fireNativeParticipantCancel() {
		boolean success = true;

		ContainerContext executor = this.beanFactory.getContainerContext();
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
							"[{}] rollback-transaction: error occurred while cancelling service: {}, please check whether the params of method(compensable-service) supports serialization.",
							ByteUtils.byteArrayToString(globalTransactionId),
							ByteUtils.byteArrayToString(branchQualifier));
				} else {
					executor.cancel(invocation);
				}
			} catch (RuntimeException rex) {
				success = false;

				logger.error("[{}] rollback-transaction: error occurred while cancelling service: {}",
						ByteUtils.byteArrayToString(transactionXid.getGlobalTransactionId()), this.archive, rex);
			} finally {
				this.archive = null;
				this.positive = null;
			}
		}

		return success;
	}

	private boolean fireRemoteParticipantCancel() {
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
			} catch (XAException ex) {
				success = false;

				logger.error("[{}] rollback-transaction: error occurred while cancelling branch: {}",
						ByteUtils.byteArrayToString(branchXid.getGlobalTransactionId()), this.archive, ex);
			} catch (RuntimeException rex) {
				success = false;

				TransactionXid transactionXid = this.transactionContext.getXid();
				logger.error("[{}] rollback-transaction: error occurred while cancelling branch: {}",
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

	public void participantComplete() {
		this.transientArchiveList.clear();
	}

	public void registerCompensable(CompensableInvocation invocation) {
		CompensableArchive archive = new CompensableArchive();

		archive.setCompensable(invocation);

		if (transactionContext.isCoordinator()) {
			// archive.setTransactionXid(transactionXid); // lazy
			this.archiveList.add(archive);
		} else {
			// archive.setTransactionXid(branchXid); // lazy
			this.archiveList.add(archive);
			this.transientArchiveList.add(archive);
		}

	}

	public void registerSynchronization(Synchronization sync) throws RollbackException, IllegalStateException,
			SystemException {
	}

	public void registerTransactionListener(TransactionListener listener) {
	}

	public void registerTransactionResourceListener(TransactionResourceListener listener) {
	}

	public void onCommitStart(TransactionXid xid) {
		CompensableLogger compensableLogger = this.beanFactory.getCompensableLogger();
		XidFactory xidFactory = this.beanFactory.getTransactionXidFactory();

		if (this.transactionContext.isCompensating()) {
			this.archive.setCompensableResourceKey(this.resourceKey);
			compensableLogger.updateCompensable(this.archive);
		} else if (this.transactionContext.isCoordinator()) {
			for (int i = 0; i < this.archiveList.size(); i++) {
				CompensableArchive compensableArchive = this.archiveList.get(i);
				compensableArchive.setTransactionXid(xid);
				compensableArchive.setTransactionResourceKey(this.resourceKey);

				TransactionXid compensableXid = xidFactory.createGlobalXid();
				compensableArchive.setCompensableXid(compensableXid);

				compensableLogger.createCompensable(archive);
			}
		} else {
			for (int i = 0; i < this.transientArchiveList.size(); i++) {
				CompensableArchive compensableArchive = this.transientArchiveList.get(i);
				compensableArchive.setTransactionXid(xid);
				compensableArchive.setTransactionResourceKey(this.resourceKey);

				TransactionXid compensableXid = xidFactory.createGlobalXid();
				compensableArchive.setCompensableXid(compensableXid);

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
			} else {
				this.archive.setCancelled(true);
			}
			compensableLogger.updateCompensable(this.archive);
		} else if (this.transactionContext.isCoordinator()) {
			for (int i = 0; i < this.archiveList.size(); i++) {
				CompensableArchive compensableArchive = this.archiveList.get(i);
				compensableArchive.setTried(true);
				compensableLogger.updateCompensable(compensableArchive);
			}
		} else {
			for (int i = 0; i < this.transientArchiveList.size(); i++) {
				CompensableArchive compensableArchive = this.transientArchiveList.get(i);
				compensableArchive.setTried(true);
				compensableLogger.updateCompensable(compensableArchive);
			}
		}

	}

	public void recoveryCommit() throws CommitRequiredException, SystemException {
		CompensableLogger compensableLogger = this.beanFactory.getCompensableLogger();

		this.transactionContext.setCompensating(true);
		this.transactionStatus = Status.STATUS_COMMITTING;
		compensableLogger.updateTransaction(this.getTransactionArchive());

		boolean nativeSuccess = this.fireNativeParticipantRecoveryConfirm();
		boolean remoteSuccess = this.fireRemoteParticipantRecoveryConfirm();
		if (nativeSuccess && remoteSuccess) {
			this.transactionStatus = Status.STATUS_COMMITTED;
			compensableLogger.deleteTransaction(this.getTransactionArchive());
		} else {
			throw new SystemException();
		}

	}

	private boolean fireNativeParticipantRecoveryConfirm() {
		boolean success = true;

		ContainerContext executor = this.beanFactory.getContainerContext();
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
							"[{}] recover-transaction: error occurred while confirming service: {}, please check whether the params of method(compensable-service) supports serialization.",
							ByteUtils.byteArrayToString(globalTransactionId),
							ByteUtils.byteArrayToString(branchQualifier));
				} else {
					executor.confirm(invocation);
				}
			} catch (RuntimeException rex) {
				success = false;

				logger.error("[{}] recover-transaction: error occurred while confirming service: {}",
						ByteUtils.byteArrayToString(transactionXid.getGlobalTransactionId()), this.archive, rex);
			} finally {
				this.archive = null;
				this.positive = null;
			}
		}

		return success;
	}

	private boolean fireRemoteParticipantRecoveryConfirm() {
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

				if (success == false) {
					logger.error("[{}] recover-transaction: error occurred while confirming branch: {}",
							ByteUtils.byteArrayToString(branchXid.getGlobalTransactionId()), this.archive, ex);
				}
			} catch (RuntimeException rex) {
				success = false;

				TransactionXid transactionXid = this.transactionContext.getXid();
				logger.error("[{}] recover-transaction: error occurred while confirming branch: {}",
						ByteUtils.byteArrayToString(transactionXid.getGlobalTransactionId()), this.archive, rex);
			}
		}

		return success;
	}

	public void recoveryRollback() throws RollbackRequiredException, SystemException {
		CompensableLogger compensableLogger = this.beanFactory.getCompensableLogger();

		this.transactionStatus = Status.STATUS_ROLLING_BACK;
		this.transactionContext.setCompensating(true);
		compensableLogger.updateTransaction(this.getTransactionArchive());

		boolean nativeSuccess = this.fireNativeParticipantRecoveryCancel();
		boolean remoteSuccess = this.fireRemoteParticipantRecoveryCancel();
		if (nativeSuccess && remoteSuccess) {
			this.transactionStatus = Status.STATUS_ROLLEDBACK;
			compensableLogger.deleteTransaction(this.getTransactionArchive());
		} else {
			throw new SystemException();
		}

	}

	private boolean fireNativeParticipantRecoveryCancel() {
		boolean success = true;

		ContainerContext executor = this.beanFactory.getContainerContext();
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
							"[{}] rollback-transaction: error occurred while cancelling service: {}, please check whether the params of method(compensable-service) supports serialization.",
							ByteUtils.byteArrayToString(globalTransactionId),
							ByteUtils.byteArrayToString(branchQualifier));
				} else {
					executor.cancel(invocation);
				}
			} catch (RuntimeException rex) {
				success = false;

				logger.error("[{}] rollback-transaction: error occurred while cancelling service: {}",
						ByteUtils.byteArrayToString(transactionXid.getGlobalTransactionId()), this.archive, rex);
			} finally {
				this.archive = null;
				this.positive = null;
			}
		}

		return success;
	}

	private boolean fireRemoteParticipantRecoveryCancel() {
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
			} catch (XAException ex) {
				success = false;

				logger.error("[{}] rollback-transaction: error occurred while cancelling branch: {}",
						ByteUtils.byteArrayToString(branchXid.getGlobalTransactionId()), this.archive, ex);
			} catch (RuntimeException rex) {
				success = false;

				TransactionXid transactionXid = this.transactionContext.getXid();
				logger.error("[{}] rollback-transaction: error occurred while cancelling branch: {}",
						ByteUtils.byteArrayToString(transactionXid.getGlobalTransactionId()), this.archive, rex);
			}
		}

		return success;
	}

	public void recoveryForgetQuietly() {
		// TODO Auto-generated method stub

	}

	public void onEnlistResource(XAResource xares) {
		if (XAResourceDescriptor.class.isInstance(xares)) {
			XAResourceDescriptor descriptor = (XAResourceDescriptor) xares;
			this.resourceKey = descriptor.getIdentifier();
		} else if (XAResourceArchive.class.isInstance(xares)) {
			XAResourceArchive resourceArchive = (XAResourceArchive) xares;
			XAResourceDescriptor descriptor = resourceArchive.getDescriptor();
			this.resourceKey = descriptor == null ? null : descriptor.getIdentifier();
		}
	}

	public void onDelistResource(XAResource xares) {
	}

	public CompensableArchive getCompensableArchive() {
		return this.archive;
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

}
