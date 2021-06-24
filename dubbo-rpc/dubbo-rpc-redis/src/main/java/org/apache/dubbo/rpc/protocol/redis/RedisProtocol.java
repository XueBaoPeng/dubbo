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
package org.apache.dubbo.rpc.protocol.redis;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.serialize.ObjectInput;
import org.apache.dubbo.common.serialize.ObjectOutput;
import org.apache.dubbo.common.serialize.Serialization;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.remoting.Constants;
import org.apache.dubbo.rpc.AsyncRpcResult;
import org.apache.dubbo.rpc.Exporter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.protocol.AbstractInvoker;
import org.apache.dubbo.rpc.protocol.AbstractProtocol;

import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.exceptions.JedisDataException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Map;

import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_TIMEOUT;
import static org.apache.dubbo.common.constants.CommonConstants.TIMEOUT_KEY;


/**
 * RedisProtocol
 * dubbo支持的redis协议是基于Redis的，Redis 是一个高效的 KV 存储服务器，跟memcached协议实现差不多，
 * 在dubbo中也没有涉及到关于redis协议的服务暴露，只有服务引用，因为在访问服务器时，Redis客户端可以在服务器上存储也可以获取
 */
public class RedisProtocol extends AbstractProtocol {
    /**
     * 默认端口号
     */
    public static final int DEFAULT_PORT = 6379;

    @Override
    public int getDefaultPort() {
        return DEFAULT_PORT;
    }

    @Override
    public <T> Exporter<T> export(final Invoker<T> invoker) throws RpcException {
        // 不支持redis协议的服务暴露，抛出异常
        throw new UnsupportedOperationException("Unsupported export redis service. url: " + invoker.getUrl());
    }

    private Serialization getSerialization(URL url) {
        return ExtensionLoader.getExtensionLoader(Serialization.class).getExtension(url.getParameter(Constants.SERIALIZATION_KEY, "java"));
    }

    @Override
    protected <T> Invoker<T> protocolBindingRefer(final Class<T> type, final URL url) throws RpcException {
        try {
            // 实例化对象池
            GenericObjectPoolConfig config = new GenericObjectPoolConfig();
            // 如果 testOnBorrow 被设置，pool 会在 borrowObject 返回对象之前使用 PoolableObjectFactory的 validateObject 来验证这个对象是否有效
            // 要是对象没通过验证，这个对象会被丢弃，然后重新选择一个新的对象。
            config.setTestOnBorrow(url.getParameter("test.on.borrow", true));
            // 如果 testOnReturn 被设置， pool 会在 returnObject 的时候通过 PoolableObjectFactory 的validateObject 方法验证对象
            // 如果对象没通过验证，对象会被丢弃，不会被放到池中。
            config.setTestOnReturn(url.getParameter("test.on.return", false));
            // 指定空闲对象是否应该使用 PoolableObjectFactory 的 validateObject 校验，如果校验失败，这个对象会从对象池中被清除。
            // 这个设置仅在 timeBetweenEvictionRunsMillis 被设置成正值（ >0） 的时候才会生效。
            config.setTestWhileIdle(url.getParameter("test.while.idle", false));
            if (url.getParameter("max.idle", 0) > 0) {
                // 控制一个pool最多有多少个状态为空闲的jedis实例。
                config.setMaxIdle(url.getParameter("max.idle", 0));
            }
            if (url.getParameter("min.idle", 0) > 0) {
                // 控制一个pool最少有多少个状态为空闲的jedis实例。
                config.setMinIdle(url.getParameter("min.idle", 0));
            }
            if (url.getParameter("max.active", 0) > 0) {
                // 控制一个pool最多有多少个jedis实例。
                config.setMaxTotal(url.getParameter("max.active", 0));
            }
            if (url.getParameter("max.total", 0) > 0) {
                config.setMaxTotal(url.getParameter("max.total", 0));
            }
            if (url.getParameter("max.wait", 0) > 0) {
                //表示当引入一个jedis实例时，最大的等待时间，如果超过等待时间，则直接抛出JedisConnectionException；
                config.setMaxWaitMillis(url.getParameter("max.wait", 0));
            }
            if (url.getParameter("num.tests.per.eviction.run", 0) > 0) {
                // 设置驱逐线程每次检测对象的数量。这个设置仅在 timeBetweenEvictionRunsMillis 被设置成正值（ >0）的时候才会生效。
                config.setNumTestsPerEvictionRun(url.getParameter("num.tests.per.eviction.run", 0));
            }
            if (url.getParameter("time.between.eviction.runs.millis", 0) > 0) {
                // 指定驱逐线程的休眠时间。如果这个值不是正数（ >0），不会有驱逐线程运行。
                config.setTimeBetweenEvictionRunsMillis(url.getParameter("time.between.eviction.runs.millis", 0));
            }
            if (url.getParameter("min.evictable.idle.time.millis", 0) > 0) {
                // 指定最小的空闲驱逐的时间间隔（空闲超过指定的时间的对象，会被清除掉）。
                // 这个设置仅在 timeBetweenEvictionRunsMillis 被设置成正值（ >0）的时候才会生效。
                config.setMinEvictableIdleTimeMillis(url.getParameter("min.evictable.idle.time.millis", 0));
            }
            // 创建redis连接池
            final JedisPool jedisPool = new JedisPool(config, url.getHost(), url.getPort(DEFAULT_PORT),
                    url.getParameter(TIMEOUT_KEY, DEFAULT_TIMEOUT),
                    StringUtils.isBlank(url.getPassword()) ? null : url.getPassword(),
                    url.getParameter("db.index", 0));
            // 获得值的过期时间
            final int expiry = url.getParameter("expiry", 0);
            // 获得get命令
            final String get = url.getParameter("get", "get");
            // 获得set命令
            final String set = url.getParameter("set", Map.class.equals(type) ? "put" : "set");
            // 获得delete命令
            final String delete = url.getParameter("delete", Map.class.equals(type) ? "remove" : "delete");
            return new AbstractInvoker<T>(type, url) {
                @Override
                protected Result doInvoke(Invocation invocation) throws Throwable {
                    Jedis jedis = null;
                    try {
                        jedis = jedisPool.getResource();
                        // 如果是get命令
                        if (get.equals(invocation.getMethodName())) {
                            // get 命令必须只有一个参数
                            if (invocation.getArguments().length != 1) {
                                throw new IllegalArgumentException("The redis get method arguments mismatch, must only one arguments. interface: " + type.getName() + ", method: " + invocation.getMethodName() + ", url: " + url);
                            }
                            // 获得值
                            byte[] value = jedis.get(String.valueOf(invocation.getArguments()[0]).getBytes());
                            if (value == null) {
                                return AsyncRpcResult.newDefaultAsyncResult(invocation);
                            }
                            // 反序列化
                            ObjectInput oin = getSerialization(url).deserialize(url, new ByteArrayInputStream(value));
                            return AsyncRpcResult.newDefaultAsyncResult(oin.readObject(), invocation);
                        } else if (set.equals(invocation.getMethodName())) {
                            // 如果是set命令，参数长度必须是2
                            if (invocation.getArguments().length != 2) {
                                throw new IllegalArgumentException("The redis set method arguments mismatch, must be two arguments. interface: " + type.getName() + ", method: " + invocation.getMethodName() + ", url: " + url);
                            }
                            byte[] key = String.valueOf(invocation.getArguments()[0]).getBytes();
                            // 对需要存入对值进行序列化
                            ByteArrayOutputStream output = new ByteArrayOutputStream();
                            ObjectOutput value = getSerialization(url).serialize(url, output);
                            value.writeObject(invocation.getArguments()[1]);
                            // 存入值
                            jedis.set(key, output.toByteArray());
                            // 设置该key过期时间，不能大于1000s
                            if (expiry > 1000) {
                                jedis.expire(key, expiry / 1000);
                            }
                            return AsyncRpcResult.newDefaultAsyncResult(invocation);
                        } else if (delete.equals(invocation.getMethodName())) {
                            // 如果是删除命令，则参数长度必须是1
                            if (invocation.getArguments().length != 1) {
                                throw new IllegalArgumentException("The redis delete method arguments mismatch, must only one arguments. interface: " + type.getName() + ", method: " + invocation.getMethodName() + ", url: " + url);
                            }
                            // 删除该值
                            jedis.del(String.valueOf(invocation.getArguments()[0]).getBytes());
                            return AsyncRpcResult.newDefaultAsyncResult(invocation);
                        } else {
                            // 否则抛出该操作不支持的异常
                            throw new UnsupportedOperationException("Unsupported method " + invocation.getMethodName() + " in redis service.");
                        }
                    } catch (Throwable t) {
                        RpcException re = new RpcException("Failed to invoke redis service method. interface: " + type.getName() + ", method: " + invocation.getMethodName() + ", url: " + url + ", cause: " + t.getMessage(), t);
                        if (t instanceof SocketTimeoutException) {
                            // 抛出超时异常
                            re.setCode(RpcException.TIMEOUT_EXCEPTION);
                        } else if (t instanceof JedisConnectionException || t instanceof IOException) {
                            // 抛出网络异常
                            re.setCode(RpcException.NETWORK_EXCEPTION);
                        } else if (t instanceof JedisDataException) {
                            // 抛出序列化异常
                            re.setCode(RpcException.SERIALIZATION_EXCEPTION);
                        }
                        throw re;
                    } finally {
                        if (jedis != null) {
                            try {
                                // 关闭连接池
                                jedis.close();
                            } catch (Throwable t) {
                                logger.warn("returnResource error: " + t.getMessage(), t);
                            }
                        }
                    }
                }

                @Override
                public void destroy() {
                    super.destroy();
                    try {
                        // 关闭连接池
                        jedisPool.destroy();
                    } catch (Throwable e) {
                        logger.warn(e.getMessage(), e);
                    }
                }
            };
        } catch (Throwable t) {
            throw new RpcException("Failed to refer redis service. interface: " + type.getName() + ", url: " + url + ", cause: " + t.getMessage(), t);
        }
    }

}
