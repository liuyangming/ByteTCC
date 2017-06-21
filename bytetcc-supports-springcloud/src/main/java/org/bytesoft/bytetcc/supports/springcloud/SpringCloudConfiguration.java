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

import org.bytesoft.bytetcc.supports.springcloud.feign.CompensableFeignContract;
import org.bytesoft.bytetcc.supports.springcloud.feign.CompensableFeignDecoder;
import org.bytesoft.bytetcc.supports.springcloud.feign.CompensableFeignHandler;
import org.bytesoft.bytetcc.supports.springcloud.feign.CompensableFeignInterceptor;
import org.bytesoft.bytetcc.supports.springcloud.ribbon.CompensableRibbonRule;
import org.bytesoft.bytetcc.supports.springcloud.web.CompensableHandlerInterceptor;
import org.bytesoft.bytetcc.supports.springcloud.web.CompensableRequestInterceptor;
import org.bytesoft.compensable.aware.CompensableEndpointAware;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.web.HttpMessageConverters;
import org.springframework.cloud.netflix.feign.support.ResponseEntityDecoder;
import org.springframework.cloud.netflix.feign.support.SpringDecoder;
import org.springframework.cloud.netflix.feign.support.SpringMvcContract;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurerAdapter;

import feign.Feign;
import feign.Feign.Builder;
import feign.InvocationHandlerFactory;
import feign.Target;

@Configuration
public class SpringCloudConfiguration extends WebMvcConfigurerAdapter
		implements CompensableEndpointAware, ApplicationContextAware {

	private String endpoint;
	private ApplicationContext applicationContext;

	public void addInterceptors(InterceptorRegistry registry) {
		CompensableHandlerInterceptor compensableHandlerInterceptor = this.applicationContext
				.getBean(CompensableHandlerInterceptor.class);
		registry.addInterceptor(compensableHandlerInterceptor);
	}

	@org.springframework.context.annotation.Primary
	@org.springframework.context.annotation.Bean
	public InvocationHandlerFactory compensableInvocationHandlerFactory() {
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

	@org.springframework.context.annotation.Primary
	@org.springframework.context.annotation.Bean
	public Builder compensableFeignBuilder(@Autowired InvocationHandlerFactory invocationHandlerFactory) {
		return Feign.builder().invocationHandlerFactory(invocationHandlerFactory);
	}

	@org.springframework.context.annotation.Primary
	@org.springframework.context.annotation.Bean
	public CompensableRibbonRule compensableRibbonRule() {
		return new CompensableRibbonRule();
	}

	@org.springframework.context.annotation.Bean
	public CompensableFeignInterceptor compensableFeignInterceptor() {
		return new CompensableFeignInterceptor();
	}

	@org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(feign.Contract.class)
	@org.springframework.context.annotation.Bean
	public feign.Contract feignContract() {
		return new SpringMvcContract();
	}

	@org.springframework.context.annotation.Primary
	@org.springframework.context.annotation.Bean
	public feign.Contract compensableFeignContract(@Autowired feign.Contract contract) {
		return new CompensableFeignContract(contract);
	}

	@org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(feign.codec.Decoder.class)
	@org.springframework.context.annotation.Bean
	public feign.codec.Decoder feignDecoder(@Autowired ObjectFactory<HttpMessageConverters> messageConverters) {
		return new ResponseEntityDecoder(new SpringDecoder(messageConverters));
	}

	@org.springframework.context.annotation.Primary
	@org.springframework.context.annotation.Bean
	public feign.codec.Decoder compensableFeignDecoder(@Autowired feign.codec.Decoder decoder) {
		return new CompensableFeignDecoder(decoder);
	}

	@org.springframework.context.annotation.Bean
	public CompensableHandlerInterceptor compensableHandlerInterceptor() {
		CompensableHandlerInterceptor interceptor = new CompensableHandlerInterceptor();
		interceptor.setEndpoint(this.endpoint);
		// interceptor.setApplicationContext(this.applicationContext);
		return interceptor;
	}

	@org.springframework.context.annotation.Bean
	public CompensableRequestInterceptor compensableRequestInterceptor() {
		return new CompensableRequestInterceptor();
	}

	@org.springframework.context.annotation.Bean("compensableRestTemplate")
	public RestTemplate transactionTemplate(@Autowired CompensableRequestInterceptor compensableRequestInterceptor) {
		return new RestTemplate();
	}

	@DependsOn("compensableRestTemplate")
	@org.springframework.context.annotation.Bean
	public SpringCloudBeanRegistry beanRegistry(@Qualifier("compensableRestTemplate") @Autowired RestTemplate restTemplate) {
		SpringCloudBeanRegistry registry = SpringCloudBeanRegistry.getInstance();
		registry.setRestTemplate(restTemplate);
		return registry;
	}

	@org.springframework.context.annotation.Primary
	@org.springframework.cloud.client.loadbalancer.LoadBalanced
	@org.springframework.context.annotation.Bean
	public RestTemplate defaultRestTemplate(@Autowired CompensableRequestInterceptor compensableRequestInterceptor) {
		RestTemplate restTemplate = new RestTemplate();
		restTemplate.getInterceptors().add(compensableRequestInterceptor);
		return restTemplate;
	}

	public void setEndpoint(String identifier) {
		this.endpoint = identifier;
	}

	public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
		this.applicationContext = applicationContext;
	}

}
