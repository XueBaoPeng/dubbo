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
package org.apache.dubbo.rpc.listener;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.InvokerListener;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;

import java.util.List;

/**
 * ListenerInvoker
 * 该类实现了Invoker，是服务引用监听器的包装类。
 */
public class ListenerInvokerWrapper<T> implements Invoker<T> {

    private static final Logger logger = LoggerFactory.getLogger(ListenerInvokerWrapper.class);
    /**
     * invoker对象
     */
    private final Invoker<T> invoker;
    /**
     * 监听器集合
     */
    private final List<InvokerListener> listeners;

    //构造方法中直接调用了监听器的服务引用
    public ListenerInvokerWrapper(Invoker<T> invoker, List<InvokerListener> listeners) {
        // 如果invoker为空则抛出异常
        if (invoker == null) {
            throw new IllegalArgumentException("invoker == null");
        }
        this.invoker = invoker;
        this.listeners = listeners;
        // 遍历监听器
        if (CollectionUtils.isNotEmpty(listeners)) {
            for (InvokerListener listener : listeners) {
                if (listener != null) {
                    try {
                        // 调用在服务引用的时候进行监听
                        listener.referred(invoker);
                    } catch (Throwable t) {
                        logger.error(t.getMessage(), t);
                    }
                }
            }
        }
    }

    @Override
    public Class<T> getInterface() {
        return invoker.getInterface();
    }

    @Override
    public URL getUrl() {
        return invoker.getUrl();
    }

    @Override
    public boolean isAvailable() {
        return invoker.isAvailable();
    }

    @Override
    public Result invoke(Invocation invocation) throws RpcException {
        return invoker.invoke(invocation);
    }

    @Override
    public String toString() {
        return getInterface() + " -> " + (getUrl() == null ? " " : getUrl().toString());
    }

    @Override
    public void destroy() {
        try {
            // 销毁invoker
            invoker.destroy();
        } finally {
            // 销毁所有监听的实体域
            if (CollectionUtils.isNotEmpty(listeners)) {
                for (InvokerListener listener : listeners) {
                    if (listener != null) {
                        try {
                            listener.destroyed(invoker);
                        } catch (Throwable t) {
                            logger.error(t.getMessage(), t);
                        }
                    }
                }
            }
        }
    }

}
