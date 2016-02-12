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
package org.bytesoft.bytetcc.supports.logger;

import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;

import org.bytesoft.transaction.archive.TransactionArchive;

public class SimpleTransactionLoggerEntry {

	private FileChannel fileChannel;
	private RandomAccessFile accessFile;
	private TransactionArchive archive;

	public FileChannel getFileChannel() {
		return fileChannel;
	}

	public void setFileChannel(FileChannel fileChannel) {
		this.fileChannel = fileChannel;
	}

	public RandomAccessFile getAccessFile() {
		return accessFile;
	}

	public void setAccessFile(RandomAccessFile accessFile) {
		this.accessFile = accessFile;
	}

	public TransactionArchive getArchive() {
		return archive;
	}

	public void setArchive(TransactionArchive archive) {
		this.archive = archive;
	}

}
