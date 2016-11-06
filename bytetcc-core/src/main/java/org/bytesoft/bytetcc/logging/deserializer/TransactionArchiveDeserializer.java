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

import java.io.Serializable;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bytesoft.common.utils.ByteUtils;
import org.bytesoft.common.utils.CommonUtils;
import org.bytesoft.compensable.archive.CompensableArchive;
import org.bytesoft.compensable.archive.TransactionArchive;
import org.bytesoft.transaction.archive.XAResourceArchive;
import org.bytesoft.transaction.logging.ArchiveDeserializer;
import org.bytesoft.transaction.xa.TransactionXid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TransactionArchiveDeserializer extends
		org.bytesoft.bytejta.logging.deserializer.TransactionArchiveDeserializer implements ArchiveDeserializer {
	static final Logger logger = LoggerFactory.getLogger(TransactionArchiveDeserializer.class);

	private ArchiveDeserializer resourceArchiveDeserializer;
	private ArchiveDeserializer compensableArchiveDeserializer;

	public byte[] serialize(TransactionXid xid, Object obj) {
		TransactionArchive archive = (TransactionArchive) obj;

		List<CompensableArchive> nativeArchiveList = archive.getCompensableResourceList();
		List<XAResourceArchive> remoteArchiveList = archive.getRemoteResources();

		int nativeArchiveNumber = nativeArchiveList.size();
		int remoteArchiveNumber = remoteArchiveList.size();

		byte[] varByteArray = null;
		if (archive.getVariables() == null) {
			varByteArray = ByteUtils.shortToByteArray((short) 0);
		} else {
			try {
				byte[] textByteArray = CommonUtils.serializeObject((Serializable) archive.getVariables());
				byte[] sizeByteArray = ByteUtils.shortToByteArray((short) textByteArray.length);
				varByteArray = new byte[sizeByteArray.length + textByteArray.length];
				System.arraycopy(sizeByteArray, 0, varByteArray, 0, sizeByteArray.length);
				System.arraycopy(textByteArray, 0, varByteArray, sizeByteArray.length, textByteArray.length);
			} catch (Exception ex) {
				logger.error("Error occurred while serializing variable: {}", archive.getVariables());
				varByteArray = ByteUtils.shortToByteArray((short) 0);
			}
		}

		int length = 5 + varByteArray.length + 2;
		byte[][] nativeByteArray = new byte[nativeArchiveNumber][];
		for (int i = 0; i < nativeArchiveNumber; i++) {
			CompensableArchive compensableArchive = nativeArchiveList.get(i);

			byte[] compensableByteArray = this.compensableArchiveDeserializer.serialize(xid, compensableArchive);
			byte[] lengthByteArray = ByteUtils.shortToByteArray((short) compensableByteArray.length);

			byte[] elementByteArray = new byte[compensableByteArray.length + 2];
			System.arraycopy(lengthByteArray, 0, elementByteArray, 0, lengthByteArray.length);
			System.arraycopy(compensableByteArray, 0, elementByteArray, 2, compensableByteArray.length);

			nativeByteArray[i] = elementByteArray;
			length = length + elementByteArray.length;
		}

		byte[][] remoteByteArray = new byte[nativeArchiveNumber][];
		for (int i = 0; i < remoteArchiveNumber; i++) {
			XAResourceArchive resourceArchive = remoteArchiveList.get(i);

			byte[] resourceByteArray = this.resourceArchiveDeserializer.serialize(xid, resourceArchive);
			byte[] lengthByteArray = ByteUtils.shortToByteArray((short) resourceByteArray.length);

			byte[] elementByteArray = new byte[resourceByteArray.length + 2];
			System.arraycopy(lengthByteArray, 0, elementByteArray, 0, lengthByteArray.length);
			System.arraycopy(resourceByteArray, 0, elementByteArray, 2, resourceByteArray.length);

			remoteByteArray[i] = elementByteArray;
			length = length + elementByteArray.length;
		}

		int position = 0;

		byte[] byteArray = new byte[length];
		byteArray[position++] = (byte) archive.getStatus();
		byteArray[position++] = (byte) archive.getVote();
		byteArray[position++] = archive.isCoordinator() ? (byte) 0x1 : (byte) 0x0;
		byteArray[position++] = archive.isCompensable() ? (byte) 0x1 : (byte) 0x0;
		byteArray[position++] = (byte) archive.getCompensableStatus();

		System.arraycopy(varByteArray, 0, byteArray, position, varByteArray.length);
		position = position + varByteArray.length;

		byteArray[position++] = (byte) nativeArchiveNumber;
		byteArray[position++] = (byte) remoteArchiveNumber;

		for (int i = 0; i < nativeArchiveNumber; i++) {
			byte[] elementByteArray = nativeByteArray[i];
			System.arraycopy(elementByteArray, 0, byteArray, position, elementByteArray.length);
			position = position + elementByteArray.length;
		}

		for (int i = 0; i < remoteArchiveNumber; i++) {
			byte[] elementByteArray = remoteByteArray[i];
			System.arraycopy(elementByteArray, 0, byteArray, position, elementByteArray.length);
			position = position + elementByteArray.length;
		}

		return byteArray;
	}

	@SuppressWarnings("unchecked")
	public Object deserialize(TransactionXid xid, byte[] array) {

		ByteBuffer buffer = ByteBuffer.wrap(array);

		TransactionArchive archive = new TransactionArchive();
		archive.setXid(xid);

		int status = buffer.get();
		int vote = buffer.get();
		int coordinatorValue = buffer.get();
		int compensableValue = buffer.get();
		int compensableStatus = buffer.get();

		archive.setStatus(status);
		archive.setVote(vote);
		archive.setCoordinator(coordinatorValue != 0);
		archive.setCompensable(compensableValue != 0);
		archive.setCompensableStatus(compensableStatus);

		short sizeOfVar = buffer.getShort();
		if (sizeOfVar > 0) {
			byte[] varByteArray = new byte[sizeOfVar];
			buffer.get(varByteArray);

			Map<String, Serializable> variables = null;
			try {
				variables = (Map<String, Serializable>) CommonUtils.deserializeObject(varByteArray);
			} catch (Exception ex) {
				variables = new HashMap<String, Serializable>();
				logger.error("Error occurred while deserializing object: {}", varByteArray);
			}

			archive.setVariables(variables);
		}

		int nativeArchiveNumber = buffer.get();
		int remoteArchiveNumber = buffer.get();
		for (int i = 0; i < nativeArchiveNumber; i++) {
			int length = buffer.getShort();
			byte[] compensableByteArray = new byte[length];
			buffer.get(compensableByteArray);

			CompensableArchive compensableArchive = //
			(CompensableArchive) this.compensableArchiveDeserializer.deserialize(xid, compensableByteArray);

			archive.getCompensableResourceList().add(compensableArchive);
		}

		for (int i = 0; i < remoteArchiveNumber; i++) {
			int length = buffer.getShort();
			byte[] resourceByteArray = new byte[length];
			buffer.get(resourceByteArray);

			XAResourceArchive resourceArchive = //
			(XAResourceArchive) this.resourceArchiveDeserializer.deserialize(xid, resourceByteArray);

			archive.getRemoteResources().add(resourceArchive);
		}

		return archive;
	}

	public ArchiveDeserializer getResourceArchiveDeserializer() {
		return resourceArchiveDeserializer;
	}

	public void setResourceArchiveDeserializer(ArchiveDeserializer resourceArchiveDeserializer) {
		this.resourceArchiveDeserializer = resourceArchiveDeserializer;
	}

	public ArchiveDeserializer getCompensableArchiveDeserializer() {
		return compensableArchiveDeserializer;
	}

	public void setCompensableArchiveDeserializer(ArchiveDeserializer compensableArchiveDeserializer) {
		this.compensableArchiveDeserializer = compensableArchiveDeserializer;
	}

}
