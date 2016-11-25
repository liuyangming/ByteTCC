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
package org.bytesoft.compensable;

public class TransactionContext extends org.bytesoft.transaction.TransactionContext {
	private static final long serialVersionUID = 1L;

	private transient boolean compensating;
	private transient int propagationLevel;

	private boolean compensable;

	public TransactionContext clone() {
		TransactionContext that = new TransactionContext();
		that.xid = this.xid.clone();
		that.createdTime = System.currentTimeMillis();
		that.expiredTime = this.expiredTime;
		that.compensable = this.compensable;
		return that;
	}

	public boolean isCompensating() {
		return compensating;
	}

	public void setCompensating(boolean compensating) {
		this.compensating = compensating;
	}

	public boolean isCompensable() {
		return compensable;
	}

	public void setCompensable(boolean compensable) {
		this.compensable = compensable;
	}

	public int getPropagationLevel() {
		return propagationLevel;
	}

	public void setPropagationLevel(int propagationLevel) {
		this.propagationLevel = propagationLevel;
	}

}
