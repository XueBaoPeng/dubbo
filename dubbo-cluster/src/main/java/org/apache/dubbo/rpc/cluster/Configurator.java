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
package org.apache.dubbo.rpc.cluster;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.utils.CollectionUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.apache.dubbo.common.constants.CommonConstants.ANYHOST_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.EMPTY_PROTOCOL;
import static org.apache.dubbo.rpc.cluster.Constants.PRIORITY_KEY;

/**
 * Configurator. (SPI, Prototype, ThreadSafe)
 * 该接口是配置规则的接口，定义了两个方法，第一个是配置规则，并且生成url，
 * 第二个是把配置配置到旧的url中，其实都是在url上应用规则。
 */
public interface Configurator extends Comparable<Configurator> {

    /**
     * Get the configurator url.
     * 配置规则，生成url
     * @return configurator url.
     */
    URL getUrl();

    /**
     * Configure the provider url.
     * 把规则配置到URL中
     * @param url - old provider url.
     * @return new provider url.
     */
    URL configure(URL url);


    /**
     * Convert override urls to map for use when re-refer. Send all rules every time, the urls will be reassembled and
     * calculated
     *
     * URL contract:
     * <ol>
     * <li>override://0.0.0.0/...( or override://ip:port...?anyhost=true)&para1=value1... means global rules
     * (all of the providers take effect)</li>
     * <li>override://ip:port...?anyhost=false Special rules (only for a certain provider)</li>
     * <li>override:// rule is not supported... ,needs to be calculated by registry itself</li>
     * <li>override://0.0.0.0/ without parameters means clearing the override</li>
     * </ol>
     *
     * @param urls URL list to convert
     * @return converted configurator list
     * 该方法是处理配置规则url集合，转换覆盖url映射以便在重新引用时使用，每次发送所有规则，网址将被重新组装和计算。
     */
    static Optional<List<Configurator>> toConfigurators(List<URL> urls) {
        // 如果为空，则返回空集合
        if (CollectionUtils.isEmpty(urls)) {
            return Optional.empty();
        }

        ConfiguratorFactory configuratorFactory = ExtensionLoader.getExtensionLoader(ConfiguratorFactory.class)
                .getAdaptiveExtension();

        List<Configurator> configurators = new ArrayList<>(urls.size());
        // 遍历url集合
        for (URL url : urls) {
            //如果是协议是empty的值，则清空配置集合
            if (EMPTY_PROTOCOL.equals(url.getProtocol())) {
                configurators.clear();
                break;
            }
            // 覆盖的参数集合
            Map<String, String> override = new HashMap<>(url.getParameters());
            //The anyhost parameter of override may be added automatically, it can't change the judgement of changing url
            // 覆盖的anyhost参数可以自动添加，也不能改变更改url的判断
            override.remove(ANYHOST_KEY);
            // 如果需要覆盖添加的值为0，则清空配置
            if (CollectionUtils.isEmptyMap(override)) {
                continue;
            }
            // 加入配置规则集合
            configurators.add(configuratorFactory.getConfigurator(url));
        }
        // 排序
        Collections.sort(configurators);
        return Optional.of(configurators);
    }

    /**
     * Sort by host, then by priority
     * 1. the url with a specific host ip should have higher priority than 0.0.0.0
     * 2. if two url has the same host, compare by priority value；
     * 这是配置的排序策略。先根据host升序，如果相同，再通过priority降序
     */
    @Override
    default int compareTo(Configurator o) {
        if (o == null) {
            return -1;
        }

        // // host 升序
        int ipCompare = getUrl().getHost().compareTo(o.getUrl().getHost());
        // host is the same, sort by priority
        // 如果host相同，则根据priority降序来对比
        if (ipCompare == 0) {
            int i = getUrl().getParameter(PRIORITY_KEY, 0);
            int j = o.getUrl().getParameter(PRIORITY_KEY, 0);
            return Integer.compare(i, j);
        } else {
            return ipCompare;
        }
    }
}
