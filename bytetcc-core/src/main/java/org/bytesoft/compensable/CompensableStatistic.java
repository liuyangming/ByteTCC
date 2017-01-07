/**
 * Copyright 2014-2017 yangming.liu<bytefox@126.com>.
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
package org.bytesoft.compensable;

import org.bytesoft.compensable.archive.CompensableArchive;
import org.bytesoft.transaction.xa.TransactionXid;

public interface CompensableStatistic {

	/* transaction */
	public void fireBeginTransaction(TransactionXid xid);

	public void fireCommitTransactionStart(TransactionXid xid);

	public void fireCommitTransactionSuccess(TransactionXid xid);

	public void fireCommitTransactionFailure(TransactionXid xid);

	public void fireRollbackTransactionStart(TransactionXid xid);

	public void fireRollbackTransactionSuccess(TransactionXid xid);

	public void fireRollbackTransactionFailure(TransactionXid xid);

	public void fireCleanupTransaction(TransactionXid xid);

	public void fireRecoverTransaction(TransactionXid xid);

	/* phase */
	public void fireTryPhaseSuccess(TransactionXid xid);

	public void fireTryPhaseFailure(TransactionXid xid);

	public void fireCancelPhaseSuccess(TransactionXid xid);

	public void fireCancelPhaseFailure(TransactionXid xid);

	public void fireConfirmPhaseSuccess(TransactionXid xid);

	public void fireConfirmPhaseFailure(TransactionXid xid);

	/* compensable */
	public void fireTryCompensableSuccess(TransactionXid xid, CompensableArchive compensable);

	public void fireTryCompensableFailure(TransactionXid xid, CompensableArchive compensable);

	public void fireCancelCompensableSuccess(TransactionXid xid, CompensableArchive compensable);

	public void fireCancelCompensableFailure(TransactionXid xid, CompensableArchive compensable);

	public void fireConfirmCompensableSuccess(TransactionXid xid, CompensableArchive compensable);

	public void fireConfirmCompensableFailure(TransactionXid xid, CompensableArchive compensable);

}
