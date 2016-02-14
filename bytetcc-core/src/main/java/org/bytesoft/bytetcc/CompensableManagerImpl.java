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
import javax.transaction.InvalidTransactionException;
import javax.transaction.NotSupportedException;
import javax.transaction.RollbackException;
import javax.transaction.SystemException;

import org.bytesoft.compensable.CompensableManager;
import org.bytesoft.transaction.Transaction;

public class CompensableManagerImpl implements CompensableManager {

	public int getTimeoutSeconds() {
		// TODO Auto-generated method stub
		return 0;
	}

	public void setTimeoutSeconds(int timeoutSeconds) {
		// TODO Auto-generated method stub

	}

	public void associateThread(Transaction transaction) {
		// TODO Auto-generated method stub

	}

	public Transaction desociateThread() {
		// TODO Auto-generated method stub
		return null;
	}

	public Transaction getTransactionQuietly() {
		// TODO Auto-generated method stub
		return null;
	}

	public Transaction getTransaction() throws SystemException {
		// TODO Auto-generated method stub
		return null;
	}

	public Transaction suspend() throws SystemException {
		// TODO Auto-generated method stub
		return null;
	}

	public void begin() throws NotSupportedException, SystemException {
		// TODO Auto-generated method stub

	}

	public void commit() throws RollbackException, HeuristicMixedException, HeuristicRollbackException,
			SecurityException, IllegalStateException, SystemException {
		// TODO Auto-generated method stub

	}

	public int getStatus() throws SystemException {
		// TODO Auto-generated method stub
		return 0;
	}

	public void resume(javax.transaction.Transaction tobj) throws InvalidTransactionException, IllegalStateException,
			SystemException {
		// TODO Auto-generated method stub

	}

	public void rollback() throws IllegalStateException, SecurityException, SystemException {
		// TODO Auto-generated method stub

	}

	public void setRollbackOnly() throws IllegalStateException, SystemException {
		// TODO Auto-generated method stub

	}

	public void setTransactionTimeout(int seconds) throws SystemException {
		// TODO Auto-generated method stub

	}

	public boolean isCurrentCompensable() {
		// TODO Auto-generated method stub
		return false;
	}

	public void compensableBegin(Transaction transaction) throws NotSupportedException, SystemException {
		// TODO Auto-generated method stub

	}

	public void compensableCommit() throws RollbackException, HeuristicMixedException, HeuristicRollbackException,
			SecurityException, IllegalStateException, SystemException {
		// TODO Auto-generated method stub

	}

	public void compensableRollback() throws IllegalStateException, SecurityException, SystemException {
		// TODO Auto-generated method stub

	}

}
