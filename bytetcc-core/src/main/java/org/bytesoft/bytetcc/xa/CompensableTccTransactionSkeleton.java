/**
 * Copyright 2014-2015 yangming.liu<liuyangming@gmail.com>.
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
package org.bytesoft.bytetcc.xa;

import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;

import org.apache.log4j.Logger;

public class CompensableTccTransactionSkeleton implements XAResource {
	static final Logger logger = Logger.getLogger(CompensableTccTransactionSkeleton.class.getSimpleName());

	public void commit(Xid xid, boolean opc) throws XAException {
		// TransactionXid transactionXid = (TransactionXid) xid;
		// TransactionConfigurator configurator = TransactionConfigurator.getInstance();
		// CompensableTransactionManager transactionManager = configurator.getTransactionManager();
		// TransactionRepository repository = configurator.getTransactionRepository();
		// CompensableTccTransaction transaction = (CompensableTccTransaction)
		// repository.getTransaction(transactionXid);
		// if (transaction == null) {
		// throw new XAException(XAException.XAER_NOTA);
		// } else if (transaction.getStatus() == Status.STATUS_ROLLEDBACK) {
		// throw new XAException(XAException.XA_HEURRB);
		// } else if (transaction.getStatus() == Status.STATUS_COMMITTED) {
		// return;
		// } else if (transaction.getStatus() == Status.STATUS_MARKED_ROLLBACK) {
		// this.rollback(xid);
		// throw new XAException(XAException.XA_HEURMIX);
		// } else if (transaction.getStatus() != Status.STATUS_ACTIVE) {
		// // ignore
		// }
		//
		// TransactionContext transactionContext = transaction.getTransactionContext();
		//
		// TransactionXid globalXid = transactionContext.getGlobalXid();
		// TransactionConfigurator transactionConfigurator = TransactionConfigurator.getInstance();
		// TransactionRepository transactionRepository = transactionConfigurator.getTransactionRepository();
		// CompensableTransactionLogger transactionLogger = transactionConfigurator.getTransactionLogger();
		//
		// // transaction.setTransactionStatus(Status.STATUS_PREPARING);
		// // transactionLogger.updateTransaction(transaction.getTransactionArchive());
		// // transaction.setTransactionStatus(Status.STATUS_PREPARED);
		// // transaction.setCompensableStatus(CompensableTccTransaction.STATUS_TRIED);
		//
		// transaction.setTransactionStatus(Status.STATUS_COMMITTING);
		// transaction.setCompensableStatus(CompensableTccTransaction.STATUS_CONFIRMING);
		//
		// transactionLogger.updateTransaction(transaction.getTransactionArchive());
		//
		// try {
		// transactionManager.processNativeConfirm(transaction);
		// } catch (RuntimeException ex) {
		// transactionRepository.putErrorTransaction(globalXid, transaction);
		// throw new XAException(XAException.XAER_RMERR);
		// }
		//
		// transaction.setCompensableStatus(CompensableTccTransaction.STATUS_CONFIRMED);
		// transactionLogger.updateTransaction(transaction.getTransactionArchive());
		//
		// try {
		// transaction.remoteConfirm();
		// } catch (SystemException ex) {
		// transactionRepository.putErrorTransaction(globalXid, transaction);
		// throw new XAException(XAException.XAER_RMERR);
		// } catch (RemoteException rex) {
		// transactionRepository.putErrorTransaction(globalXid, transaction);
		// throw new XAException(XAException.XAER_RMERR);
		// } catch (RuntimeException rex) {
		// transactionRepository.putErrorTransaction(globalXid, transaction);
		// throw new XAException(XAException.XAER_RMERR);
		// }
		//
		// transaction.setTransactionStatus(Status.STATUS_COMMITTED);
		// transactionLogger.deleteTransaction(transaction.getTransactionArchive());
		//
		// logger.info(String.format("<%s> commit transaction branch successfully.",
		// ByteUtils.byteArrayToString(globalXid.getGlobalTransactionId())));
		//
		// transactionRepository.removeTransaction(globalXid);
		// transactionRepository.removeErrorTransaction(globalXid);
	}

	public void rollback(Xid xid) throws XAException {
		// TransactionXid transactionXid = (TransactionXid) xid;
		// TransactionConfigurator configurator = TransactionConfigurator.getInstance();
		// CompensableTransactionManager transactionManager = configurator.getTransactionManager();
		// TransactionRepository repository = configurator.getTransactionRepository();
		// CompensableTccTransaction transaction = (CompensableTccTransaction)
		// repository.getTransaction(transactionXid);
		//
		// if (transaction == null) {
		// throw new IllegalStateException();
		// } else if (transaction.getStatus() == Status.STATUS_ROLLEDBACK) {
		// return;
		// } else if (transaction.getStatus() == Status.STATUS_COMMITTED) {
		// throw new XAException(XAException.XAER_RMERR);
		// }
		//
		// TransactionContext transactionContext = transaction.getTransactionContext();
		//
		// TransactionXid globalXid = transactionContext.getGlobalXid();
		// TransactionConfigurator transactionConfigurator = TransactionConfigurator.getInstance();
		// TransactionRepository transactionRepository = transactionConfigurator.getTransactionRepository();
		// CompensableTransactionLogger transactionLogger = transactionConfigurator.getTransactionLogger();
		//
		// transaction.setTransactionStatus(Status.STATUS_ROLLING_BACK);
		// transaction.setCompensableStatus(CompensableTccTransaction.STATUS_CANCELLING);
		// transactionLogger.updateTransaction(transaction.getTransactionArchive());
		//
		// try {
		// transactionManager.processNativeCancel(transaction);
		// } catch (RuntimeException ex) {
		// transactionRepository.putErrorTransaction(globalXid, transaction);
		// throw new XAException(XAException.XAER_RMERR);
		// }
		//
		// transaction.setCompensableStatus(CompensableTccTransaction.STATUS_CANCELLED);
		// transactionLogger.updateTransaction(transaction.getTransactionArchive());
		//
		// try {
		// transaction.remoteCancel();
		// } catch (SystemException ex) {
		// transactionRepository.putErrorTransaction(globalXid, transaction);
		// throw new XAException(XAException.XAER_RMERR);
		// } catch (RemoteException rex) {
		// transactionRepository.putErrorTransaction(globalXid, transaction);
		// throw new XAException(XAException.XAER_RMERR);
		// } catch (RuntimeException rex) {
		// transactionRepository.putErrorTransaction(globalXid, transaction);
		// throw new XAException(XAException.XAER_RMERR);
		// }
		//
		// transaction.setTransactionStatus(Status.STATUS_ROLLEDBACK);
		// transactionLogger.deleteTransaction(transaction.getTransactionArchive());
		//
		// logger.info(String.format("<%s> rollback transaction branch successfully.",
		// ByteUtils.byteArrayToString(globalXid.getGlobalTransactionId())));
		//
		// transactionRepository.removeTransaction(globalXid);
		// transactionRepository.removeErrorTransaction(globalXid);
	}

	public Xid[] recover(int arg0) throws XAException {
		return new Xid[0];
	}

	public void forget(Xid arg0) throws XAException {
	}

	public int getTransactionTimeout() throws XAException {
		throw new XAException(XAException.XAER_PROTO);
	}

	public boolean isSameRM(XAResource arg0) throws XAException {
		throw new XAException(XAException.XAER_PROTO);
	}

	public int prepare(Xid arg0) throws XAException {
		throw new XAException(XAException.XAER_PROTO);
	}

	public boolean setTransactionTimeout(int arg0) throws XAException {
		throw new XAException(XAException.XAER_PROTO);
	}

	public void start(Xid arg0, int arg1) throws XAException {
		throw new XAException(XAException.XAER_PROTO);
	}

	public void end(Xid arg0, int arg1) throws XAException {
		throw new XAException(XAException.XAER_PROTO);
	}

}
