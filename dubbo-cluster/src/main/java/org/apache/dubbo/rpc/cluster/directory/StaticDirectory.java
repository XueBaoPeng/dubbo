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
package org.apache.dubbo.rpc.cluster.directory;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.RouterChain;

import java.util.Collections;
import java.util.List;

/**
 * StaticDirectory
 * 静态 Directory 实现类，将传入的 invokers 集合，封装成静态的 Directory 对象。
 */
public class StaticDirectory<T> extends AbstractDirectory<T> {
    private static final Logger logger = LoggerFactory.getLogger(StaticDirectory.class);

    private final List<Invoker<T>> invokers;

    public StaticDirectory(List<Invoker<T>> invokers) {
        this(null, invokers, null);
    }

    public StaticDirectory(List<Invoker<T>> invokers, RouterChain<T> routerChain) {
        this(null, invokers, routerChain);
    }

    public StaticDirectory(URL url, List<Invoker<T>> invokers) {
        this(url, invokers, null);
    }

    public StaticDirectory(URL url, List<Invoker<T>> invokers, RouterChain<T> routerChain) {
        super(url == null && CollectionUtils.isNotEmpty(invokers) ? invokers.get(0).getUrl() : url, routerChain, false);
        if (CollectionUtils.isEmpty(invokers)) {
            throw new IllegalArgumentException("invokers == null");
        }
        this.invokers = invokers;
    }

    @Override
    public Class<T> getInterface() {
        return invokers.get(0).getInterface();
    }

    @Override
    public List<Invoker<T>> getAllInvokers() {
        return invokers;
    }

    @Override
    public boolean isAvailable() {
        if (isDestroyed()) {
            return false;
        }
        // 遍历invokers，如果有一个可用，则可用
        for (Invoker<T> invoker : invokers) {
            if (invoker.isAvailable()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void destroy() {
        if (isDestroyed()) {
            return;
        }
        super.destroy();
        // 遍历invokers，销毁所有的invoker
        for (Invoker<T> invoker : invokers) {
            invoker.destroy();
        }
        // 清除集合
        invokers.clear();
    }

    public void buildRouterChain() {
        RouterChain<T> routerChain = RouterChain.buildChain(getUrl());
        routerChain.setInvokers(invokers);
        this.setRouterChain(routerChain);
    }

    @Override
    protected List<Invoker<T>> doList(Invocation invocation) throws RpcException {
        // 获得路由集合
        List<Invoker<T>> finalInvokers = invokers;
        if (routerChain != null) {
            try {
                finalInvokers = routerChain.route(getConsumerUrl(), invocation);
            } catch (Throwable t) {
                logger.error("Failed to execute router: " + getUrl() + ", cause: " + t.getMessage(), t);
            }
        }
        return finalInvokers == null ? Collections.emptyList() : finalInvokers;
    }

}
