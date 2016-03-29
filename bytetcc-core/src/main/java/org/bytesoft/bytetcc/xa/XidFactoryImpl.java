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
package org.bytesoft.bytetcc.xa;

import org.bytesoft.transaction.xa.TransactionXid;
import org.bytesoft.transaction.xa.XidFactory;

public class XidFactoryImpl extends org.bytesoft.bytejta.xa.XidFactoryImpl implements XidFactory {

	public TransactionXid createGlobalXid() {
		TransactionXid xid = super.createGlobalXid();
		xid.setFormatId(XidFactory.TCC_FORMAT_ID);
		return xid;
	}

	public TransactionXid createGlobalXid(byte[] globalTransactionId) {
		TransactionXid xid = super.createGlobalXid(globalTransactionId);
		xid.setFormatId(XidFactory.TCC_FORMAT_ID);
		return xid;
	}

	public TransactionXid createBranchXid(TransactionXid globalXid) {
		TransactionXid xid = super.createBranchXid(globalXid);
		xid.setFormatId(XidFactory.TCC_FORMAT_ID);
		return xid;
	}

	public TransactionXid createBranchXid(TransactionXid globalXid, byte[] branchQualifier) {
		TransactionXid xid = super.createBranchXid(globalXid, branchQualifier);
		xid.setFormatId(XidFactory.TCC_FORMAT_ID);
		return xid;
	}

}
