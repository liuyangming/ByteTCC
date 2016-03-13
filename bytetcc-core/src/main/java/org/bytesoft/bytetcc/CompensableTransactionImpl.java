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
import javax.transaction.xa.XAResource;

import org.apache.log4j.Logger;
import org.bytesoft.common.utils.ByteUtils;
import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.CompensableInvocation;
import org.bytesoft.compensable.CompensableInvocationExecutor;
import org.bytesoft.compensable.CompensableTransaction;
import org.bytesoft.compensable.archive.CompensableArchive;
import org.bytesoft.transaction.CommitRequiredException;
import org.bytesoft.transaction.RollbackRequiredException;
import org.bytesoft.transaction.Transaction;
import org.bytesoft.transaction.TransactionContext;
import org.bytesoft.transaction.archive.TransactionArchive;
import org.bytesoft.transaction.supports.TransactionListener;
import org.bytesoft.transaction.xa.TransactionXid;

public class CompensableTransactionImpl implements CompensableTransaction {
	static final Logger logger = Logger.getLogger(CompensableTransactionImpl.class.getSimpleName());

	private final TransactionContext transactionContext;
	private final List<CompensableArchive> archiveList = new ArrayList<CompensableArchive>();
	private Transaction transaction;
	private CompensableBeanFactory beanFactory;

	/* current comensable-archive and compense decision. */
	private transient Boolean decision;
	private transient CompensableArchive archive;

	public CompensableTransactionImpl(TransactionContext txContext) {
		this.transactionContext = txContext;
	}

	public TransactionArchive getTransactionArchive() {
		// TODO Auto-generated method stub
		return null;
	}

	public void commit() throws RollbackException, HeuristicMixedException, HeuristicRollbackException, SecurityException,
			IllegalStateException, SystemException {
		CompensableInvocationExecutor executor = this.beanFactory.getCompensableInvocationExecutor();
		for (int i = 0; i < this.archiveList.size(); i++) {
			CompensableArchive current = this.archiveList.get(i);
			if (current.isConfirmed()) {
				continue;
			}

			try {
				this.decision = true;
				this.archive = current;
				executor.confirm(current.getCompensable());
			} catch (RuntimeException rex) {
				TransactionXid transactionXid = this.transactionContext.getXid();
				logger.error(
						String.format("[%s] commit-transaction: error occurred while confirming service: %s",
								ByteUtils.byteArrayToString(transactionXid.getGlobalTransactionId()), this.archive), rex);
			} finally {
				this.archive = null;
				this.decision = null;
			}
		}
	}

	public void participantPrepare() throws RollbackRequiredException, CommitRequiredException {
		throw new RuntimeException("Not supported!");
	}

	public void participantCommit() throws RollbackException, HeuristicMixedException, HeuristicRollbackException,
			SecurityException, IllegalStateException, CommitRequiredException, SystemException {
		throw new SystemException("Not supported!");
	}

	public void rollback() throws IllegalStateException, SystemException {
		CompensableInvocationExecutor executor = this.beanFactory.getCompensableInvocationExecutor();
		for (int i = 0; i < this.archiveList.size(); i++) {
			CompensableArchive current = this.archiveList.get(i);
			if (current.isCancelled()) {
				continue;
			}

			try {
				this.decision = false;
				this.archive = current;
				executor.cancel(current.getCompensable());
			} catch (RuntimeException rex) {
				TransactionXid transactionXid = this.transactionContext.getXid();
				logger.error(
						String.format("[%s] commit-transaction: error occurred while cancelling service: %s",
								ByteUtils.byteArrayToString(transactionXid.getGlobalTransactionId()), this.archive), rex);
			} finally {
				this.archive = null;
				this.decision = null;
			}
		}
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
		// TODO Auto-generated method stub
		return false;
	}

	public boolean delistResource(XAResource xaRes, int flag) throws IllegalStateException, SystemException {
		// TODO Auto-generated method stub
		return false;
	}

	public void resume() throws SystemException {
		// TODO Auto-generated method stub

	}

	public void suspend() throws SystemException {
		// TODO Auto-generated method stub

	}

	public void registerCompensableInvocation(CompensableInvocation invocation) {
		CompensableArchive archive = new CompensableArchive();
		archive.setCompensable(invocation);
		this.archiveList.add(archive);
	}

	public void registerSynchronization(Synchronization sync) throws RollbackException, IllegalStateException, SystemException {
	}

	public void registerTransactionListener(TransactionListener listener) {
	}

	public void onPrepareStart(TransactionXid xid) {
	}

	public void onPrepareSuccess(TransactionXid xid) {
	}

	public void onPrepareFailure(TransactionXid xid) {
	}

	public void onCommitStart(TransactionXid xid) {
		this.archive.setXid(xid);
		// TODO logger
	}

	public void onCommitSuccess(TransactionXid xid) {
		if (this.decision == null) {
			// ignore
		} else if (this.decision) {
			this.archive.setConfirmed(true);
		} else {
			this.archive.setCancelled(true);
		}
		// TODO logger
	}

	public void onCommitFailure(TransactionXid xid) {
		// TODO logger
	}

	public void onCommitHeuristicMixed(TransactionXid xid) {
		if (this.decision == null) {
			// ignore
		} else if (this.decision) {
			this.archive.setTxMixed(true);
			this.archive.setConfirmed(true);
		} else {
			this.archive.setTxMixed(true);
			this.archive.setCancelled(true);
		}
		// TODO logger
	}

	public void onCommitHeuristicRolledback(TransactionXid xid) {
	}

	public void onRollbackStart(TransactionXid xid) {
	}

	public void onRollbackSuccess(TransactionXid xid) {
	}

	public void onRollbackFailure(TransactionXid xid) {
		// TODO logger
	}

	public void setRollbackOnly() throws IllegalStateException, SystemException {
		// TODO Auto-generated method stub
	}

	public void setRollbackOnlyQuietly() {
		// TODO Auto-generated method stub

	}

	public int getStatus() throws SystemException {
		// TODO Auto-generated method stub
		return 0;
	}

	public int getTransactionStatus() {
		// TODO Auto-generated method stub
		return 0;
	}

	public void setTransactionStatus(int status) {
		// TODO Auto-generated method stub

	}

	public boolean isTiming() {
		return false;
	}

	public void setTransactionTimeout(int seconds) {
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

}
