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
package org.bytesoft.bytetcc.supports.spring.rpc;

import org.bytesoft.byterpc.RemoteInvocation;
import org.bytesoft.byterpc.RemoteInvocationResult;
import org.bytesoft.byterpc.supports.RemoteInterceptor;
import org.bytesoft.bytetcc.CompensableTransactionBeanFactory;
import org.bytesoft.bytetcc.aware.CompensableTransactionBeanFactoryAware;
import org.bytesoft.transaction.supports.rpc.TransactionInterceptor;

public class ByteTccRemoteInterceptor implements RemoteInterceptor, CompensableTransactionBeanFactoryAware {

	private CompensableTransactionBeanFactory beanFactory;

	public void onDeliverInvocation(RemoteInvocation invocation) {
		ByteTccRemoteInvocation request = (ByteTccRemoteInvocation) invocation;
		TransactionInterceptor interceptor = this.beanFactory.getTransactionInterceptor();
		interceptor.beforeSendRequest(request);
	}

	public void onReceiveInvocation(RemoteInvocation invocation) {
		ByteTccRemoteInvocation request = (ByteTccRemoteInvocation) invocation;
		TransactionInterceptor interceptor = this.beanFactory.getTransactionInterceptor();
		interceptor.afterReceiveRequest(request);
	}

	public void onDeliverInvocationResult(RemoteInvocationResult result) {
		ByteTccRemoteInvocationResult response = (ByteTccRemoteInvocationResult) result;
		TransactionInterceptor interceptor = this.beanFactory.getTransactionInterceptor();
		interceptor.beforeSendResponse(response);
	}

	public void onReceiveInvocationResult(RemoteInvocationResult result) {
		ByteTccRemoteInvocationResult response = (ByteTccRemoteInvocationResult) result;
		TransactionInterceptor interceptor = this.beanFactory.getTransactionInterceptor();
		interceptor.afterReceiveResponse(response);
	}

	public void setBeanFactory(CompensableTransactionBeanFactory tbf) {
		this.beanFactory = tbf;
	}

}
