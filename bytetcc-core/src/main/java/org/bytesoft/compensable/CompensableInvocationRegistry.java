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
package org.bytesoft.compensable;

import java.util.Map;
import java.util.Stack;
import java.util.concurrent.ConcurrentHashMap;

public final class CompensableInvocationRegistry {
	static final CompensableInvocationRegistry instance = new CompensableInvocationRegistry();

	private Map<Thread, Stack<CompensableInvocation>> invocationMap = new ConcurrentHashMap<Thread, Stack<CompensableInvocation>>();

	private CompensableInvocationRegistry() {
	}

	public void register(CompensableInvocation invocation) {
		Thread current = Thread.currentThread();
		Stack<CompensableInvocation> stack = this.invocationMap.get(current);
		if (stack == null) {
			stack = new Stack<CompensableInvocation>();
			this.invocationMap.put(current, stack);
		}
		stack.push(invocation);
	}

	public CompensableInvocation getCurrent() {
		Thread current = Thread.currentThread();
		Stack<CompensableInvocation> stack = this.invocationMap.get(current);
		if (stack == null || stack.isEmpty()) {
			return null;
		}
		return stack.peek();
	}

	public CompensableInvocation unegister() {
		Thread current = Thread.currentThread();
		Stack<CompensableInvocation> stack = this.invocationMap.get(current);
		if (stack == null || stack.isEmpty()) {
			return null;
		}
		CompensableInvocation invocation = stack.pop();
		if (stack.isEmpty()) {
			this.invocationMap.remove(current);
		}
		return invocation;
	}

	public static CompensableInvocationRegistry getInstance() {
		return instance;
	}
}
