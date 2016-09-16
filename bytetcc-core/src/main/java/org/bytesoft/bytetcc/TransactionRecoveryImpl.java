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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.transaction.Status;
import javax.transaction.SystemException;

import org.bytesoft.common.utils.ByteUtils;
import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.aware.CompensableBeanFactoryAware;
import org.bytesoft.compensable.logging.CompensableLogger;
import org.bytesoft.transaction.CommitRequiredException;
import org.bytesoft.transaction.RollbackRequiredException;
import org.bytesoft.transaction.Transaction;
import org.bytesoft.transaction.TransactionContext;
import org.bytesoft.transaction.TransactionRecovery;
import org.bytesoft.transaction.TransactionRepository;
import org.bytesoft.transaction.archive.TransactionArchive;
import org.bytesoft.transaction.recovery.TransactionRecoveryCallback;
import org.bytesoft.transaction.recovery.TransactionRecoveryListener;
import org.bytesoft.transaction.xa.TransactionXid;
import org.bytesoft.transaction.xa.XidFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TransactionRecoveryImpl implements TransactionRecovery, TransactionRecoveryListener,
		CompensableBeanFactoryAware {
	static final Logger logger = LoggerFactory.getLogger(TransactionRecoveryImpl.class.getSimpleName());

	private CompensableBeanFactory beanFactory;

	private final Map<TransactionXid, Transaction> recovered = new HashMap<TransactionXid, Transaction>();

	public void onRecovery(Transaction transaction) {
		TransactionContext transactionContext = transaction.getTransactionContext();
		TransactionXid xid = transactionContext.getXid();

		XidFactory xidFactory = this.beanFactory.getCompensableXidFactory();
		TransactionXid globalXid = xidFactory.createGlobalXid(xid.getGlobalTransactionId());

		this.recovered.put(globalXid, transaction);
	}

	public void startRecovery() {
		this.fireTransactionStartRecovery();
		this.fireCompensableStartRecovery();
	}

	private void fireTransactionStartRecovery() {
		TransactionRecovery transactionRecovery = this.beanFactory.getTransactionRecovery();
		transactionRecovery.startRecovery();
	}

	private void fireCompensableStartRecovery() {
		final TransactionRepository transactionRepository = this.beanFactory.getTransactionRepository();
		CompensableLogger compensableLogger = this.beanFactory.getCompensableLogger();

		compensableLogger.recover(new TransactionRecoveryCallback() {
			public void recover(TransactionArchive archive) {
				this.recover((org.bytesoft.compensable.archive.TransactionArchive) archive);
			}

			public void recover(org.bytesoft.compensable.archive.TransactionArchive archive) {
				XidFactory transactionXidFactory = beanFactory.getTransactionXidFactory();
				// XidFactory compensableXidFactory = beanFactory.getCompensableXidFactory();

				CompensableTransactionImpl transaction = reconstructTransaction(archive);
				TransactionContext transactionContext = transaction.getTransactionContext();
				if (transactionContext.isCompensable()) {
					// List<CompensableArchive> compensableArchiveList = archive.getCompensableResourceList();
					// for (int i = 0; i < compensableArchiveList.size(); i++) {
					// CompensableArchive compensableArchive = compensableArchiveList.get(i);
					// Xid transactionXid = compensableArchive.getCompensableXid();
					// TransactionXid compensableXid = xidFactory.createGlobalXid(transactionXid.getBranchQualifier());
					//
					// Transaction tx = recovered.get(compensableXid);
					// if (tx != null) {
					// tx.setTransactionalExtra(transaction);
					// transaction.setTransactionalExtra(tx);
					// }
					//
					// }
				} else {
					TransactionXid compensableXid = transactionContext.getXid();
					TransactionXid transactionXid = transactionXidFactory.createGlobalXid(compensableXid
							.getGlobalTransactionId());
					Transaction tx = recovered.get(transactionXid);
					if (tx != null) {
						tx.setTransactionalExtra(transaction);
						transaction.setTransactionalExtra(tx);
					}
					transactionRepository.putTransaction(compensableXid, transaction);
					transactionRepository.putErrorTransaction(compensableXid, transaction);
				}

			}
		});
	}

	public CompensableTransactionImpl reconstructTransaction(TransactionArchive archive) {
		return null;
	}

	public void timingRecover() {
		TransactionRepository transactionRepository = beanFactory.getTransactionRepository();
		List<Transaction> transactions = transactionRepository.getErrorTransactionList();
		int total = transactions == null ? 0 : transactions.size();
		int value = 0;
		for (int i = 0; transactions != null && i < transactions.size(); i++) {
			Transaction transaction = transactions.get(i);
			TransactionContext transactionContext = transaction.getTransactionContext();
			TransactionXid xid = transactionContext.getXid();
			try {
				this.recoverTransaction(transaction);
				transaction.recoveryForgetQuietly();
			} catch (CommitRequiredException ex) {
				logger.debug("[{}] recover: branch={}, message= commit-required",
						ByteUtils.byteArrayToString(xid.getGlobalTransactionId()),
						ByteUtils.byteArrayToString(xid.getBranchQualifier()));
				continue;
			} catch (RollbackRequiredException ex) {
				logger.debug("[{}] recover: branch={}, message= rollback-required",
						ByteUtils.byteArrayToString(xid.getGlobalTransactionId()),
						ByteUtils.byteArrayToString(xid.getBranchQualifier()));
				continue;
			} catch (SystemException ex) {
				logger.debug("[{}] recover: branch={}, message= {}",
						ByteUtils.byteArrayToString(xid.getGlobalTransactionId()),
						ByteUtils.byteArrayToString(xid.getBranchQualifier()), ex.getMessage());
				continue;
			} catch (RuntimeException ex) {
				logger.debug("[{}] recover: branch={}, message= {}",
						ByteUtils.byteArrayToString(xid.getGlobalTransactionId()),
						ByteUtils.byteArrayToString(xid.getBranchQualifier()), ex.getMessage());
				continue;
			}
		}
		logger.info("[transaction-recovery] total= {}, success= {}", total, value);
	}

	public synchronized void recoverTransaction(Transaction transaction) throws CommitRequiredException,
			RollbackRequiredException, SystemException {

		TransactionContext transactionContext = transaction.getTransactionContext();
		if (transactionContext.isCoordinator()) {
			this.recoverCoordinator(transaction);
		} // end-if (coordinator)

	}

	public synchronized void recoverCoordinator(Transaction transaction) throws CommitRequiredException,
			RollbackRequiredException, SystemException {

		switch (transaction.getTransactionStatus()) {
		case Status.STATUS_ACTIVE:
		case Status.STATUS_MARKED_ROLLBACK:
		case Status.STATUS_PREPARING:
		case Status.STATUS_ROLLING_BACK:
		case Status.STATUS_UNKNOWN:
			transaction.recoveryRollback();
			break;
		case Status.STATUS_PREPARED:
		case Status.STATUS_COMMITTING:
			transaction.recoveryCommit();
			break;
		case Status.STATUS_COMMITTED:
		case Status.STATUS_ROLLEDBACK:
		default:
			// ignore
		}

	}

	public void setBeanFactory(CompensableBeanFactory tbf) {
		this.beanFactory = tbf;
	}

}
