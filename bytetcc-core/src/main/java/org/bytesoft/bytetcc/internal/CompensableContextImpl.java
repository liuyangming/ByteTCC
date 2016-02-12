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
package org.bytesoft.bytetcc.internal;

import java.io.Serializable;

import org.bytesoft.bytetcc.CompensableContext;

public class CompensableContextImpl implements CompensableContext {

	public Serializable getCompensableVariable() throws IllegalStateException {
		// CompensableContext context = CompensableInvocationRegistryImpl.getInstance().getCompensableInvocation();
		// if (context == null) {
		return null;
		// } else {
		// return context.getCompensableVariable();
		// }
	}

	public void setCompensableVariable(Serializable variable) throws IllegalStateException {
		// CompensableContext context = CompensableInvocationRegistryImpl.getInstance().getCompensableInvocation();
		// if (context == null) {
		// // ignore
		// } else {
		// context.setCompensableVariable(variable);
		// }
	}

	public boolean isRollbackOnly() throws IllegalStateException {
		// CompensableContext context = CompensableInvocationRegistryImpl.getInstance().getCompensableInvocation();
		// if (context == null) {
		return false;
		// } else {
		// return context.isRollbackOnly();
		// }
	}

	public void setRollbackOnly() throws IllegalStateException {
		// CompensableContext context = CompensableInvocationRegistryImpl.getInstance().getCompensableInvocation();
		// if (context == null) {
		// // ignore
		// } else {
		// context.setRollbackOnly();
		// }
	}

}
