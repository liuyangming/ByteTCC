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

import java.nio.ByteBuffer;

import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;

import org.bytesoft.bytejta.supports.resource.CommonResourceDescriptor;
import org.bytesoft.bytejta.supports.resource.RemoteResourceDescriptor;
import org.bytesoft.bytejta.supports.resource.UnidentifiedResourceDescriptor;
import org.bytesoft.bytejta.supports.wire.RemoteCoordinator;
import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.aware.CompensableBeanFactoryAware;
import org.bytesoft.transaction.archive.XAResourceArchive;
import org.bytesoft.transaction.logging.ArchiveDeserializer;
import org.bytesoft.transaction.supports.resource.XAResourceDescriptor;
import org.bytesoft.transaction.supports.serialize.XAResourceDeserializer;
import org.bytesoft.transaction.xa.TransactionXid;
import org.bytesoft.transaction.xa.XidFactory;

public class XAResourceArchiveDeserializer implements ArchiveDeserializer, CompensableBeanFactoryAware {

	private CompensableBeanFactory beanFactory;
	private XAResourceDeserializer deserializer;

	public byte[] serialize(TransactionXid xid, Object obj) {
		XAResourceArchive archive = (XAResourceArchive) obj;

		ByteBuffer buffer = ByteBuffer.allocate(8192);

		Xid branchXid = archive.getXid();
		byte[] branchQualifier = branchXid.getBranchQualifier();
		buffer.put(branchQualifier);

		XAResourceDescriptor descriptor = archive.getDescriptor();
		if (CommonResourceDescriptor.class.isInstance(descriptor)) {
			String identifier = descriptor.getIdentifier();
			byte[] identifierByteArray = identifier.getBytes();
			buffer.put((byte) 0x1);
			buffer.put((byte) identifierByteArray.length);
			buffer.put(identifierByteArray);
		} else if (RemoteResourceDescriptor.class.isInstance(descriptor)) {
			String identifier = descriptor.getIdentifier();
			byte[] identifierByteArray = identifier.getBytes();
			buffer.put((byte) 0x2);
			buffer.put((byte) identifierByteArray.length);
			buffer.put(identifierByteArray);
		} else {
			buffer.put((byte) 0x0);
			buffer.put((byte) 0x0);
		}

		byte branchVote = (byte) archive.getVote();
		byte readonly = archive.isReadonly() ? (byte) 1 : (byte) 0;
		byte committed = archive.isCommitted() ? (byte) 1 : (byte) 0;
		byte rolledback = archive.isRolledback() ? (byte) 1 : (byte) 0;
		byte completed = archive.isCompleted() ? (byte) 1 : (byte) 0;
		byte heuristic = archive.isHeuristic() ? (byte) 1 : (byte) 0;

		buffer.put(branchVote);
		buffer.put(readonly);
		buffer.put(committed);
		buffer.put(rolledback);
		buffer.put(completed);
		buffer.put(heuristic);

		int position = buffer.position();
		byte[] byteArray = new byte[position];
		buffer.flip();
		buffer.get(byteArray);

		return byteArray;
	}

	public Object deserialize(TransactionXid xid, byte[] array) {
		ByteBuffer buffer = ByteBuffer.wrap(array);

		XAResourceArchive archive = new XAResourceArchive();

		byte[] branchQualifier = new byte[XidFactory.BRANCH_QUALIFIER_LENGTH];
		buffer.get(branchQualifier);
		XidFactory xidFactory = this.beanFactory.getCompensableXidFactory();
		TransactionXid branchXid = xidFactory.createBranchXid(xid, branchQualifier);
		archive.setXid(branchXid);

		XAResourceDescriptor descriptor = null;
		byte resourceType = buffer.get();
		byte length = buffer.get();
		byte[] byteArray = new byte[length];
		buffer.get(byteArray);
		String identifier = new String(byteArray);

		if (resourceType == 0x01) {
			archive.setIdentified(true);
			CommonResourceDescriptor resourceDescriptor = new CommonResourceDescriptor();
			XAResource resource = this.deserializer.deserialize(identifier);
			resourceDescriptor.setDelegate(resource);
			descriptor = resourceDescriptor;
		} else if (resourceType == 0x02) {
			archive.setIdentified(true);
			RemoteResourceDescriptor resourceDescriptor = new RemoteResourceDescriptor();
			XAResource resource = this.deserializer.deserialize(identifier);
			resourceDescriptor.setDelegate((RemoteCoordinator) resource);
			descriptor = resourceDescriptor;
			descriptor = resourceDescriptor;
		} else {
			UnidentifiedResourceDescriptor resourceDescriptor = new UnidentifiedResourceDescriptor();
			descriptor = resourceDescriptor;
		}
		archive.setDescriptor(descriptor);

		int branchVote = buffer.get();
		int readonly = buffer.get();
		int committedValue = buffer.get();
		int rolledbackValue = buffer.get();
		int completedValue = buffer.get();
		int heuristicValue = buffer.get();
		archive.setVote(branchVote);
		archive.setReadonly(readonly != 0);
		archive.setCommitted(committedValue != 0);
		archive.setRolledback(rolledbackValue != 0);
		archive.setCompleted(completedValue != 0);
		archive.setHeuristic(heuristicValue != 0);

		return archive;
	}

	public void setBeanFactory(CompensableBeanFactory tbf) {
		this.beanFactory = tbf;
	}

}
