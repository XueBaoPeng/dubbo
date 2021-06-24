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
package org.apache.dubbo.rpc.protocol.memcached;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.constants.RemotingConstants;
import org.apache.dubbo.rpc.AsyncRpcResult;
import org.apache.dubbo.rpc.Exporter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.protocol.AbstractInvoker;
import org.apache.dubbo.rpc.protocol.AbstractProtocol;

import net.rubyeye.xmemcached.MemcachedClient;
import net.rubyeye.xmemcached.MemcachedClientBuilder;
import net.rubyeye.xmemcached.XMemcachedClientBuilder;
import net.rubyeye.xmemcached.exception.MemcachedException;
import net.rubyeye.xmemcached.utils.AddrUtil;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * MemcachedProtocol
 * dubbo实现memcached协议是基于Memcached，Memcached 是一个高效的 KV 缓存服务器，在dubbo中没有涉及到关于memcached协议的服务暴露，只有服务引用
 * ，因为在访问Memcached服务器时，Memcached客户端可以在服务器上存储也可以获取。
 */
public class MemcachedProtocol extends AbstractProtocol {
    /**
     * 默认端口号
     */
    public static final int DEFAULT_PORT = 11211;

    @Override
    public int getDefaultPort() {
        return DEFAULT_PORT;
    }

    @Override
    public <T> Exporter<T> export(final Invoker<T> invoker) throws RpcException {
        // 不支持memcached服务暴露
        throw new UnsupportedOperationException("Unsupported export memcached service. url: " + invoker.getUrl());
    }

    @Override
    public <T> Invoker<T> protocolBindingRefer(final Class<T> type, final URL url) throws RpcException {
        try {
            // 获得地址
            String address = url.getAddress();
            // 获得备用地址
            String backup = url.getParameter(RemotingConstants.BACKUP_KEY);
            // 把备用地址拼接上
            if (backup != null && backup.length() > 0) {
                address += "," + backup;
            }
            // 创建Memcached客户端构造器
            MemcachedClientBuilder builder = new XMemcachedClientBuilder(AddrUtil.getAddresses(address));
            // 创建客户端
            final MemcachedClient memcachedClient = builder.build();
            // 到期时间参数配置
            final int expiry = url.getParameter("expiry", 0);
            // 获得值命令
            final String get = url.getParameter("get", "get");
            // 添加值命令根据类型来取决是put还是set
            final String set = url.getParameter("set", Map.class.equals(type) ? "put" : "set");
            // 删除值命令
            final String delete = url.getParameter("delete", Map.class.equals(type) ? "remove" : "delete");
            return new AbstractInvoker<T>(type, url) {
                @Override
                protected Result doInvoke(Invocation invocation) throws Throwable {
                    try {
                        Object value = null;
                        // 如果是获取方法名的值
                        if (get.equals(invocation.getMethodName())) {
                            // 如果参数长度不等于1，则抛出异常
                            if (invocation.getArguments().length != 1) {
                                throw new IllegalArgumentException("The memcached get method arguments mismatch, must only one arguments. interface: " + type.getName() + ", method: " + invocation.getMethodName() + ", url: " + url);
                            }
                            // 否则调用get方法来获取
                            value = memcachedClient.get(String.valueOf(invocation.getArguments()[0]));
                        } else if (set.equals(invocation.getMethodName())) {
                            // 如果参数长度不为2，则抛出异常
                            if (invocation.getArguments().length != 2) {
                                throw new IllegalArgumentException("The memcached set method arguments mismatch, must be two arguments. interface: " + type.getName() + ", method: " + invocation.getMethodName() + ", url: " + url);
                            }
                            // 无论任何现有值如何，都在缓存中设置一个对象
                            memcachedClient.set(String.valueOf(invocation.getArguments()[0]), expiry, invocation.getArguments()[1]);
                        } else if (delete.equals(invocation.getMethodName())) {
                            // 删除操作只有一个参数，如果参数长度不等于1，则抛出异常
                            if (invocation.getArguments().length != 1) {
                                throw new IllegalArgumentException("The memcached delete method arguments mismatch, must only one arguments. interface: " + type.getName() + ", method: " + invocation.getMethodName() + ", url: " + url);
                            }
                            // 删除某个值
                            memcachedClient.delete(String.valueOf(invocation.getArguments()[0]));
                        } else {
                            // 不支持的操作
                            throw new UnsupportedOperationException("Unsupported method " + invocation.getMethodName() + " in memcached service.");
                        }
                        return AsyncRpcResult.newDefaultAsyncResult(value, invocation);
                    } catch (Throwable t) {
                        RpcException re = new RpcException("Failed to invoke memcached service method. interface: " + type.getName() + ", method: " + invocation.getMethodName() + ", url: " + url + ", cause: " + t.getMessage(), t);
                        if (t instanceof TimeoutException || t instanceof SocketTimeoutException) {
                            re.setCode(RpcException.TIMEOUT_EXCEPTION);
                        } else if (t instanceof MemcachedException || t instanceof IOException) {
                            re.setCode(RpcException.NETWORK_EXCEPTION);
                        }
                        throw re;
                    }
                }

                @Override
                public void destroy() {
                    super.destroy();
                    try {
                        // 关闭客户端
                        memcachedClient.shutdown();
                    } catch (Throwable e) {
                        logger.warn(e.getMessage(), e);
                    }
                }
            };
        } catch (Throwable t) {
            throw new RpcException("Failed to refer memcached service. interface: " + type.getName() + ", url: " + url + ", cause: " + t.getMessage(), t);
        }
    }

}
