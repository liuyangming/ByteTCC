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

import javax.naming.NamingException;
import javax.naming.Reference;
import javax.naming.Referenceable;
import javax.transaction.HeuristicMixedException;
import javax.transaction.HeuristicRollbackException;
import javax.transaction.NotSupportedException;
import javax.transaction.RollbackException;
import javax.transaction.SystemException;
import javax.transaction.UserTransaction;

import org.bytesoft.compensable.CompensableManager;

public class UserCompensableImpl implements UserTransaction, Referenceable, Serializable {
	private static final long serialVersionUID = 1L;

	private transient CompensableManager compensableManager;

	public void begin() throws NotSupportedException, SystemException {
		this.compensableManager.begin(); // TODO
	}

	public void commit() throws HeuristicMixedException, HeuristicRollbackException, IllegalStateException,
			RollbackException, SecurityException, SystemException {
		this.compensableManager.compensableCommit();
	}

	public int getStatus() throws SystemException {
		return this.compensableManager.getStatus();
	}

	public void rollback() throws IllegalStateException, SecurityException, SystemException {
		this.compensableManager.compensableRollback();
	}

	public void setRollbackOnly() throws IllegalStateException, SystemException {
		this.compensableManager.setRollbackOnly();
	}

	public void setTransactionTimeout(int timeout) throws SystemException {
		this.compensableManager.setTransactionTimeout(timeout);
	}

	public Reference getReference() throws NamingException {
		throw new NamingException("Not supported yet!");
	}

	public CompensableManager getCompensableManager() {
		return compensableManager;
	}

	public void setCompensableManager(CompensableManager compensableManager) {
		this.compensableManager = compensableManager;
	}

}
