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
package org.bytesoft.compensable.archive;

import javax.transaction.xa.Xid;

import org.bytesoft.common.utils.ByteUtils;
import org.bytesoft.common.utils.CommonUtils;
import org.bytesoft.compensable.CompensableInvocation;

public class CompensableArchive {

	private Xid identifier;
	private boolean coordinator;

	private CompensableInvocation compensable;

	/* try-phase. */
	private String transactionResourceKey;
	private Xid transactionXid;
	private boolean tried;

	/* confirm/cancel phase. */
	private String compensableResourceKey;
	private Xid compensableXid;
	private boolean confirmed;
	private boolean cancelled;

	public String toString() {
		String key = (this.identifier == null || this.identifier.getGlobalTransactionId() == null) ? null
				: ByteUtils.byteArrayToString(this.identifier.getGlobalTransactionId());
		return String.format(
				"[compensable-archive| identifier= %s, transactionKey= %s, transactionXid= %s, compensableKey= %s, compensableXid= %s, confirmed= %s, cancelled= %s]",
				key, this.transactionResourceKey, this.transactionXid, this.compensableResourceKey, this.compensableXid,
				this.confirmed, this.cancelled);
	}

	public int hashCode() {
		int hash = 23;
		hash += 29 * (this.transactionXid == null ? 0 : this.transactionXid.hashCode());
		hash += 31 * (this.compensableXid == null ? 0 : this.compensableXid.hashCode());
		return hash;
	}

	public boolean equals(Object obj) {
		if (obj == null) {
			return false;
		} else if (this.getClass().equals(obj.getClass()) == false) {
			return false;
		}
		CompensableArchive that = (CompensableArchive) obj;
		boolean transactionXidEquals = CommonUtils.equals(this.transactionXid, that.transactionXid);
		boolean compensableXidEquals = CommonUtils.equals(this.compensableXid, that.compensableXid);
		return transactionXidEquals && compensableXidEquals;
	}

	public Xid getIdentifier() {
		return identifier;
	}

	public void setIdentifier(Xid identifier) {
		this.identifier = identifier;
	}

	public Xid getTransactionXid() {
		return transactionXid;
	}

	public void setTransactionXid(Xid transactionXid) {
		this.transactionXid = transactionXid;
	}

	public Xid getCompensableXid() {
		return compensableXid;
	}

	public void setCompensableXid(Xid compensableXid) {
		this.compensableXid = compensableXid;
	}

	public CompensableInvocation getCompensable() {
		return compensable;
	}

	public void setCompensable(CompensableInvocation compensable) {
		this.compensable = compensable;
	}

	public boolean isTried() {
		return tried;
	}

	public void setTried(boolean tried) {
		this.tried = tried;
	}

	public boolean isConfirmed() {
		return confirmed;
	}

	public void setConfirmed(boolean confirmed) {
		this.confirmed = confirmed;
	}

	public boolean isCancelled() {
		return cancelled;
	}

	public void setCancelled(boolean cancelled) {
		this.cancelled = cancelled;
	}

	public boolean isCoordinator() {
		return coordinator;
	}

	public void setCoordinator(boolean coordinator) {
		this.coordinator = coordinator;
	}

	public String getTransactionResourceKey() {
		return transactionResourceKey;
	}

	public void setTransactionResourceKey(String transactionResourceKey) {
		this.transactionResourceKey = transactionResourceKey;
	}

	public String getCompensableResourceKey() {
		return compensableResourceKey;
	}

	public void setCompensableResourceKey(String compensableResourceKey) {
		this.compensableResourceKey = compensableResourceKey;
	}

}
