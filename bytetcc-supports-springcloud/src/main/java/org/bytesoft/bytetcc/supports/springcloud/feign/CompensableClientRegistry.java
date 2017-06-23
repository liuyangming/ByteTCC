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
package org.bytesoft.bytetcc.supports.springcloud.feign;

import java.util.HashSet;
import java.util.Set;

public class CompensableClientRegistry {
	private static final CompensableClientRegistry instance = new CompensableClientRegistry();

	private final Set<String> clients = new HashSet<String>();

	private CompensableClientRegistry() {
		if (instance != null) {
			throw new IllegalStateException();
		}
	}

	public static CompensableClientRegistry getInstance() {
		return instance;
	}

	public void registerClient(String name) {
		this.clients.add(name);
	}

	public void unRegisterClient(String name) {
		this.clients.remove(name);
	}

	public boolean containsClient(String name) {
		return this.clients.contains(name);
	}

}
