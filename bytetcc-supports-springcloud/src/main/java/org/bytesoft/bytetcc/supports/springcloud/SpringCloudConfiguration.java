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

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Map;

import org.bytesoft.bytetcc.supports.springcloud.ext.CompensableFeignContract;
import org.bytesoft.bytetcc.supports.springcloud.ext.CompensableFeignDecoder;
import org.bytesoft.bytetcc.supports.springcloud.ext.CompensableFeignHandler;
import org.bytesoft.bytetcc.supports.springcloud.ext.CompensableFeignInterceptor;
import org.bytesoft.bytetcc.supports.springcloud.ext.CompensableHandlerInterceptor;
import org.bytesoft.bytetcc.supports.springcloud.ext.CompensableRequestInterceptor;
import org.bytesoft.bytetcc.supports.springcloud.ext.CompensableRibbonRule;
import org.bytesoft.compensable.aware.CompensableEndpointAware;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.HttpMessageConverters;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.cloud.netflix.feign.support.ResponseEntityDecoder;
import org.springframework.cloud.netflix.feign.support.SpringDecoder;
import org.springframework.cloud.netflix.feign.support.SpringMvcContract;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurerAdapter;

import feign.Feign;
import feign.Feign.Builder;
import feign.InvocationHandlerFactory;
import feign.Target;

@Configuration
public class SpringCloudConfiguration extends WebMvcConfigurerAdapter implements CompensableEndpointAware {

	private String endpoint;

	@Autowired
	private ObjectFactory<HttpMessageConverters> messageConverters;

	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(this.getCompensableHandlerInterceptor());
	}

	// @org.springframework.context.annotation.Bean
	public FilterRegistrationBean filterRegistrationBean() {
		FilterRegistrationBean registration = new FilterRegistrationBean();
		return registration;
	}

	@org.springframework.context.annotation.Bean
	public InvocationHandlerFactory getInvocationHandlerFactory() {
		return new InvocationHandlerFactory() {
			@SuppressWarnings("rawtypes")
			public InvocationHandler create(Target target, Map<Method, MethodHandler> dispatch) {
				CompensableFeignHandler handler = new CompensableFeignHandler();
				handler.setTarget(target);
				handler.setHandlers(dispatch);
				return handler;
			}
		};
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
	public CompensableFeignInterceptor getCompensableFeignInterceptor() {
		return new CompensableFeignInterceptor();
	}

	@org.springframework.context.annotation.Bean
	public feign.Contract getCompensableFeignContract() {
		SpringMvcContract springContract = new SpringMvcContract();
		CompensableFeignContract contract = new CompensableFeignContract();
		contract.setDelegate(springContract);
		return contract;
	}

	@org.springframework.context.annotation.Bean
	public feign.codec.Decoder getCompensableFeignDecoder() {
		ResponseEntityDecoder responseEntityecoder = //
				new ResponseEntityDecoder(new SpringDecoder(this.messageConverters));
		return new CompensableFeignDecoder(responseEntityecoder);
	}

	@org.springframework.context.annotation.Bean
	public CompensableHandlerInterceptor getCompensableHandlerInterceptor() {
		CompensableHandlerInterceptor interceptor = new CompensableHandlerInterceptor();
		interceptor.setEndpoint(this.endpoint);
		return interceptor;
	}

	@org.springframework.context.annotation.Bean
	public CompensableRequestInterceptor getCompensableRequestInterceptor() {
		return new CompensableRequestInterceptor();
	}

	@org.springframework.context.annotation.Bean("transactionTemplate")
	public RestTemplate transactionTemplate() {
		RestTemplate restTemplate = new RestTemplate();

		CompensableRequestInterceptor interceptor = this.getCompensableRequestInterceptor();
		restTemplate.getInterceptors().add(interceptor);

		return restTemplate;
	}

	@org.springframework.cloud.client.loadbalancer.LoadBalanced
	@org.springframework.context.annotation.Bean
	public RestTemplate defaultRestTemplate() {
		RestTemplate restTemplate = new RestTemplate();

		CompensableRequestInterceptor interceptor = this.getCompensableRequestInterceptor();
		restTemplate.getInterceptors().add(interceptor);

		return restTemplate;
	}

	public void setEndpoint(String identifier) {
		this.endpoint = identifier;
	}

}
