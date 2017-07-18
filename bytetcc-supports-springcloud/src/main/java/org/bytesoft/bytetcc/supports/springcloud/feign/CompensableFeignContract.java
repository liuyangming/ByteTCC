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

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.cloud.netflix.feign.support.SpringMvcContract;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import feign.MethodMetadata;

public class CompensableFeignContract implements feign.Contract, InitializingBean, ApplicationContextAware {
	private ApplicationContext applicationContext;
	private feign.Contract delegate;

	public CompensableFeignContract() {
	}

	public CompensableFeignContract(feign.Contract contract) {
		this.delegate = contract;
	}

	public void afterPropertiesSet() throws Exception {
		if (this.delegate == null) {
			this.invokeAfterPropertiesSet();
		} // end-if (this.delegate == null)
	}

	public void invokeAfterPropertiesSet() throws Exception {
		feign.Contract feignContract = null;

		String[] beanNameArray = this.applicationContext.getBeanNamesForType(feign.Contract.class);
		for (int i = 0; beanNameArray != null && i < beanNameArray.length; i++) {
			String beanName = beanNameArray[i];
			Object beanInst = this.applicationContext.getBean(beanName);
			if (CompensableFeignContract.class.isInstance(beanInst)) {
				continue;
			} else if (feignContract != null) {
				throw new RuntimeException("There are more than one feign.Contract exists!");
			} else {
				feignContract = (feign.Contract) beanInst;
			}
		}

		if (feignContract == null) {
			feignContract = new SpringMvcContract();
		} // end-if (feignContract == null)

		this.delegate = feignContract;
	}

	public List<MethodMetadata> parseAndValidatateMetadata(Class<?> targetType) {
		List<MethodMetadata> metas = this.delegate.parseAndValidatateMetadata(targetType);
		for (int i = 0; metas != null && i < metas.size(); i++) {
			MethodMetadata meta = metas.get(i);
			if (meta.returnType() == void.class) {
				meta.returnType(Void.class);
			}
		}
		return metas == null ? new ArrayList<MethodMetadata>() : metas;
	}

	public feign.Contract getDelegate() {
		return delegate;
	}

	public void setDelegate(feign.Contract delegate) {
		this.delegate = delegate;
	}

	public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
		this.applicationContext = applicationContext;
	}

}
