package org.bytesoft.compensable.supports.logger;

import java.util.ArrayList;
import java.util.List;

import org.bytesoft.compensable.archive.CompensableArchive;
import org.bytesoft.compensable.archive.CompensableResourceArchive;
import org.bytesoft.transaction.archive.XAResourceArchive;

public class EmptyCompensableLogger implements CompensableLogger {

	public void createTransaction(CompensableArchive archive) {
	}

	public void updateTransaction(CompensableArchive archive) {
	}

	public void deleteTransaction(CompensableArchive archive) {
	}

	public List<CompensableArchive> getTransactionArchiveList() {
		return new ArrayList<CompensableArchive>();
	}

	public void updateCoordinator(XAResourceArchive archive) {
	}

	public void updateCompensable(CompensableResourceArchive archive) {
	}

}
