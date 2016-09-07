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
		byte[] byteArray = null;
		try {
			byteArray = CommonUtils.serializeObject(compensable);
		} catch (Exception ex) {
			byteArray = new byte[0];
			logger.error("Error occurred while serializing object: {}", compensable);
		}

		byte[] resultArray = new byte[XidFactory.BRANCH_QUALIFIER_LENGTH + 1 + byteArray.length];

		Xid branch = archive.getXid();
		byte[] branchQualifier = branch.getBranchQualifier();
		System.arraycopy(branchQualifier, 0, resultArray, 0, XidFactory.BRANCH_QUALIFIER_LENGTH);

		int value = archive.isCoordinator() ? 0x1 : 0x0;
		int triedValue = archive.isParticipantTried() ? 0x1 : 0x0;
		int confirmValue = archive.isConfirmed() ? 0x1 : 0x0;
		int cancelValue = archive.isCancelled() ? 0x1 : 0x0;
		int mixedValue = archive.isTxMixed() ? 0x1 : 0x0;

		value = value | (triedValue << 1);
		value = value | (confirmValue << 2);
		value = value | (cancelValue << 3);
		value = value | (mixedValue << 4);
		resultArray[XidFactory.BRANCH_QUALIFIER_LENGTH] = (byte) value;

		System.arraycopy(byteArray, 0, resultArray, XidFactory.BRANCH_QUALIFIER_LENGTH + 1, byteArray.length);

		return resultArray;
	}

	public Object deserialize(TransactionXid xid, byte[] array) {
		byte[] branchQualifier = new byte[XidFactory.BRANCH_QUALIFIER_LENGTH];
		System.arraycopy(array, 0, branchQualifier, 0, branchQualifier.length);

		int value = array[XidFactory.BRANCH_QUALIFIER_LENGTH];

		boolean coordinator = (value & 0x1) == 0x1;
		boolean tried = ((value >>> 1) & 0x1) == 0x1;
		boolean confirmed = ((value >>> 2) & 0x1) == 0x1;
		boolean cancelled = ((value >>> 3) & 0x1) == 0x1;
		boolean mixed = ((value >>> 4) & 0x1) == 0x1;

		byte[] byteArray = new byte[array.length - XidFactory.BRANCH_QUALIFIER_LENGTH - 1];
		System.arraycopy(array, XidFactory.BRANCH_QUALIFIER_LENGTH + 1, byteArray, 0, byteArray.length);

		CompensableInvocation compensable = null;
		try {
			compensable = (CompensableInvocation) CommonUtils.deserializeObject(byteArray);
		} catch (Exception ex) {
			compensable = null;
			logger.error("Error occurred while deserializing object: {}", byteArray);
		}

		XidFactory xidFactory = this.beanFactory.getCompensableXidFactory();
		Xid branch = xidFactory.createBranchXid(xid, branchQualifier);

		CompensableArchive archive = new CompensableArchive();
		archive.setXid(branch);
		archive.setCoordinator(coordinator);
		archive.setParticipantTried(tried);
		archive.setConfirmed(confirmed);
		archive.setCancelled(cancelled);
		archive.setTxMixed(mixed);
		archive.setCompensable(compensable);

		return archive;
	}

	public void setBeanFactory(CompensableBeanFactory tbf) {
		this.beanFactory = tbf;
	}

}
