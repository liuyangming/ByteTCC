package org.bytesoft.bytetcc.supports.logger;

import org.bytesoft.bytetcc.archive.CompensableArchive;

public class EmptyCompensableTransactionLogger extends org.bytesoft.transaction.supports.logger.EmptyTransactionLogger
		implements CompensableTransactionLogger {

	public void updateCompensable(CompensableArchive archive) {
	}

}
