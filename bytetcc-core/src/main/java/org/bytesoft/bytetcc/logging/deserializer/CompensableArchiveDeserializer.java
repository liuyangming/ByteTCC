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

import java.util.Arrays;

import javax.transaction.xa.Xid;

import org.bytesoft.common.utils.ByteUtils;
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
	static final Logger logger = LoggerFactory.getLogger(CompensableArchiveDeserializer.class);
	static final int LENGTH_OF_XID = XidFactory.GLOBAL_TRANSACTION_LENGTH + XidFactory.BRANCH_QUALIFIER_LENGTH;

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

		String transactionResourceKey = archive.getTransactionResourceKey();
		String compensableResourceKey = archive.getCompensableResourceKey();

		byte[] transactionResourceKeyByteArray = transactionResourceKey == null ? new byte[0]
				: transactionResourceKey.getBytes();
		byte[] compensableResourceKeyByteArray = compensableResourceKey == null ? new byte[0]
				: compensableResourceKey.getBytes();

		byte[] resultArray = new byte[XidFactory.GLOBAL_TRANSACTION_LENGTH + XidFactory.BRANCH_QUALIFIER_LENGTH
				+ LENGTH_OF_XID * 2 + 1 //
				+ 2 + transactionResourceKeyByteArray.length //
				+ 2 + compensableResourceKeyByteArray.length //
				+ byteArray.length];

		Xid identifier = archive.getIdentifier();
		byte[] globalByteArray = identifier.getGlobalTransactionId();
		byte[] branchByteArray = identifier.getBranchQualifier();
		System.arraycopy(globalByteArray, 0, resultArray, 0, XidFactory.GLOBAL_TRANSACTION_LENGTH);
		System.arraycopy(branchByteArray, 0, resultArray, XidFactory.GLOBAL_TRANSACTION_LENGTH,
				XidFactory.BRANCH_QUALIFIER_LENGTH);

		Xid transactionXid = archive.getTransactionXid();
		Xid compensableXid = archive.getCompensableXid();
		byte[] transactionGlobalTransactionId = null;
		byte[] transactionBranchQualifier = null;
		byte[] compensableGlobalTransactionId = null;
		byte[] compensableBranchQualifier = null;
		if (transactionXid == null) {
			transactionGlobalTransactionId = new byte[XidFactory.GLOBAL_TRANSACTION_LENGTH];
			transactionBranchQualifier = new byte[XidFactory.BRANCH_QUALIFIER_LENGTH];
		} else {
			transactionGlobalTransactionId = transactionXid.getGlobalTransactionId();
			transactionBranchQualifier = transactionXid.getBranchQualifier();
		}
		System.arraycopy(transactionGlobalTransactionId, 0, resultArray, LENGTH_OF_XID, XidFactory.GLOBAL_TRANSACTION_LENGTH);
		System.arraycopy(transactionBranchQualifier, 0, resultArray, LENGTH_OF_XID + XidFactory.GLOBAL_TRANSACTION_LENGTH,
				XidFactory.BRANCH_QUALIFIER_LENGTH);

		if (compensableXid == null) {
			compensableGlobalTransactionId = new byte[XidFactory.GLOBAL_TRANSACTION_LENGTH];
			compensableBranchQualifier = new byte[XidFactory.BRANCH_QUALIFIER_LENGTH];
		} else {
			compensableGlobalTransactionId = compensableXid.getGlobalTransactionId();
			compensableBranchQualifier = compensableXid.getBranchQualifier();
		}
		System.arraycopy(compensableGlobalTransactionId, 0, resultArray, LENGTH_OF_XID * 2,
				XidFactory.GLOBAL_TRANSACTION_LENGTH);
		System.arraycopy(compensableBranchQualifier, 0, resultArray, LENGTH_OF_XID * 2 + XidFactory.GLOBAL_TRANSACTION_LENGTH,
				XidFactory.BRANCH_QUALIFIER_LENGTH);

		int value = archive.isCoordinator() ? 0x1 : 0x0;
		int triedValue = archive.isTried() ? 0x1 : 0x0;
		int confirmValue = archive.isConfirmed() ? 0x1 : 0x0;
		int cancelValue = archive.isCancelled() ? 0x1 : 0x0;
		// int mixedValue = archive.isTxMixed() ? 0x1 : 0x0;

		value = value | (triedValue << 1);
		value = value | (confirmValue << 2);
		value = value | (cancelValue << 3);
		// value = value | (mixedValue << 4);
		resultArray[LENGTH_OF_XID * 3] = (byte) value;

		byte[] lengthOfTransactionResourceKey = ByteUtils.shortToByteArray((short) transactionResourceKeyByteArray.length);
		byte[] lengthOfCompensableResourceKey = ByteUtils.shortToByteArray((short) compensableResourceKeyByteArray.length);

		int index = LENGTH_OF_XID * 3 + 1;
		System.arraycopy(lengthOfTransactionResourceKey, 0, resultArray, index, lengthOfTransactionResourceKey.length);
		index += lengthOfTransactionResourceKey.length;

		System.arraycopy(transactionResourceKeyByteArray, 0, resultArray, index, transactionResourceKeyByteArray.length);
		index += transactionResourceKeyByteArray.length;

		System.arraycopy(lengthOfCompensableResourceKey, 0, resultArray, index, lengthOfCompensableResourceKey.length);
		index += lengthOfCompensableResourceKey.length;

		System.arraycopy(compensableResourceKeyByteArray, 0, resultArray, index, compensableResourceKeyByteArray.length);
		index += compensableResourceKeyByteArray.length;

		System.arraycopy(byteArray, 0, resultArray, index, byteArray.length);

		return resultArray;
	}

	public Object deserialize(TransactionXid xid, byte[] array) {
		byte[] globalByteArray = new byte[XidFactory.GLOBAL_TRANSACTION_LENGTH];
		byte[] branchByteArray = new byte[XidFactory.BRANCH_QUALIFIER_LENGTH];
		System.arraycopy(array, 0, globalByteArray, 0, globalByteArray.length);
		System.arraycopy(array, XidFactory.GLOBAL_TRANSACTION_LENGTH, branchByteArray, 0, branchByteArray.length);

		byte[] transactionGlobalTransactionId = new byte[XidFactory.GLOBAL_TRANSACTION_LENGTH];
		byte[] transactionBranchQualifier = new byte[XidFactory.BRANCH_QUALIFIER_LENGTH];
		byte[] compensableGlobalTransactionId = new byte[XidFactory.GLOBAL_TRANSACTION_LENGTH];
		byte[] compensableBranchQualifier = new byte[XidFactory.BRANCH_QUALIFIER_LENGTH];
		System.arraycopy(array, LENGTH_OF_XID, transactionGlobalTransactionId, 0, transactionGlobalTransactionId.length);
		System.arraycopy(array, LENGTH_OF_XID + XidFactory.GLOBAL_TRANSACTION_LENGTH, transactionBranchQualifier, 0,
				transactionBranchQualifier.length);
		System.arraycopy(array, LENGTH_OF_XID * 2, compensableGlobalTransactionId, 0, compensableGlobalTransactionId.length);
		System.arraycopy(array, LENGTH_OF_XID * 2 + XidFactory.GLOBAL_TRANSACTION_LENGTH, compensableBranchQualifier, 0,
				compensableBranchQualifier.length);

		int value = array[LENGTH_OF_XID * 3];

		boolean coordinator = (value & 0x1) == 0x1;
		boolean tried = ((value >>> 1) & 0x1) == 0x1;
		boolean confirmed = ((value >>> 2) & 0x1) == 0x1;
		boolean cancelled = ((value >>> 3) & 0x1) == 0x1;
		// boolean mixed = ((value >>> 4) & 0x1) == 0x1;

		int index = LENGTH_OF_XID * 3 + 1;

		byte[] lengthOfTransactionResourceKey = new byte[2];
		System.arraycopy(array, index, lengthOfTransactionResourceKey, 0, lengthOfTransactionResourceKey.length);
		index += lengthOfTransactionResourceKey.length;
		short transactionResourceKeySize = ByteUtils.byteArrayToShort(lengthOfTransactionResourceKey);
		byte[] transactionResourceKeyByteArray = new byte[transactionResourceKeySize];
		System.arraycopy(array, index, transactionResourceKeyByteArray, 0, transactionResourceKeyByteArray.length);
		index += transactionResourceKeyByteArray.length;

		byte[] lengthOfCompensableResourceKey = new byte[2];
		System.arraycopy(array, index, lengthOfCompensableResourceKey, 0, lengthOfCompensableResourceKey.length);
		index += lengthOfCompensableResourceKey.length;
		short compensableResourceKeySize = ByteUtils.byteArrayToShort(lengthOfCompensableResourceKey);
		byte[] compensableResourceKeyByteArray = new byte[compensableResourceKeySize];
		System.arraycopy(array, index, compensableResourceKeyByteArray, 0, compensableResourceKeyByteArray.length);
		index += compensableResourceKeyByteArray.length;

		String transactionResourceKey = transactionResourceKeyByteArray.length == 0 ? null
				: new String(transactionResourceKeyByteArray);
		String compensableResourceKey = compensableResourceKeyByteArray.length == 0 ? null
				: new String(compensableResourceKeyByteArray);

		int usedSize = LENGTH_OF_XID * 3 + 1 + 2 + transactionResourceKeySize + 2 + compensableResourceKeySize;

		byte[] byteArray = new byte[array.length - usedSize];
		System.arraycopy(array, index, byteArray, 0, byteArray.length);

		CompensableInvocation compensable = null;
		try {
			compensable = (CompensableInvocation) CommonUtils.deserializeObject(byteArray);
		} catch (Exception ex) {
			logger.error("Error occurred while deserializing object: {}", byteArray);
		}

		XidFactory xidFactory = this.beanFactory.getTransactionXidFactory();
		Xid transactionXid = null;
		Xid compensableXid = null;

		if (Arrays.equals(transactionGlobalTransactionId, new byte[XidFactory.GLOBAL_TRANSACTION_LENGTH]) == false) {
			TransactionXid transactionGlobalXid = xidFactory.createGlobalXid(transactionGlobalTransactionId);
			transactionXid = xidFactory.createBranchXid(transactionGlobalXid, transactionBranchQualifier);
		}
		if (Arrays.equals(compensableGlobalTransactionId, new byte[XidFactory.GLOBAL_TRANSACTION_LENGTH]) == false) {
			TransactionXid compensableGlobalXid = xidFactory.createGlobalXid(compensableGlobalTransactionId);
			compensableXid = xidFactory.createBranchXid(compensableGlobalXid, compensableBranchQualifier);
		}

		TransactionXid globalXid = xidFactory.createGlobalXid(globalByteArray);
		TransactionXid identifier = xidFactory.createBranchXid(globalXid, branchByteArray);

		CompensableArchive archive = new CompensableArchive();
		archive.setIdentifier(identifier);
		archive.setCoordinator(coordinator);
		archive.setTried(tried);
		archive.setConfirmed(confirmed);
		archive.setCancelled(cancelled);
		// archive.setTxMixed(mixed);
		archive.setCompensable(compensable);
		archive.setTransactionXid(transactionXid);
		archive.setCompensableXid(compensableXid);
		archive.setTransactionResourceKey(transactionResourceKey);
		archive.setCompensableResourceKey(compensableResourceKey);

		return archive;
	}

	public void setBeanFactory(CompensableBeanFactory tbf) {
		this.beanFactory = tbf;
	}

}
