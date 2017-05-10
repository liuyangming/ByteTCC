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

import java.util.List;

import feign.MethodMetadata;

public class CompensableFeignContract implements feign.Contract {

	private feign.Contract delegate;

	public List<MethodMetadata> parseAndValidatateMetadata(Class<?> targetType) {
		List<MethodMetadata> metas = this.delegate.parseAndValidatateMetadata(targetType);
		for (int i = 0; i < metas.size(); i++) {
			MethodMetadata meta = metas.get(i);
			if (meta.returnType() == void.class) {
				meta.returnType(Void.class);
			}
		}
		return metas;
	}

	public feign.Contract getDelegate() {
		return delegate;
	}

	public void setDelegate(feign.Contract delegate) {
		this.delegate = delegate;
	}

}
