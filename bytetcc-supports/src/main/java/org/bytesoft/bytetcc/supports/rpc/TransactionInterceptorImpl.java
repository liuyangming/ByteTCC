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
package org.bytesoft.bytetcc.supports.rpc;

import org.apache.log4j.Logger;
import org.bytesoft.transaction.supports.rpc.TransactionInterceptor;
import org.bytesoft.transaction.supports.rpc.TransactionRequest;
import org.bytesoft.transaction.supports.rpc.TransactionResponse;

public class TransactionInterceptorImpl implements TransactionInterceptor {
	static final Logger logger = Logger.getLogger(TransactionInterceptorImpl.class.getSimpleName());

	public void beforeSendRequest(TransactionRequest request) throws IllegalStateException {
	}

	public void afterReceiveRequest(TransactionRequest request) throws IllegalStateException {
	}

	public void beforeSendResponse(TransactionResponse response) throws IllegalStateException {
	}

	public void afterReceiveResponse(TransactionResponse response) throws IllegalStateException {
	}

}
