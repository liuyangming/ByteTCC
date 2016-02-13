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
package org.bytesoft.bytetcc.internal;

import javax.transaction.Synchronization;

import org.bytesoft.bytetcc.supports.CompensableSynchronization;
import org.bytesoft.transaction.xa.TransactionXid;

public class CompensableSynchronizationImpl extends CompensableSynchronization {
	public Synchronization synchronization;

	public CompensableSynchronizationImpl(TransactionXid globalXid, Synchronization sync) {
		super(globalXid);
		this.synchronization = sync;
	}

	public void afterInitialization(TransactionXid xid) {
		// ignore
	}

	public void beforeCompletion(TransactionXid xid) {
		if (this.synchronization != null) {
			this.synchronization.beforeCompletion();
		}
	}

	public void afterCompletion(TransactionXid xid, int status) {
		if (this.synchronization != null) {
			this.synchronization.afterCompletion(status);
		}
	}
}
