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
package org.bytesoft.bytetcc.common;

import java.io.Serializable;

import javax.transaction.xa.Xid;

import org.bytesoft.transaction.xa.TransactionXid;
import org.bytesoft.transaction.xa.XidFactory;

public class TransactionCommonXid extends TransactionXid implements Xid, Serializable {
	private static final long serialVersionUID = 1L;

	public TransactionCommonXid(byte[] global) {
		this(global, new byte[0]);
	}

	public TransactionCommonXid(byte[] global, byte[] branch) {
		super(global, branch);
	}

	public int getFormatId() {
		return XidFactory.TCC_FORMAT_ID;
	}

	public TransactionXid getGlobalXid() {
		if (this.globalTransactionId == null || this.globalTransactionId.length == 0) {
			throw new IllegalStateException();
		} else if (this.branchQualifier != null && this.branchQualifier.length > 0) {
			TransactionConfigurator transactionConfigurator = TransactionConfigurator.getInstance();
			XidFactory xidFactory = transactionConfigurator.getXidFactory();
			return xidFactory.createGlobalXid(this.globalTransactionId);
		} else {
			return this;
		}
	}

	public TransactionXid createBranchXid() {
		if (this.globalTransactionId == null || this.globalTransactionId.length == 0) {
			throw new IllegalStateException();
		} else if (this.branchQualifier != null && this.branchQualifier.length > 0) {
			throw new IllegalStateException();
		} else {
			TransactionConfigurator transactionConfigurator = TransactionConfigurator.getInstance();
			XidFactory xidFactory = transactionConfigurator.getXidFactory();
			return xidFactory.createBranchXid(this);
		}
	}

}
