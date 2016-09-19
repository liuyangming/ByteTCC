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
package org.bytesoft.bytetcc.supports.zk;

import java.util.List;

import org.bytesoft.transaction.Transaction;
import org.bytesoft.transaction.TransactionRepository;
import org.bytesoft.transaction.xa.TransactionXid;

public class TransactionRepositoryImpl implements TransactionRepository {

	public void putTransaction(TransactionXid xid, Transaction transaction) {
	}

	public Transaction getTransaction(TransactionXid xid) {
		return null;
	}

	public Transaction removeTransaction(TransactionXid xid) {
		return null;
	}

	public void putErrorTransaction(TransactionXid xid, Transaction transaction) {
	}

	public Transaction getErrorTransaction(TransactionXid xid) {
		return null;
	}

	public Transaction removeErrorTransaction(TransactionXid xid) {
		return null;
	}

	public List<Transaction> getErrorTransactionList() {
		return null;
	}

	public List<Transaction> getActiveTransactionList() {
		return null;
	}

}
