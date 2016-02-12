/**
 * Copyright 2014 yangming.liu<liuyangming@gmail.com>.
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
package org.bytesoft.bytetcc.supports;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Set;

import javax.transaction.xa.Xid;

import org.bytesoft.bytetcc.archive.CompensableArchive;
import org.bytesoft.transaction.logger.TransactionLogger;

public interface CompensableTransactionLogger extends TransactionLogger {

	/* compensable */
	public void updateCompensable(Xid transactionXid, CompensableArchive archive);

	/* default transaction logger */
	public static CompensableTransactionLogger defaultTransactionLogger = NullTransactionLoggerHanlder
			.getNullTransactionLogger();

	public static class NullTransactionLoggerHanlder implements InvocationHandler {
		private static final NullTransactionLoggerHanlder instance = new NullTransactionLoggerHanlder();

		public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
			Class<?> clazz = method.getReturnType();
			if (Void.TYPE.equals(clazz)) {
				return null;
			} else if (Set.class.equals(clazz)) {
				return this.newInstance(HashSet.class);
			} else {
				return null;
			}
		}

		private Object newInstance(Class<?> clazz) {
			try {
				return clazz.newInstance();
			} catch (Exception ex) {
				return null;
			}
		}

		public static CompensableTransactionLogger getNullTransactionLogger() {
			return (CompensableTransactionLogger) Proxy.newProxyInstance(CompensableTransactionLogger.class.getClassLoader(),
					new Class[] { CompensableTransactionLogger.class }, instance);
		}
	}
}
