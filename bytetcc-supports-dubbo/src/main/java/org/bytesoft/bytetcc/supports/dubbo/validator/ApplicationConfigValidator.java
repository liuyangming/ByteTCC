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
package org.bytesoft.bytetcc.supports.dubbo.validator;

import java.util.List;

import org.bytesoft.bytetcc.supports.dubbo.DubboConfigValidator;
import org.springframework.beans.BeansException;
import org.springframework.beans.FatalBeanException;
import org.springframework.beans.factory.config.BeanDefinition;

public class ApplicationConfigValidator implements DubboConfigValidator {

	private List<BeanDefinition> definitionList;

	public void validate() throws BeansException {
		if (this.definitionList == null || this.definitionList.isEmpty()) {
			throw new FatalBeanException("There is no application name specified!");
		} else if (this.definitionList.size() > 1) {
			throw new FatalBeanException("There are more than one application name specified!");
		}
	}

	public List<BeanDefinition> getDefinitionList() {
		return definitionList;
	}

	public void setDefinitionList(List<BeanDefinition> definitionList) {
		this.definitionList = definitionList;
	}

}
