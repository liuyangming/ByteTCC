package org.bytesoft.compensable.supports.logger;

import java.util.ArrayList;
import java.util.List;

import org.bytesoft.compensable.archive.CompensableArchive;
import org.bytesoft.compensable.archive.TransactionArchive;
import org.bytesoft.transaction.archive.XAResourceArchive;

public class EmptyCompensableLogger implements CompensableLogger {

	public void createTransaction(TransactionArchive archive) {
	}

	public void updateTransaction(TransactionArchive archive) {
	}

	public void deleteTransaction(TransactionArchive archive) {
	}

	public List<TransactionArchive> getTransactionArchiveList() {
		return new ArrayList<TransactionArchive>();
	}

	public void updateCoordinator(XAResourceArchive archive) {
	}

	public void updateCompensable(CompensableArchive archive) {
	}

}
