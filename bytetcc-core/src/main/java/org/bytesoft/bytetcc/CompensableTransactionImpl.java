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

	private boolean coordinatorTried;
	private int transactionVote;
	private int transactionStatus;
	/* current comensable-decision. */
	private transient Boolean decision;
	/**
	 * current compense-archive. <br />
	 * only for participant in try phase; <br />
	 * for both coordinator and participant in confirm/cancel phase.
	 */
	private transient CompensableArchive archive;

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
		boolean nativeSuccess = this.fireCompensableInvocationConfirm();
		boolean remoteSuccess = this.fireRemoteCoordinatorConfirm();
		if (nativeSuccess == false || remoteSuccess == false) {
			throw new SystemException();
		}
	}

	private boolean fireCompensableInvocationConfirm() {
		boolean success = true;

		ContainerContext executor = this.beanFactory.getContainerContext();
		for (int i = 0; i < this.archiveList.size(); i++) {
			CompensableArchive current = this.archiveList.get(i);
			if (current.isConfirmed()) {
				continue;
			}

			TransactionXid transactionXid = this.transactionContext.getXid();
			try {
				this.decision = true;
				this.archive = current;
				CompensableInvocation invocation = current.getCompensable();
				if (invocation == null) {
					success = false;

					byte[] globalTransactionId = transactionXid.getGlobalTransactionId();
					byte[] branchQualifier = current.getXid().getBranchQualifier();
					logger.error(
							"[{}] commit-transaction: error occurred while confirming branch: {}, please check whether the params of method(compensable-service) supports serialization.",
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
				this.decision = null;
			}
		}

		return success;
	}

	private boolean fireRemoteCoordinatorConfirm() {
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

				logger.error("[{}] commit-transaction: error occurred while confirming branch: {}",
						ByteUtils.byteArrayToString(branchXid.getGlobalTransactionId()), this.archive, ex);
			} catch (RuntimeException rex) {
				success = false;

				TransactionXid transactionXid = this.transactionContext.getXid();
				logger.error("[{}] commit-transaction: error occurred while confirming service: {}",
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
		boolean coordinator = this.transactionContext.isCoordinator();
		if (coordinator && this.coordinatorTried == false) {
			boolean remoteSuccess = this.fireRemoteCoordinatorCancel();
			if (remoteSuccess == false) {
				throw new SystemException();
			}
		} else {
			boolean nativeSuccess = this.fireCompensableInvocationCancel();
			boolean remoteSuccess = this.fireRemoteCoordinatorCancel();
			if (nativeSuccess == false || remoteSuccess == false) {
				throw new SystemException();
			}
		}
	}

	private boolean fireCompensableInvocationCancel() {
		boolean success = true;

		ContainerContext executor = this.beanFactory.getContainerContext();
		for (int i = 0; i < this.archiveList.size(); i++) {
			CompensableArchive current = this.archiveList.get(i);
			if (current.isCancelled()) {
				continue;
			}

			TransactionXid transactionXid = this.transactionContext.getXid();
			try {
				this.decision = false;
				this.archive = current;
				CompensableInvocation invocation = current.getCompensable();
				if (invocation == null) {
					success = false;

					byte[] globalTransactionId = transactionXid.getGlobalTransactionId();
					byte[] branchQualifier = current.getXid().getBranchQualifier();
					logger.error(
							"[{}] rollback-transaction: error occurred while cancelling branch: {}, please check whether the params of method(compensable-service) supports serialization.",
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
				this.decision = null;
			}
		}

		return success;
	}

	private boolean fireRemoteCoordinatorCancel() {
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
				logger.error("[{}] rollback-transaction: error occurred while cancelling service: {}",
						ByteUtils.byteArrayToString(transactionXid.getGlobalTransactionId()), this.archive, rex);
			}
		}

		return success;
	}

	public void recoveryCommit() throws CommitRequiredException, SystemException {
		// TODO Auto-generated method stub

	}

	public void recoveryRollback() throws RollbackRequiredException, SystemException {
		// TODO Auto-generated method stub

	}

	public void recoveryForgetQuietly() {
		// TODO Auto-generated method stub

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

	public void registerCompensableInvocation(CompensableInvocation invocation) {
		CompensableArchive archive = new CompensableArchive();
		archive.setCompensable(invocation);
		if (this.transactionContext.isCoordinator() == false) {
			this.archive = archive;
		}
		this.archiveList.add(archive);
	}

	public void registerSynchronization(Synchronization sync)
			throws RollbackException, IllegalStateException, SystemException {
	}

	public void registerTransactionListener(TransactionListener listener) {
	}

	// public void onPrepareStart(TransactionXid xid) {}
	// public void onPrepareSuccess(TransactionXid xid) {}
	// public void onPrepareFailure(TransactionXid xid) {}

	public void onCommitStart(TransactionXid xid) {
		if (this.transactionContext.isCompensating()) {
			this.archive.setXid(xid);
			// CompensableLogger transactionLogger =
			// this.beanFactory.getCompensableLogger();
			// transactionLogger.updateCompensable(this.archive);
		}
	}

	public void onCommitSuccess(TransactionXid xid) {
		if (this.transactionContext.isCompensating()) {
			if (this.decision == null) {
				// ignore
			} else if (this.decision) {
				this.archive.setConfirmed(true);
			} else {
				this.archive.setCancelled(true);
			}
			CompensableLogger transactionLogger = this.beanFactory.getCompensableLogger();
			transactionLogger.updateCompensable(this.archive);
		} else /* try-phase */ {
			if (this.transactionContext.isCoordinator()) {
				this.coordinatorTried = true;
			} else {
				this.archive.setParticipantTried(true);
			}
		}
	}

	// public void onCommitFailure(TransactionXid xid) {}

	public void onCommitHeuristicMixed(TransactionXid xid) {
		if (this.transactionContext.isCompensating()) {
			if (this.decision == null) {
				// ignore
			} else if (this.decision) {
				this.archive.setTxMixed(true);
				this.archive.setConfirmed(true);
			} else {
				this.archive.setTxMixed(true);
				this.archive.setCancelled(true);
			}
			CompensableLogger transactionLogger = this.beanFactory.getCompensableLogger();
			transactionLogger.updateCompensable(this.archive);
		} else /* try-phase */ {
			if (this.transactionContext.isCoordinator()) {
				this.coordinatorTried = true;
			} else {
				this.archive.setParticipantTried(true);
			}
		}
	}

	// public void onCommitHeuristicRolledback(TransactionXid xid) {}
	// public void onRollbackStart(TransactionXid xid) {}
	// public void onRollbackSuccess(TransactionXid xid) {}
	// public void onRollbackFailure(TransactionXid xid) {}

	public void setRollbackOnly() throws IllegalStateException, SystemException {
		throw new IllegalStateException();
	}

	public void setRollbackOnlyQuietly() {
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

	public CompensableArchive getCurrentArchive() {
		return this.archive;
	}

	public void setCurrentArchive(CompensableArchive archive) {
		this.archive = archive;
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
