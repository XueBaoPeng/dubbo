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
package org.apache.dubbo.rpc.protocol.injvm;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.UrlUtils;
import org.apache.dubbo.rpc.Exporter;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.protocol.AbstractProtocol;
import org.apache.dubbo.rpc.support.ProtocolUtils;

import java.util.Map;

import static org.apache.dubbo.common.constants.CommonConstants.BROADCAST_CLUSTER;
import static org.apache.dubbo.common.constants.CommonConstants.CLUSTER_KEY;
import static org.apache.dubbo.rpc.Constants.SCOPE_KEY;
import static org.apache.dubbo.rpc.Constants.SCOPE_LOCAL;
import static org.apache.dubbo.rpc.Constants.SCOPE_REMOTE;
import static org.apache.dubbo.rpc.Constants.GENERIC_KEY;
import static org.apache.dubbo.rpc.Constants.LOCAL_PROTOCOL;

/**
 * InjvmProtocol
 * 该类是本地调用的协议实现，其中实现了服务调用和服务暴露方法，并且封装了一个判断是否是本地调用的方法。
 */
public class InjvmProtocol extends AbstractProtocol implements Protocol{
    /**
     * 本地调用  Protocol的实现类key
     */
    public static final String NAME = LOCAL_PROTOCOL;
    /**
     * 默认端口
     */
    public static final int DEFAULT_PORT = 0;
    /**
     * 单例
     */
    private static InjvmProtocol INSTANCE;

    public InjvmProtocol() {
        INSTANCE = this;
    }

    public static InjvmProtocol getInjvmProtocol() {
        if (INSTANCE == null) {
            ExtensionLoader.getExtensionLoader(Protocol.class).getExtension(InjvmProtocol.NAME); // load
        }
        return INSTANCE;
    }

    /**
     * 该方法是获得相关的暴露者
     * @param map
     * @param key
     * @return
     */
    static Exporter<?> getExporter(Map<String, Exporter<?>> map, URL key) {
        Exporter<?> result = null;
        // 如果服务key不是*
        if (!key.getServiceKey().contains("*")) {
            // 直接从集合中取出
            result = map.get(key.getServiceKey());
        } else {
            // 如果 map不为空，则遍历暴露者，来找到对应的exporter
            if (CollectionUtils.isNotEmptyMap(map)) {
                for (Exporter<?> exporter : map.values()) {
                    // 如果是服务key
                    if (UrlUtils.isServiceKeyMatch(key, exporter.getInvoker().getUrl())) {
                        // 赋值
                        result = exporter;
                        break;
                    }
                }
            }
        }
        // 如果没有找到exporter
        if (result == null) {
            // 则返回null
            return null;
        } else if (ProtocolUtils.isGeneric(
                // 如果是泛化调用，则返回null
                result.getInvoker().getUrl().getParameter(GENERIC_KEY))) {
            return null;
        } else {
            return result;
        }
    }

    @Override
    public int getDefaultPort() {
        return DEFAULT_PORT;
    }

    @Override
    public <T> Exporter<T> export(Invoker<T> invoker) throws RpcException {
        return new InjvmExporter<T>(invoker, invoker.getUrl().getServiceKey(), exporterMap);
    }

    @Override
    public <T> Invoker<T> protocolBindingRefer(Class<T> serviceType, URL url) throws RpcException {
        return new InjvmInvoker<T>(serviceType, url, url.getServiceKey(), exporterMap);
    }

    public boolean isInjvmRefer(URL url) {
        // 获得scope配置
        String scope = url.getParameter(SCOPE_KEY);
        // Since injvm protocol is configured explicitly, we don't need to set any extra flag, use normal refer process.
        if (SCOPE_LOCAL.equals(scope) || (url.getParameter(LOCAL_PROTOCOL, false))) {
            // if it's declared as local reference
            // 'scope=local' is equivalent to 'injvm=true', injvm will be deprecated in the future release
            // 如果是injvm，则不是本地调用
            return true;
        } else if (SCOPE_REMOTE.equals(scope)) {
            // it's declared as remote reference
            // 如果它被声明为本地引用 scope = local'相当于'injvm = true'，将在以后的版本中弃用injvm
            return false;
        } else if (url.getParameter(GENERIC_KEY, false)) {
            // generic invocation is not local reference
            return false;
        } else if (getExporter(exporterMap, url) != null) {
            // Broadcast cluster means that multiple machines will be called,
            // which is not converted to injvm protocol at this time.
            // 泛化的调用不是本地调用
            if (BROADCAST_CLUSTER.equalsIgnoreCase(url.getParameter(CLUSTER_KEY))) {
                return false;
            }
            // 默认情况下，如果本地暴露服务，请通过本地引用
            // by default, go through local reference if there's the service exposed locally
            return true;
        } else {
            return false;
        }
    }
}
