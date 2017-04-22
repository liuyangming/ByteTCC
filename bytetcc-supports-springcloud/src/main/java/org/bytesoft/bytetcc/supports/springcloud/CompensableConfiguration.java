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
package org.bytesoft.bytetcc.supports.springcloud;

import org.bytesoft.bytetcc.supports.springcloud.ext.CompensableInterceptor;
import org.bytesoft.bytetcc.supports.springcloud.ext.CompensableRibbonRule;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurerAdapter;

import feign.Feign;
import feign.Feign.Builder;
import feign.InvocationHandlerFactory;

@Configuration
public class CompensableConfiguration extends WebMvcConfigurerAdapter {

	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(this.getCompensableInterceptor());
	}

	@org.springframework.context.annotation.Bean
	public InvocationHandlerFactory getInvocationHandlerFactory() {
		return this.getCompensableInterceptor();
	}

	@org.springframework.context.annotation.Bean
	public Builder getFeignBuilder() {
		return Feign.builder().invocationHandlerFactory(this.getInvocationHandlerFactory());
	}

	@org.springframework.context.annotation.Bean
	public CompensableRibbonRule getCompensableRibbonRule() {
		return new CompensableRibbonRule();
	}

	@org.springframework.context.annotation.Bean
	public CompensableInterceptor getCompensableInterceptor() {
		return new CompensableInterceptor();
	}

	@org.springframework.cloud.client.loadbalancer.LoadBalanced
	@org.springframework.context.annotation.Bean
	public RestTemplate restTemplate() {
		RestTemplate restTemplate = new RestTemplate();

		CompensableInterceptor interceptor = this.getCompensableInterceptor();
		restTemplate.getInterceptors().add(interceptor);

		return restTemplate;
	}

}
