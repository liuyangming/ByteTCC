/**
 * Copyright 2014-2018 yangming.liu<bytefox@126.com>.
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
package org.bytesoft.bytetcc.supports.internal;

import org.bytesoft.bytetcc.TransactionRecoveryImpl;
import org.bytesoft.bytetcc.work.CommandManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MongoCompensableRecovery extends TransactionRecoveryImpl {
	static final Logger logger = LoggerFactory.getLogger(MongoCompensableRecovery.class);

	@javax.inject.Inject
	private CommandManager commandManager;

	public void startRecovery() throws SecurityException {
		try {
			this.commandManager.execute(new Runnable() {
				public void run() {
					fireSuperStartRecovery();
				}
			});
		} catch (SecurityException error) {
			throw error; // Only the master node can perform the recovery operation!
		} catch (RuntimeException error) {
			throw error;
		} catch (Exception error) {
			throw new RuntimeException(error);
		}
	}

	public void timingRecover() throws SecurityException {
		try {
			this.commandManager.execute(new Runnable() {
				public void run() {
					fireSuperTimingRecovery();
				}
			});
		} catch (SecurityException error) {
			throw error; // Only the master node can perform the recovery operation!
		} catch (RuntimeException error) {
			throw error;
		} catch (Exception error) {
			throw new RuntimeException(error);
		}
	}

	private void fireSuperStartRecovery() {
		super.startRecovery();
	}

	private void fireSuperTimingRecovery() {
		super.timingRecover();
	}

	public CommandManager getCommandManager() {
		return commandManager;
	}

	public void setCommandManager(CommandManager commandManager) {
		this.commandManager = commandManager;
	}

}
