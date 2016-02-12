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
package org.bytesoft.bytetcc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bytesoft.transaction.Transaction;
import org.bytesoft.transaction.TransactionRepository;
import org.bytesoft.transaction.xa.TransactionXid;

public class TransactionRepositoryImpl implements TransactionRepository {
	private final Map<TransactionXid, Transaction> xidToTxMap = new ConcurrentHashMap<TransactionXid, Transaction>();
	private final Map<TransactionXid, Transaction> xidToErrTxMap = new ConcurrentHashMap<TransactionXid, Transaction>();

	public void putTransaction(TransactionXid globalXid, Transaction transaction) {
		this.xidToTxMap.put(globalXid, transaction);
	}

	public Transaction getTransaction(TransactionXid globalXid) {
		return this.xidToTxMap.get(globalXid);
	}

	public Transaction removeTransaction(TransactionXid globalXid) {
		return this.xidToTxMap.remove(globalXid);
	}

	public void putErrorTransaction(TransactionXid globalXid, Transaction transaction) {
		this.xidToErrTxMap.put(globalXid, transaction);
	}

	public Transaction getErrorTransaction(TransactionXid globalXid) {
		return this.xidToErrTxMap.get(globalXid);
	}

	public Transaction removeErrorTransaction(TransactionXid globalXid) {
		return this.xidToErrTxMap.remove(globalXid);
	}

	public List<Transaction> getErrorTransactionList() {
		return new ArrayList<Transaction>(this.xidToErrTxMap.values());
	}

	public List<Transaction> getActiveTransactionList() {
		return new ArrayList<Transaction>(this.xidToTxMap.values());
	}

}
