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
package org.bytesoft.bytetcc.logging;

import org.bytesoft.bytejta.logging.store.VirtualLoggingSystemImpl;
import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.archive.CompensableArchive;
import org.bytesoft.compensable.archive.TransactionArchive;
import org.bytesoft.compensable.aware.CompensableBeanFactoryAware;
import org.bytesoft.compensable.logging.CompensableLogger;
import org.bytesoft.transaction.archive.XAResourceArchive;
import org.bytesoft.transaction.recovery.TransactionRecoveryCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SampleTransactionLogger extends VirtualLoggingSystemImpl
		implements CompensableLogger, CompensableBeanFactoryAware {
	static final Logger logger = LoggerFactory.getLogger(SampleTransactionLogger.class.getSimpleName());

	private CompensableBeanFactory beanFactory;

	public void createTransaction(TransactionArchive archive) {
		// TODO Auto-generated method stub

	}

	public void updateTransaction(TransactionArchive archive) {
		// TODO Auto-generated method stub

	}

	public void deleteTransaction(TransactionArchive archive) {
		// TODO Auto-generated method stub

	}

		public void updateCoordinator(XAResourceArchive archive) {
		// TODO Auto-generated method stub

	}

	public void updateCompensable(CompensableArchive archive) {
		// TODO Auto-generated method stub

	}

	public void recover(TransactionRecoveryCallback callback) {
		// TODO Auto-generated method stub

	}

	public String getLoggingIdentifier() {
		return "org.bytesoft.bytetcc.logging.sample";
	}

	public void setBeanFactory(CompensableBeanFactory tbf) {
		this.beanFactory = tbf;
	}

}
