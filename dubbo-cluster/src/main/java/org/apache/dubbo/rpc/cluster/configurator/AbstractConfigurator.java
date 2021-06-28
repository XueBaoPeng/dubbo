/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.rpc.cluster.configurator;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.remoting.Constants;
import org.apache.dubbo.rpc.cluster.Configurator;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.apache.dubbo.common.constants.CommonConstants.ANYHOST_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.ANY_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.APPLICATION_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.CONSUMER;
import static org.apache.dubbo.common.constants.CommonConstants.ENABLED_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.GROUP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.INTERFACES;
import static org.apache.dubbo.common.constants.CommonConstants.PROVIDER;
import static org.apache.dubbo.common.constants.CommonConstants.SIDE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.VERSION_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.CATEGORY_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.COMPATIBLE_CONFIG_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.DYNAMIC_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.CONFIG_VERSION_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.OVERRIDE_PROVIDERS_KEY;

/**
 * AbstractOverrideConfigurator
 * 该类实现了Configurator接口，是配置规则 抽象类，配置有两种方式，一种是没有时添加配置，这种暂时没有用到，另一种是覆盖配置。
 */
public abstract class AbstractConfigurator implements Configurator {

    private static final String TILDE = "~";

    private final URL configuratorUrl;

    public AbstractConfigurator(URL url) {
        if (url == null) {
            throw new IllegalArgumentException("configurator url == null");
        }
        this.configuratorUrl = url;
    }

    @Override
    public URL getUrl() {
        return configuratorUrl;
    }

    @Override
    public URL configure(URL url) {
        // If override url is not enabled or is invalid, just return.
        if (!configuratorUrl.getParameter(ENABLED_KEY, true) || configuratorUrl.getHost() == null || url == null || url.getHost() == null) {
            return url;
        }
        /*
         * This if branch is created since 2.7.0.
         */
        String apiVersion = configuratorUrl.getParameter(CONFIG_VERSION_KEY);
        if (StringUtils.isNotEmpty(apiVersion)) {
            String currentSide = url.getParameter(SIDE_KEY);
            String configuratorSide = configuratorUrl.getParameter(SIDE_KEY);
            if (currentSide.equals(configuratorSide) && CONSUMER.equals(configuratorSide) && 0 == configuratorUrl.getPort()) {
                url = configureIfMatch(NetUtils.getLocalHost(), url);
            } else if (currentSide.equals(configuratorSide) && PROVIDER.equals(configuratorSide) &&
                    url.getPort() == configuratorUrl.getPort()) {
                url = configureIfMatch(url.getHost(), url);
            }
        }
        /*
         * This else branch is deprecated and is left only to keep compatibility with versions before 2.7.0
         */
        else {
            url = configureDeprecated(url);
        }
        return url;
    }

    @Deprecated
    private URL configureDeprecated(URL url) {
        // If override url has port, means it is a provider address. We want to control a specific provider with this override url, it may take effect on the specific provider instance or on consumers holding this provider instance.
        // 如果覆盖url具有端口，则表示它是提供者地址。我们希望使用此覆盖URL控制特定提供程序，它可以在提供端生效 也可以在消费端生效。
        if (configuratorUrl.getPort() != 0) {
            if (url.getPort() == configuratorUrl.getPort()) {
                return configureIfMatch(url.getHost(), url);
            }
        } else {
            /*
             *  override url don't have a port, means the ip override url specify is a consumer address or 0.0.0.0.
             *  1.If it is a consumer ip address, the intention is to control a specific consumer instance, it must takes effect at the consumer side, any provider received this override url should ignore.
             *  2.If the ip is 0.0.0.0, this override url can be used on consumer, and also can be used on provider.
             */
            // 配置规则，URL 没有端口，意味着override 输入消费端地址 或者 0.0.0.0
            if (url.getParameter(SIDE_KEY, PROVIDER).equals(CONSUMER)) {
                // NetUtils.getLocalHost is the ip address consumer registered to registry.
                // 如果它是一个消费者ip地址，目的是控制一个特定的消费者实例，它必须在消费者一方生效，任何提供者收到这个覆盖url应该忽略;
                return configureIfMatch(NetUtils.getLocalHost(), url);
            } else if (url.getParameter(SIDE_KEY, CONSUMER).equals(PROVIDER)) {
                // take effect on all providers, so address must be 0.0.0.0, otherwise it won't flow to this if branch
                // 如果ip为0.0.0.0，则此覆盖url可以在使用者上使用，也可以在提供者上使用
                return configureIfMatch(ANYHOST_VALUE, url);
            }
        }
        return url;
    }

    /**
     * 该方法是当条件匹配时，才对url进行配置。
     * @param host
     * @param url
     * @return
     */
    private URL configureIfMatch(String host, URL url) {
        // 匹配 Host
        if (ANYHOST_VALUE.equals(configuratorUrl.getHost()) || host.equals(configuratorUrl.getHost())) {
            // TODO, to support wildcards
            String providers = configuratorUrl.getParameter(OVERRIDE_PROVIDERS_KEY);
            if (StringUtils.isEmpty(providers) || providers.contains(url.getAddress()) || providers.contains(ANYHOST_VALUE)) {
                String configApplication = configuratorUrl.getParameter(APPLICATION_KEY,
                        configuratorUrl.getUsername());
                // 匹配 "application"
                String currentApplication = url.getParameter(APPLICATION_KEY, url.getUsername());
                if (configApplication == null || ANY_VALUE.equals(configApplication)
                        || configApplication.equals(currentApplication)) {
                    Set<String> conditionKeys = new HashSet<String>();
                    // 配置 URL 中的条件 KEYS 集合。其中下面四个 KEY ，不算是条件，而是内置属性。考虑到下面要移除，所以添加到该集合中。
                    conditionKeys.add(CATEGORY_KEY);
                    conditionKeys.add(Constants.CHECK_KEY);
                    conditionKeys.add(DYNAMIC_KEY);
                    conditionKeys.add(ENABLED_KEY);
                    conditionKeys.add(GROUP_KEY);
                    conditionKeys.add(VERSION_KEY);
                    conditionKeys.add(APPLICATION_KEY);
                    conditionKeys.add(SIDE_KEY);
                    conditionKeys.add(CONFIG_VERSION_KEY);
                    conditionKeys.add(COMPATIBLE_CONFIG_KEY);
                    conditionKeys.add(INTERFACES);
                    for (Map.Entry<String, String> entry : configuratorUrl.getParameters().entrySet()) {
                        String key = entry.getKey();
                        String value = entry.getValue();
                        boolean startWithTilde = startWithTilde(key);
                        if (startWithTilde || APPLICATION_KEY.equals(key) || SIDE_KEY.equals(key)) {
                            if (startWithTilde) {
                                // 添加搭配条件集合
                                conditionKeys.add(key);
                            }
                            if (value != null && !ANY_VALUE.equals(value)
                                    && !value.equals(url.getParameter(startWithTilde ? key.substring(1) : key))) {
                                return url;
                            }
                        }
                    }
                    return doConfigure(url, configuratorUrl.removeParameters(conditionKeys));
                }
            }
        }
        return url;
    }

    private boolean startWithTilde(String key) {
        if (StringUtils.isNotEmpty(key) && key.startsWith(TILDE)) {
            return true;
        }
        return false;
    }

    protected abstract URL doConfigure(URL currentUrl, URL configUrl);

}
