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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bytesoft.bytetcc.CompensableTransaction;
import org.bytesoft.transaction.xa.TransactionXid;

public class TransactionRepository {
	private final Map<TransactionXid, CompensableTransaction> xidToTxMap = new ConcurrentHashMap<TransactionXid, CompensableTransaction>();
	private final Map<TransactionXid, CompensableTransaction> xidToErrTxMap = new ConcurrentHashMap<TransactionXid, CompensableTransaction>();

	public void putTransaction(TransactionXid globalXid, CompensableTransaction transaction) {
		this.xidToTxMap.put(globalXid, transaction);
	}

	public CompensableTransaction getTransaction(TransactionXid globalXid) {
		return this.xidToTxMap.get(globalXid);
	}

	public CompensableTransaction removeTransaction(TransactionXid globalXid) {
		return this.xidToTxMap.remove(globalXid);
	}

	public void putErrorTransaction(TransactionXid globalXid, CompensableTransaction transaction) {
		this.xidToErrTxMap.put(globalXid, transaction);
	}

	public CompensableTransaction getErrorTransaction(TransactionXid globalXid) {
		return this.xidToErrTxMap.get(globalXid);
	}

	public CompensableTransaction removeErrorTransaction(TransactionXid globalXid) {
		return this.xidToErrTxMap.remove(globalXid);
	}

	public List<CompensableTransaction> getErrorTransactionList() {
		return new ArrayList<CompensableTransaction>(this.xidToErrTxMap.values());
	}

	public List<CompensableTransaction> getActiveTransactionList() {
		return new ArrayList<CompensableTransaction>(this.xidToTxMap.values());
	}

}
