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
package org.bytesoft.bytetcc;

import java.io.Serializable;

import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.CompensableContext;
import org.bytesoft.compensable.CompensableManager;
import org.bytesoft.compensable.CompensableTransaction;
import org.bytesoft.compensable.aware.CompensableBeanFactoryAware;

public class CompensableContextImpl implements CompensableContext, CompensableBeanFactoryAware {
	private CompensableBeanFactory beanFactory;

	public boolean isCurrentCompensableServiceTried() throws IllegalStateException {
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();
		if (compensableManager == null) {
			throw new IllegalStateException("org.bytesoft.compensable.CompensableManager is undefined!");
		}
		CompensableTransaction compensable = compensableManager.getCompensableTransactionQuietly();
		if (compensable == null) {
			throw new IllegalStateException("There is no active compensable transaction!");
		} else if (compensable.getTransactionContext().isCompensating()) {
			return compensable.isCurrentCompensableServiceTried();
		}

		return false;
	}

	public Serializable getVariable(String key) {
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();
		if (compensableManager == null) {
			throw new IllegalStateException("org.bytesoft.compensable.CompensableManager is undefined!");
		}
		CompensableTransaction compensable = compensableManager.getCompensableTransactionQuietly();
		if (compensable == null) {
			throw new IllegalStateException("There is no active compensable transaction!");
		}
		return compensable.getVariable(key);
	}

	public void setVariable(String key, Serializable variable) {
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();
		if (compensableManager == null) {
			throw new IllegalStateException("org.bytesoft.compensable.CompensableManager is undefined!");
		}
		CompensableTransaction compensable = compensableManager.getCompensableTransactionQuietly();
		if (compensable == null) {
			throw new IllegalStateException("There is no active compensable transaction!");
		} else if (compensable.getTransactionContext().isCompensating()) {
			throw new IllegalStateException("CompensableContext.setVariable(String) is forbidden in compensable phase!");
		}
		compensable.setVariable(key, variable);
	}

	public CompensableBeanFactory getBeanFactory() {
		return beanFactory;
	}

	public void setBeanFactory(CompensableBeanFactory beanFactory) {
		this.beanFactory = beanFactory;
	}

}
