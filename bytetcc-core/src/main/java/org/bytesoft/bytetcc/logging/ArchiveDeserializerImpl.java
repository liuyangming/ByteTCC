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

import org.bytesoft.compensable.archive.CompensableArchive;
import org.bytesoft.compensable.archive.TransactionArchive;
import org.bytesoft.transaction.archive.XAResourceArchive;
import org.bytesoft.transaction.logging.ArchiveDeserializer;
import org.bytesoft.transaction.xa.TransactionXid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ArchiveDeserializerImpl implements ArchiveDeserializer {
	static final Logger logger = LoggerFactory.getLogger(ArchiveDeserializerImpl.class);

	static final byte TYPE_TRANSACTION = 0x0;
	static final byte TYPE_XA_RESOURCE = 0x1;
	static final byte TYPE_COMPENSABLE = 0x2;

	private ArchiveDeserializer compensableArchiveDeserializer;
	private ArchiveDeserializer xaResourceArchiveDeserializer;
	private ArchiveDeserializer transactionArchiveDeserializer;

	public byte[] serialize(TransactionXid xid, Object archive) {

		if (TransactionArchive.class.isInstance(archive)) {
			byte[] array = this.transactionArchiveDeserializer.serialize(xid, archive);
			byte[] byteArray = new byte[array.length + 1];
			byteArray[0] = TYPE_TRANSACTION;
			System.arraycopy(array, 0, byteArray, 1, array.length);
			return byteArray;
		} else if (XAResourceArchive.class.isInstance(archive)) {
			byte[] array = this.xaResourceArchiveDeserializer.serialize(xid, archive);
			byte[] byteArray = new byte[array.length + 1];
			byteArray[0] = TYPE_XA_RESOURCE;
			System.arraycopy(array, 0, byteArray, 1, array.length);
			return byteArray;
		} else if (CompensableArchive.class.isInstance(archive)) {
			byte[] array = this.compensableArchiveDeserializer.serialize(xid, archive);
			byte[] byteArray = new byte[array.length + 1];
			byteArray[0] = TYPE_COMPENSABLE;
			System.arraycopy(array, 0, byteArray, 1, array.length);
			return byteArray;
		} else {
			throw new IllegalArgumentException();
		}

	}

	public Object deserialize(TransactionXid xid, byte[] array) {
		if (array == null || array.length <= 1) {
			throw new IllegalArgumentException();
		}

		byte type = array[0];
		if (type == TYPE_TRANSACTION) {
			byte[] byteArray = new byte[array.length - 1];
			System.arraycopy(array, 1, byteArray, 0, byteArray.length);
			return this.transactionArchiveDeserializer.deserialize(xid, byteArray);
		} else if (type == TYPE_XA_RESOURCE) {
			byte[] byteArray = new byte[array.length - 1];
			System.arraycopy(array, 1, byteArray, 0, byteArray.length);
			return this.xaResourceArchiveDeserializer.deserialize(xid, byteArray);
		} else if (type == TYPE_COMPENSABLE) {
			byte[] byteArray = new byte[array.length - 1];
			System.arraycopy(array, 1, byteArray, 0, byteArray.length);
			return this.compensableArchiveDeserializer.deserialize(xid, byteArray);
		} else {
			throw new IllegalArgumentException();
		}

	}

	public ArchiveDeserializer getCompensableArchiveDeserializer() {
		return compensableArchiveDeserializer;
	}

	public void setCompensableArchiveDeserializer(ArchiveDeserializer compensableArchiveDeserializer) {
		this.compensableArchiveDeserializer = compensableArchiveDeserializer;
	}

	public ArchiveDeserializer getXaResourceArchiveDeserializer() {
		return xaResourceArchiveDeserializer;
	}

	public void setXaResourceArchiveDeserializer(ArchiveDeserializer xaResourceArchiveDeserializer) {
		this.xaResourceArchiveDeserializer = xaResourceArchiveDeserializer;
	}

	public ArchiveDeserializer getTransactionArchiveDeserializer() {
		return transactionArchiveDeserializer;
	}

	public void setTransactionArchiveDeserializer(ArchiveDeserializer transactionArchiveDeserializer) {
		this.transactionArchiveDeserializer = transactionArchiveDeserializer;
	}

}
