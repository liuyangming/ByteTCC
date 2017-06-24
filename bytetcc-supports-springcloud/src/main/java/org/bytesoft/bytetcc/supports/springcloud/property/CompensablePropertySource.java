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
package org.bytesoft.bytetcc.supports.springcloud.property;

import org.apache.commons.lang3.StringUtils;
import org.bytesoft.bytetcc.supports.springcloud.feign.CompensableClientRegistry;
import org.bytesoft.bytetcc.supports.springcloud.loadbalancer.CompensableLoadBalancerRuleImpl;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.support.EncodedResource;

public class CompensablePropertySource extends PropertySource<Object> {

	private boolean enabled;

	public CompensablePropertySource(String name, EncodedResource source) {
		super(name, source);

		EncodedResource encoded = (EncodedResource) this.getSource();
		AbstractResource resource = (AbstractResource) encoded.getResource();
		String path = resource.getFilename();

		if (StringUtils.isBlank(path)) {
			return;
		}

		String[] values = path.split(":");
		if (values.length != 2) {
			return;
		}

		String protocol = values[0];
		String resName = values[1];
		if ("bytetcc".equalsIgnoreCase(protocol) == false) {
			return;
		} else if ("loadbalancer.config".equalsIgnoreCase(resName) == false) {
			return;
		}

		this.enabled = true;

	}

	public Object getProperty(String name) {
		if (this.enabled == false || name == null || StringUtils.isBlank(name) || name.indexOf(".") < 0) {
			return null;
		}

		CompensableClientRegistry registry = CompensableClientRegistry.getInstance();

		int index = name.indexOf(".");
		String client = name.substring(0, index);
		String suffix = name.substring(index);
		if (registry.containsClient(client) && ".ribbon.NFLoadBalancerRuleClassName".equals(suffix)) {
			return CompensableLoadBalancerRuleImpl.class.getName();
		}

		return null;
	}

}
