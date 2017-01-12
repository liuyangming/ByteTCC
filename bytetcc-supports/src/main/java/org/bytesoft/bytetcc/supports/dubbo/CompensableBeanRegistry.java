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
package org.bytesoft.bytetcc.supports.dubbo;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.bytesoft.bytejta.supports.wire.RemoteCoordinator;
import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.aware.CompensableBeanFactoryAware;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CompensableBeanRegistry implements CompensableBeanFactoryAware {
	static final Logger logger = LoggerFactory.getLogger(CompensableBeanRegistry.class);
	private static final CompensableBeanRegistry instance = new CompensableBeanRegistry();

	private CompensableBeanFactory beanFactory;
	private RemoteCoordinator consumeCoordinator;

	private Lock lock = new ReentrantLock();
	private Condition condition = this.lock.newCondition();

	private CompensableBeanRegistry() {
		if (instance != null) {
			throw new IllegalStateException();
		}
	}

	public static CompensableBeanRegistry getInstance() {
		return instance;
	}

	public void setBeanFactory(CompensableBeanFactory tbf) {
		this.beanFactory = tbf;
	}

	public CompensableBeanFactory getBeanFactory() {
		return beanFactory;
	}

	public RemoteCoordinator getConsumeCoordinator() {
		if (this.consumeCoordinator != null) {
			return this.consumeCoordinator;
		} else {
			return this.doGetConsumeCoordinator();
		}
	}

	private RemoteCoordinator doGetConsumeCoordinator() {
		try {
			this.lock.lock();
			while (this.consumeCoordinator == null) {
				try {
					this.condition.await(1, TimeUnit.SECONDS);
				} catch (InterruptedException ex) {
					logger.debug(ex.getMessage());
				}
			}

			// ConsumeCoordinator is injected by the CompensableConfigPostProcessor, which has a slight delay.
			return consumeCoordinator;
		} finally {
			this.lock.unlock();
		}
	}

	public void setConsumeCoordinator(RemoteCoordinator consumeCoordinator) {
		try {
			this.lock.lock();
			this.consumeCoordinator = consumeCoordinator;
			this.condition.signalAll();
		} finally {
			this.lock.unlock();
		}
	}

}
