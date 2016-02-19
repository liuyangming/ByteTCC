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

import javax.transaction.HeuristicMixedException;
import javax.transaction.HeuristicRollbackException;
import javax.transaction.RollbackException;
import javax.transaction.Synchronization;
import javax.transaction.SystemException;
import javax.transaction.xa.XAResource;

import org.bytesoft.compensable.CompensableTransaction;
import org.bytesoft.transaction.CommitRequiredException;
import org.bytesoft.transaction.RollbackRequiredException;
import org.bytesoft.transaction.Transaction;
import org.bytesoft.transaction.TransactionBeanFactory;
import org.bytesoft.transaction.TransactionContext;
import org.bytesoft.transaction.archive.TransactionArchive;
import org.bytesoft.transaction.supports.TransactionListener;
import org.bytesoft.transaction.xa.TransactionXid;

public class CompensableTransactionImpl implements CompensableTransaction {

	private Transaction transaction;
	private TransactionContext transactionContext;

	public CompensableTransactionImpl(TransactionContext txContext) {
		this.transactionContext = txContext;
	}

	public void setRollbackOnlyQuietly() {
		// TODO Auto-generated method stub

	}

	public int getTransactionStatus() {
		// TODO Auto-generated method stub
		return 0;
	}

	public void setTransactionStatus(int status) {
		// TODO Auto-generated method stub

	}

	public void resume() throws SystemException {
		// TODO Auto-generated method stub

	}

	public void suspend() throws SystemException {
		// TODO Auto-generated method stub

	}

	public boolean isTiming() {
		// TODO Auto-generated method stub
		return false;
	}

	public void setTransactionTimeout(int seconds) {
		// TODO Auto-generated method stub

	}

	public void registerTransactionListener(TransactionListener listener) {
		// TODO Auto-generated method stub

	}

	public TransactionContext getTransactionContext() {
		return this.transactionContext;
	}

	public TransactionArchive getTransactionArchive() {
		// TODO Auto-generated method stub
		return null;
	}

	public void participantPrepare() throws RollbackRequiredException, CommitRequiredException {
		// TODO Auto-generated method stub

	}

	public void participantCommit() throws RollbackException, HeuristicMixedException, HeuristicRollbackException,
			SecurityException, IllegalStateException, CommitRequiredException, SystemException {
		// TODO Auto-generated method stub

	}

	public void recoveryForgetQuietly() {
		// TODO Auto-generated method stub

	}

	public void recoveryRollback() throws RollbackRequiredException, SystemException {
		// TODO Auto-generated method stub

	}

	public void recoveryCommit() throws CommitRequiredException, SystemException {
		// TODO Auto-generated method stub

	}

	public void commit() throws RollbackException, HeuristicMixedException, HeuristicRollbackException,
			SecurityException, IllegalStateException, SystemException {
		// TODO Auto-generated method stub

	}

	public boolean delistResource(XAResource xaRes, int flag) throws IllegalStateException, SystemException {
		// TODO Auto-generated method stub
		return false;
	}

	public boolean enlistResource(XAResource xaRes) throws RollbackException, IllegalStateException, SystemException {
		// TODO Auto-generated method stub
		return false;
	}

	public int getStatus() throws SystemException {
		// TODO Auto-generated method stub
		return 0;
	}

	public void registerSynchronization(Synchronization sync) throws RollbackException, IllegalStateException,
			SystemException {
		// TODO Auto-generated method stub

	}

	public void rollback() throws IllegalStateException, SystemException {
		// TODO Auto-generated method stub

	}

	public void setRollbackOnly() throws IllegalStateException, SystemException {
		// TODO Auto-generated method stub

	}

	public void setBeanFactory(TransactionBeanFactory tbf) {
		// TODO Auto-generated method stub

	}

	public void prepareStart(TransactionXid xid) {
		// TODO Auto-generated method stub

	}

	public void prepareSuccess(TransactionXid xid) {
		// TODO Auto-generated method stub

	}

	public void prepareFailure(TransactionXid xid) {
		// TODO Auto-generated method stub

	}

	public void commitStart(TransactionXid xid) {
		// TODO Auto-generated method stub

	}

	public void commitSuccess(TransactionXid xid) {
		// TODO Auto-generated method stub

	}

	public void commitFailure(TransactionXid xid) {
		// TODO Auto-generated method stub

	}

	public void commitHeuristicMixed(TransactionXid xid) {
		// TODO Auto-generated method stub

	}

	public void commitHeuristicRolledback(TransactionXid xid) {
		// TODO Auto-generated method stub

	}

	public void rollbackStart(TransactionXid xid) {
		// TODO Auto-generated method stub

	}

	public void rollbackSuccess(TransactionXid xid) {
		// TODO Auto-generated method stub

	}

	public void rollbackFailure(TransactionXid xid) {
		// TODO Auto-generated method stub

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
