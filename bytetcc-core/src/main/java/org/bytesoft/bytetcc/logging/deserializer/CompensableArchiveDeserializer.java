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
package org.bytesoft.bytetcc.logging.deserializer;

import javax.transaction.xa.Xid;

import org.bytesoft.common.utils.CommonUtils;
import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.CompensableInvocation;
import org.bytesoft.compensable.archive.CompensableArchive;
import org.bytesoft.compensable.aware.CompensableBeanFactoryAware;
import org.bytesoft.transaction.logging.ArchiveDeserializer;
import org.bytesoft.transaction.xa.TransactionXid;
import org.bytesoft.transaction.xa.XidFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CompensableArchiveDeserializer implements ArchiveDeserializer, CompensableBeanFactoryAware {
	static final Logger logger = LoggerFactory.getLogger(CommonUtils.class);

	private CompensableBeanFactory beanFactory;

	public byte[] serialize(TransactionXid xid, Object obj) {
		CompensableArchive archive = (CompensableArchive) obj;

		CompensableInvocation compensable = archive.getCompensable();
		byte[] byteArray = new byte[0];
		try {
			byteArray = CommonUtils.serializeObject(compensable);
		} catch (Exception ex) {
			if (compensable == null) {
				logger.error("Error occurred while serializing compensable: {}", compensable);
			} else {
				logger.error("Error occurred while serializing args: {}", compensable.getArgs());
			}
		}

		byte[] resultArray = new byte[XidFactory.BRANCH_QUALIFIER_LENGTH * 2 + 1 + byteArray.length];

		Xid transactionXid = archive.getTransactionXid();
		Xid compensableXid = archive.getCompensableXid();
		byte[] transactionBranchQualifier = transactionXid.getBranchQualifier();
		byte[] compensableBranchQualifier = null;
		if (compensableXid == null) {
			compensableBranchQualifier = new byte[XidFactory.BRANCH_QUALIFIER_LENGTH];
		} else {
			compensableBranchQualifier = compensableXid.getBranchQualifier();
		}
		System.arraycopy(transactionBranchQualifier, 0, resultArray, 0, XidFactory.BRANCH_QUALIFIER_LENGTH);
		System.arraycopy(compensableBranchQualifier, 0, resultArray, XidFactory.BRANCH_QUALIFIER_LENGTH,
				XidFactory.BRANCH_QUALIFIER_LENGTH);

		int value = archive.isCoordinator() ? 0x1 : 0x0;
		int triedValue = archive.isParticipantTried() ? 0x1 : 0x0;
		int confirmValue = archive.isConfirmed() ? 0x1 : 0x0;
		int cancelValue = archive.isCancelled() ? 0x1 : 0x0;
		int mixedValue = archive.isTxMixed() ? 0x1 : 0x0;

		value = value | (triedValue << 1);
		value = value | (confirmValue << 2);
		value = value | (cancelValue << 3);
		value = value | (mixedValue << 4);
		resultArray[XidFactory.BRANCH_QUALIFIER_LENGTH * 2] = (byte) value;

		System.arraycopy(byteArray, 0, resultArray, XidFactory.BRANCH_QUALIFIER_LENGTH * 2 + 1, byteArray.length);

		return resultArray;
	}

	public Object deserialize(TransactionXid xid, byte[] array) {
		byte[] transactionBranchQualifier = new byte[XidFactory.BRANCH_QUALIFIER_LENGTH];
		byte[] compensableBranchQualifier = new byte[XidFactory.BRANCH_QUALIFIER_LENGTH];
		System.arraycopy(array, 0, transactionBranchQualifier, 0, transactionBranchQualifier.length);
		System.arraycopy(array, 0, compensableBranchQualifier, XidFactory.BRANCH_QUALIFIER_LENGTH,
				compensableBranchQualifier.length);

		int value = array[XidFactory.BRANCH_QUALIFIER_LENGTH * 2];

		boolean coordinator = (value & 0x1) == 0x1;
		boolean tried = ((value >>> 1) & 0x1) == 0x1;
		boolean confirmed = ((value >>> 2) & 0x1) == 0x1;
		boolean cancelled = ((value >>> 3) & 0x1) == 0x1;
		boolean mixed = ((value >>> 4) & 0x1) == 0x1;

		byte[] byteArray = new byte[array.length - XidFactory.BRANCH_QUALIFIER_LENGTH * 2 - 1];
		System.arraycopy(array, XidFactory.BRANCH_QUALIFIER_LENGTH * 2 + 1, byteArray, 0, byteArray.length);

		CompensableInvocation compensable = null;
		try {
			compensable = (CompensableInvocation) CommonUtils.deserializeObject(byteArray);
		} catch (Exception ex) {
			logger.error("Error occurred while deserializing object: {}", byteArray);
		}

		XidFactory xidFactory = this.beanFactory.getCompensableXidFactory();
		Xid transactionXid = xidFactory.createBranchXid(xid, transactionBranchQualifier);
		Xid compensableXid = xidFactory.createBranchXid(xid, compensableBranchQualifier);

		CompensableArchive archive = new CompensableArchive();
		archive.setCoordinator(coordinator);
		archive.setParticipantTried(tried);
		archive.setConfirmed(confirmed);
		archive.setCancelled(cancelled);
		archive.setTxMixed(mixed);
		archive.setCompensable(compensable);
		archive.setTransactionXid(transactionXid);
		archive.setCompensableXid(compensableXid);

		return archive;
	}

	public void setBeanFactory(CompensableBeanFactory tbf) {
		this.beanFactory = tbf;
	}

}
