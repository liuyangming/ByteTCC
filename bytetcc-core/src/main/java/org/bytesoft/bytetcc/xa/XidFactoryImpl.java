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
package org.bytesoft.bytetcc.xa;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import org.bytesoft.bytejta.utils.ByteUtils;
import org.bytesoft.bytetcc.common.TransactionCommonXid;
import org.bytesoft.transaction.xa.TransactionXid;
import org.bytesoft.transaction.xa.XidFactory;

public class XidFactoryImpl implements XidFactory {

	private final Random random = new Random();
	private final AtomicLong atomic = new AtomicLong();
	private final byte[] hardwareAddress = new byte[6];
	private String application = "default";
	private int identifier = 0;

	public XidFactoryImpl() throws IllegalStateException {
		try {
			InetAddress inetAddress = InetAddress.getLocalHost();
			NetworkInterface networkInterface = NetworkInterface.getByInetAddress(inetAddress);
			byte[] hardwareByteArray = networkInterface.getHardwareAddress();
			int fromLen = hardwareByteArray.length;
			int distLen = this.hardwareAddress.length;
			if (fromLen >= distLen) {
				System.arraycopy(hardwareByteArray, 0, this.hardwareAddress, 0, distLen);
			} else {
				System.arraycopy(hardwareByteArray, 0, this.hardwareAddress, 0, fromLen);
			}
		} catch (UnknownHostException ex) {
			throw new IllegalStateException(ex);
		} catch (SocketException ex) {
			throw new IllegalStateException(ex);
		}
	}

	public TransactionXid createGlobalXid() {
		byte[] global = new byte[GLOBAL_TRANSACTION_LENGTH];
		byte[] applicationBytes = new byte[10];
		byte[] fromBytes = this.application.getBytes();
		int copyLength = applicationBytes.length > fromBytes.length ? fromBytes.length : applicationBytes.length;
		System.arraycopy(fromBytes, 0, applicationBytes, 0, copyLength);
		byte[] endByteArray = ByteUtils.intToByteArray(this.identifier);

		long millis = System.currentTimeMillis();
		byte[] millisByteArray = ByteUtils.longToByteArray(millis);

		int index = (int) (this.atomic.incrementAndGet() % Integer.MAX_VALUE);
		byte[] atomicByteArray = ByteUtils.intToByteArray(index);

		System.arraycopy(this.hardwareAddress, 0, global, 0, this.hardwareAddress.length);
		System.arraycopy(applicationBytes, 0, global, this.hardwareAddress.length, applicationBytes.length);
		System.arraycopy(endByteArray, 0, global, //
				this.hardwareAddress.length + applicationBytes.length, endByteArray.length);
		System.arraycopy(millisByteArray, 0, global, //
				this.hardwareAddress.length + applicationBytes.length + endByteArray.length, millisByteArray.length);
		System.arraycopy(atomicByteArray, 0, global, //
				this.hardwareAddress.length + applicationBytes.length + endByteArray.length + millisByteArray.length,//
				atomicByteArray.length);

		return new TransactionCommonXid(global);
	}

	public TransactionXid createGlobalXid(byte[] globalTransactionId) {
		if (globalTransactionId == null) {
			throw new IllegalArgumentException("The globalTransactionId cannot be null.");
		} else if (globalTransactionId.length > TransactionXid.MAXGTRIDSIZE) {
			throw new IllegalArgumentException("The length of globalTransactionId cannot exceed 64 bytes.");
		}
		byte[] global = new byte[globalTransactionId.length];
		System.arraycopy(globalTransactionId, 0, global, 0, global.length);
		return new TransactionCommonXid(global);
	}

	public TransactionXid createBranchXid(TransactionXid globalXid) {
		if (globalXid == null) {
			throw new IllegalArgumentException("Xid cannot be null.");
		} else if (globalXid.getGlobalTransactionId() == null) {
			throw new IllegalArgumentException("The globalTransactionId cannot be null.");
		} else if (globalXid.getGlobalTransactionId().length > TransactionXid.MAXGTRIDSIZE) {
			throw new IllegalArgumentException("The length of globalTransactionId cannot exceed 64 bytes.");
		}

		byte[] global = new byte[globalXid.getGlobalTransactionId().length];
		System.arraycopy(globalXid.getGlobalTransactionId(), 0, global, 0, global.length);

		byte[] branch = new byte[BRANCH_QUALIFIER_LENGTH];
		byte[] applicationBytes = new byte[10];
		byte[] fromBytes = this.application.getBytes();
		int copyLength = applicationBytes.length > fromBytes.length ? fromBytes.length : applicationBytes.length;
		System.arraycopy(fromBytes, 0, applicationBytes, 0, copyLength);
		byte[] endByteArray = ByteUtils.intToByteArray(this.identifier);

		long millis = System.currentTimeMillis();
		byte[] millisByteArray = ByteUtils.longToByteArray(millis);

		int index = (int) (this.atomic.incrementAndGet() % Integer.MAX_VALUE);
		byte[] atomicByteArray = ByteUtils.intToByteArray(index);

		System.arraycopy(this.hardwareAddress, 0, branch, 0, this.hardwareAddress.length);
		System.arraycopy(applicationBytes, 0, branch, this.hardwareAddress.length, applicationBytes.length);
		System.arraycopy(endByteArray, 0, branch, //
				this.hardwareAddress.length + applicationBytes.length, endByteArray.length);
		System.arraycopy(millisByteArray, 0, branch, //
				this.hardwareAddress.length + applicationBytes.length + endByteArray.length, millisByteArray.length);
		System.arraycopy(atomicByteArray, 0, branch, //
				this.hardwareAddress.length + applicationBytes.length + endByteArray.length + millisByteArray.length,//
				atomicByteArray.length);

		return new TransactionCommonXid(global, branch);
	}

	public TransactionXid createBranchXid(TransactionXid globalXid, byte[] branchQualifier) {
		if (globalXid == null) {
			throw new IllegalArgumentException("Xid cannot be null.");
		} else if (globalXid.getGlobalTransactionId() == null) {
			throw new IllegalArgumentException("The globalTransactionId cannot be null.");
		} else if (globalXid.getGlobalTransactionId().length > TransactionXid.MAXGTRIDSIZE) {
			throw new IllegalArgumentException("The length of globalTransactionId cannot exceed 64 bytes.");
		}

		if (branchQualifier == null) {
			throw new IllegalArgumentException("The branchQulifier cannot be null.");
		} else if (branchQualifier.length > TransactionXid.MAXBQUALSIZE) {
			throw new IllegalArgumentException("The length of branchQulifier cannot exceed 64 bytes.");
		}

		byte[] global = new byte[globalXid.getGlobalTransactionId().length];
		System.arraycopy(globalXid.getGlobalTransactionId(), 0, global, 0, global.length);

		return new TransactionCommonXid(global, branchQualifier);
	}

	public byte[] generateUniqueKey() {
		byte[] currentByteArray = ByteUtils.longToByteArray(System.currentTimeMillis());

		byte[] randomByteArray = new byte[4];
		random.nextBytes(randomByteArray);

		byte[] uniqueKey = new byte[16];
		int timeLen = 6;
		System.arraycopy(currentByteArray, currentByteArray.length - timeLen, uniqueKey, 0, timeLen);
		System.arraycopy(randomByteArray, 0, uniqueKey, timeLen, randomByteArray.length);
		System.arraycopy(hardwareAddress, 0, uniqueKey, randomByteArray.length + timeLen, hardwareAddress.length);

		return uniqueKey;
	}

}
