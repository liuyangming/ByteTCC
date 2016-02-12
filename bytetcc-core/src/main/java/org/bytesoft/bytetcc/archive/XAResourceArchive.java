/**
 * Copyright 2014 yangming.liu<liuyangming@gmail.com>.
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
package org.bytesoft.bytetcc.archive;

import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;

public class XAResourceArchive {
	public XAResource xaRes;
	public int vote;
	public Xid xid;
	public boolean terminator;
	public boolean completed;
	public boolean committed;
	public boolean rolledback;

	public int hashCode() {
		return this.xaRes == null ? 31 : this.xaRes.hashCode();
	}

	public boolean equals(Object obj) {
		if (obj == null) {
			return false;
		} else if (XAResourceArchive.class.isInstance(obj) == false) {
			return false;
		}
		XAResourceArchive that = (XAResourceArchive) obj;
		if (this.xaRes == that.xaRes) {
			return true;
		} else if (this.xaRes == null || that.xaRes == null) {
			return false;
		} else {
			return this.xaRes.equals(that.xaRes);
		}
	}
}
