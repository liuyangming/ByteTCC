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
package org.bytesoft.bytetcc.logger;

import java.util.List;

import org.apache.log4j.Logger;
import org.bytesoft.compensable.archive.CompensableArchive;
import org.bytesoft.compensable.archive.TransactionArchive;
import org.bytesoft.compensable.logger.CompensableLogger;
import org.bytesoft.transaction.archive.XAResourceArchive;

public class SampleTransactionLogger implements CompensableLogger {
	static final Logger logger = Logger.getLogger(SampleTransactionLogger.class.getSimpleName());

	public void createTransaction(TransactionArchive archive) {
		// TODO Auto-generated method stub

	}

	public void updateTransaction(TransactionArchive archive) {
		// TODO Auto-generated method stub

	}

	public void deleteTransaction(TransactionArchive archive) {
		// TODO Auto-generated method stub

	}

	public List<TransactionArchive> getTransactionArchiveList() {
		// TODO Auto-generated method stub
		return null;
	}

	public void updateCoordinator(XAResourceArchive archive) {
		// TODO Auto-generated method stub

	}

	public void updateCompensable(CompensableArchive archive) {
		// TODO Auto-generated method stub

	}

}
