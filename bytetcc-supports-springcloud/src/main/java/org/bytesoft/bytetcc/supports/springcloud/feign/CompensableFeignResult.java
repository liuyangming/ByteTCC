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
package org.bytesoft.bytetcc.supports.springcloud.feign;

import java.io.PrintStream;
import java.io.PrintWriter;

import org.bytesoft.compensable.TransactionContext;
import org.bytesoft.transaction.remote.RemoteCoordinator;

public class CompensableFeignResult extends RuntimeException {
	private static final long serialVersionUID = 1L;

	private TransactionContext transactionContext;
	private Object result;
	private RemoteCoordinator remoteParticipant;
	private boolean error;
	private boolean participantValidFlag;

//	public synchronized Throwable fillInStackTrace() {}

	public synchronized Throwable getCause() {
		throw new IllegalStateException("Not supported!");
	}

	public String getLocalizedMessage() {
		throw new IllegalStateException("Not supported!");
	}

	public String getMessage() {
		throw new IllegalStateException("Not supported!");
	}

	public StackTraceElement[] getStackTrace() {
		throw new IllegalStateException("Not supported!");
	}

	public synchronized Throwable initCause(Throwable arg0) {
		throw new IllegalStateException("Not supported!");
	}

	public void printStackTrace() {
		throw new IllegalStateException("Not supported!");
	}

	public void printStackTrace(PrintStream arg0) {
		throw new IllegalStateException("Not supported!");
	}

	public void printStackTrace(PrintWriter arg0) {
		throw new IllegalStateException("Not supported!");
	}

//	public void setStackTrace(StackTraceElement[] arg0) {}

	public TransactionContext getTransactionContext() {
		return transactionContext;
	}

	public void setTransactionContext(TransactionContext transactionContext) {
		this.transactionContext = transactionContext;
	}

	public Object getResult() {
		return result;
	}

	public void setResult(Object result) {
		this.result = result;
	}

	public RemoteCoordinator getRemoteParticipant() {
		return remoteParticipant;
	}

	public void setRemoteParticipant(RemoteCoordinator remoteParticipant) {
		this.remoteParticipant = remoteParticipant;
	}

	public boolean isError() {
		return error;
	}

	public void setError(boolean error) {
		this.error = error;
	}

	public boolean isParticipantValidFlag() {
		return participantValidFlag;
	}

	public void setParticipantValidFlag(boolean participantValidFlag) {
		this.participantValidFlag = participantValidFlag;
	}

}
