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
package org.bytesoft.bytetcc.supports.spring.rpc;

import org.bytesoft.byterpc.RemoteInvocation;
import org.bytesoft.byterpc.RemoteInvocationResult;
import org.bytesoft.byterpc.supports.RemoteInterceptor;
import org.bytesoft.bytetcc.common.TransactionConfigurator;
import org.bytesoft.transaction.rpc.TransactionInterceptor;

public class ByteTccRemoteInterceptor implements RemoteInterceptor {

	public void onDeliverInvocation(RemoteInvocation invocation) {
		ByteTccRemoteInvocation request = (ByteTccRemoteInvocation) invocation;
		TransactionConfigurator configurator = TransactionConfigurator.getInstance();
		TransactionInterceptor interceptor = configurator.getTransactionInterceptor();
		interceptor.beforeSendRequest(request);
	}

	public void onReceiveInvocation(RemoteInvocation invocation) {
		ByteTccRemoteInvocation request = (ByteTccRemoteInvocation) invocation;
		TransactionConfigurator configurator = TransactionConfigurator.getInstance();
		TransactionInterceptor interceptor = configurator.getTransactionInterceptor();
		interceptor.afterReceiveRequest(request);
	}

	public void onDeliverInvocationResult(RemoteInvocationResult result) {
		ByteTccRemoteInvocationResult response = (ByteTccRemoteInvocationResult) result;
		TransactionConfigurator configurator = TransactionConfigurator.getInstance();
		TransactionInterceptor interceptor = configurator.getTransactionInterceptor();
		interceptor.beforeSendResponse(response);
	}

	public void onReceiveInvocationResult(RemoteInvocationResult result) {
		ByteTccRemoteInvocationResult response = (ByteTccRemoteInvocationResult) result;
		TransactionConfigurator configurator = TransactionConfigurator.getInstance();
		TransactionInterceptor interceptor = configurator.getTransactionInterceptor();
		interceptor.afterReceiveResponse(response);
	}

}
