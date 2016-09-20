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
package org.bytesoft.bytetcc.supports.zk;

import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;

import org.apache.commons.lang3.StringUtils;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.api.CuratorEvent;
import org.apache.curator.framework.api.CuratorListener;
import org.apache.curator.framework.api.CuratorWatcher;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.framework.state.ConnectionStateListener;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.zookeeper.WatchedEvent;
import org.springframework.beans.BeansException;
import org.springframework.beans.FatalBeanException;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.beans.PropertyValue;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

public class TransactionCuratorClient extends URLStreamHandler implements InitializingBean, DisposableBean,
		BeanFactoryPostProcessor, CuratorWatcher, CuratorListener, ConnectionStateListener {

	static final String KEY_DUBBO_REGISTRY_ZOOKEEPER = "zookeeper";
	static final String KEY_DUBBO_REGISTRY_ADDRESS = "address";
	static final String KEY_DUBBO_REGISTRY_PROTOCOL = "protocol";
	static final String KEY_FIELD_ZOOKEEPER_ADDRESS = "zookeeperAddr";

	private String zookeeperAddr;
	private CuratorFramework curatorFramework;

	public void afterPropertiesSet() throws Exception {
		this.curatorFramework = CuratorFrameworkFactory.builder() //
				.connectString(this.zookeeperAddr).namespace("org.bytesoft.bytetcc") //
				.sessionTimeoutMs(1000 * 3).retryPolicy(new ExponentialBackoffRetry(1000, 3)).build();
		this.curatorFramework.getCuratorListenable().addListener(this);
		this.curatorFramework.getConnectionStateListenable().addListener(this);
		this.curatorFramework.start();
	}

	public void destroy() throws Exception {
		this.curatorFramework.close();
	}

	public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
		String[] beanNameArray = beanFactory
				.getBeanNamesForType(org.bytesoft.bytetcc.supports.zk.TransactionCuratorClient.class);
		if (beanNameArray.length != 1) {
			throw new FatalBeanException("Multiple TransactionCuratorClient instance exists!");
		}

		BeanDefinition beanDef = beanFactory.getBeanDefinition(beanNameArray[0]);
		MutablePropertyValues mpv = beanDef.getPropertyValues();
		PropertyValue pv = mpv.getPropertyValue(KEY_FIELD_ZOOKEEPER_ADDRESS);
		if (pv == null || pv.getValue() == null || StringUtils.isBlank(String.valueOf(pv.getValue()))) {
			this.initZookeeperAddressIfNecessary(beanFactory);
		}

	}

	public void initZookeeperAddressIfNecessary(ConfigurableListableBeanFactory beanFactory) throws BeansException {
		String[] beanNameArray = beanFactory.getBeanDefinitionNames();
		for (int i = 0; i < beanNameArray.length; i++) {
			String beanName = beanNameArray[i];
			BeanDefinition beanDef = beanFactory.getBeanDefinition(beanName);
			if (com.alibaba.dubbo.config.RegistryConfig.class.getName().equals(beanDef.getBeanClassName())) {
				MutablePropertyValues mpv = beanDef.getPropertyValues();
				PropertyValue protpv = mpv.getPropertyValue(KEY_DUBBO_REGISTRY_PROTOCOL);
				PropertyValue addrpv = mpv.getPropertyValue(KEY_DUBBO_REGISTRY_ADDRESS);

				String protocol = null;
				String address = null;
				if (addrpv == null) {
					throw new FatalBeanException("zookeeper address cannot be null!"); // should never happen
				} else if (protpv == null) {
					String value = String.valueOf(addrpv.getValue());
					try {
						URL url = new URL(null, value, this);
						protocol = url.getProtocol();
						address = url.getAuthority();
					} catch (Exception ex) {
						throw new FatalBeanException("Unsupported format!");
					}
				} else {
					protocol = String.valueOf(protpv.getValue());
					String value = StringUtils.trimToEmpty(String.valueOf(addrpv.getValue()));
					int index = value.indexOf(protocol);
					if (index == -1) {
						address = value;
					} else if (index == 0) {
						String str = StringUtils.trimToEmpty(value.substring(protocol.length()));
						if (str.startsWith("://")) {
							address = StringUtils.trimToEmpty(str.substring(3));
						} else {
							throw new FatalBeanException("Unsupported format!");
						}
					} else {
						throw new FatalBeanException("Unsupported format!");
					}
				}

				if (KEY_DUBBO_REGISTRY_ZOOKEEPER.equalsIgnoreCase(protocol) == false) {
					throw new FatalBeanException("Unsupported protocol!");
				}

				String addrKey = "zookeeperAddr";
				String[] watcherBeanArray = beanFactory
						.getBeanNamesForType(org.bytesoft.bytetcc.supports.zk.TransactionCuratorClient.class);
				BeanDefinition watcherBeanDef = beanFactory.getBeanDefinition(watcherBeanArray[0]);
				MutablePropertyValues warcherMpv = watcherBeanDef.getPropertyValues();
				PropertyValue warcherPv = warcherMpv.getPropertyValue(addrKey);
				if (warcherPv == null) {
					warcherMpv.addPropertyValue(new PropertyValue(addrKey, address));
				} else {
					warcherMpv.removePropertyValue(addrKey);
					warcherMpv.addPropertyValue(new PropertyValue(addrKey, address));
				}

			}
		}
	}

	public void stateChanged(CuratorFramework client, ConnectionState newState) {
	}

	public void eventReceived(CuratorFramework client, CuratorEvent event) throws Exception {
	}

	public void process(WatchedEvent event) throws Exception {
	}

	public String getZookeeperAddr() {
		return zookeeperAddr;
	}

	public void setZookeeperAddr(String zookeeperAddr) {
		this.zookeeperAddr = zookeeperAddr;
	}

	protected URLConnection openConnection(URL u) throws IOException {
		throw new IOException("Not supported yet!");
	}

}
