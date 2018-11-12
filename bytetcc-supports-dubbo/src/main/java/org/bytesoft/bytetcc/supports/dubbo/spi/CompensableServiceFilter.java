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
package org.bytesoft.bytetcc.supports.dubbo.spi;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

import org.bytesoft.bytetcc.CompensableCoordinator;
import org.bytesoft.bytetcc.supports.dubbo.CompensableBeanRegistry;
import org.bytesoft.compensable.CompensableBeanFactory;

import com.alibaba.com.caucho.hessian.io.HessianHandle;
import com.alibaba.dubbo.rpc.Filter;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcException;

public class CompensableServiceFilter implements Filter {
	private final Filter primaryFilter = new CompensablePrimaryFilter();
	private final Filter secondaryFilter = new CompensableSecondaryFilter();

	public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
		CompensableBeanFactory beanFactory = CompensableBeanRegistry.getInstance().getBeanFactory();
		CompensableCoordinator compensableCoordinator = //
				(CompensableCoordinator) beanFactory.getCompensableNativeParticipant();
		if (compensableCoordinator.isStatefully()) {
			return this.secondaryFilter.invoke(invoker, invocation);
		} else {
			return this.primaryFilter.invoke(invoker, invocation);
		}
	}

	static class InvocationResult implements HessianHandle, Serializable {
		private static final long serialVersionUID = 1L;

		private Throwable error;
		private Object value;
		private final Map<String, Serializable> variables = new HashMap<String, Serializable>();

		public boolean isFailure() {
			return this.error != null;
		}

		public Object getValue() {
			return value;
		}

		public void setValue(Object value) {
			this.value = value;
		}

		public void setVariable(String key, Serializable value) {
			this.variables.put(key, value);
		}

		public Serializable getVariable(String key) {
			return this.variables.get(key);
		}

		public Throwable getError() {
			return error;
		}

		public void setError(Throwable error) {
			this.error = error;
		}

	}

}
