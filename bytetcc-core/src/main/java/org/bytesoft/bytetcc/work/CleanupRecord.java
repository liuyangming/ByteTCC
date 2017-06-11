/**
 * Copyright 2014-2017 yangming.liu<bytefox@126.com>.
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
package org.bytesoft.bytetcc.work;

import javax.transaction.xa.Xid;

import org.apache.commons.lang3.StringUtils;
import org.bytesoft.common.utils.CommonUtils;

public class CleanupRecord {

	private boolean enabled;
	private int recordFlag;
	private int startIndex;
	private Xid xid;
	private String resource;

	public boolean equals(Object obj) {
		if (obj == null) {
			return false;
		} else if (CleanupRecord.class.isInstance(obj) == false) {
			return false;
		}

		CleanupRecord that = (CleanupRecord) obj;
		boolean xidEquals = CommonUtils.equals(this.xid, that.xid);
		boolean resEquals = StringUtils.equals(this.resource, that.resource);
		return xidEquals && resEquals;
	}

	public int hashCode() {
		int hash = 13;
		hash += 17 * (this.xid == null ? 0 : this.xid.hashCode());
		hash += 19 * (this.resource == null ? 0 : this.resource.hashCode());
		return hash;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public int getRecordFlag() {
		return recordFlag;
	}

	public void setRecordFlag(int recordFlag) {
		this.recordFlag = recordFlag;
	}

	public int getStartIndex() {
		return startIndex;
	}

	public void setStartIndex(int startIndex) {
		this.startIndex = startIndex;
	}

	public Xid getXid() {
		return xid;
	}

	public void setXid(Xid xid) {
		this.xid = xid;
	}

	public String getResource() {
		return resource;
	}

	public void setResource(String resource) {
		this.resource = resource;
	}

}
