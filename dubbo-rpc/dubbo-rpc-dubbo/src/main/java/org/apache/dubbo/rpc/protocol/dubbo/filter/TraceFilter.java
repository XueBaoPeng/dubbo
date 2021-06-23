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
package org.apache.dubbo.rpc.protocol.dubbo.filter;

import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConcurrentHashSet;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.Constants;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;

import com.alibaba.fastjson.JSON;

import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TraceFilter
 * 该过滤器是增强的功能是通道的跟踪，会在通道内把最大的调用次数和现在的调用数量放进去。方便使用telnet来跟踪服务的调用次数等。
 */
@Activate(group = CommonConstants.PROVIDER)
public class TraceFilter implements Filter {

    private static final Logger logger = LoggerFactory.getLogger(TraceFilter.class);
    /**
     * 跟踪数量的最大值key
     */
    private static final String TRACE_MAX = "trace.max";
    /**
     * 跟踪的数量
     */
    private static final String TRACE_COUNT = "trace.count";
    /**
     * 通道集合
     */
    private static final ConcurrentMap<String, Set<Channel>> TRACERS = new ConcurrentHashMap<>();

    /**
     * 该方法是对某一个通道进行跟踪，把现在的调用数量放到属性里面
     * @param type
     * @param method
     * @param channel
     * @param max
     */
    public static void addTracer(Class<?> type, String method, Channel channel, int max) {
        // 设置最大的数量
        channel.setAttribute(TRACE_MAX, max);
        // 设置当前的数量
        channel.setAttribute(TRACE_COUNT, new AtomicInteger());
        // 获得key
        String key = method != null && method.length() > 0 ? type.getName() + "." + method : type.getName();
        // 获得通道集合
        // 如果为空，则新建
        Set<Channel> channels = TRACERS.computeIfAbsent(key, k -> new ConcurrentHashSet<>());
        channels.add(channel);
    }

    public static void removeTracer(Class<?> type, String method, Channel channel) {
        // 移除最大值属性
        channel.removeAttribute(TRACE_MAX);
        // 移除数量属性
        channel.removeAttribute(TRACE_COUNT);
        String key = method != null && method.length() > 0 ? type.getName() + "." + method : type.getName();
        Set<Channel> channels = TRACERS.get(key);
        if (channels != null) {
            // 集合中移除该通道
            channels.remove(channel);
        }
    }

    /**
     * 该方法是当服务被调用时，进行跟踪或者取消跟踪的处理逻辑，是核心的功能增强逻辑。
     * @param invoker
     * @param invocation
     * @return
     * @throws RpcException
     */
    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        // 开始时间
        long start = System.currentTimeMillis();
        // 调用下一个调用链 获得结果
        Result result = invoker.invoke(invocation);
        // 调用结束时间
        long end = System.currentTimeMillis();
        // 如果通道跟踪大小大于0
        if (TRACERS.size() > 0) {
            // 服务key
            String key = invoker.getInterface().getName() + "." + invocation.getMethodName();
            // 获得通道集合
            Set<Channel> channels = TRACERS.get(key);
            if (channels == null || channels.isEmpty()) {
                key = invoker.getInterface().getName();
                channels = TRACERS.get(key);
            }
            if (CollectionUtils.isNotEmpty(channels)) {
                // 遍历通道集合
                for (Channel channel : new ArrayList<>(channels)) {
                    // 如果通道是连接的
                    if (channel.isConnected()) {
                        try {
                            // 获得跟踪的最大数
                            int max = 1;
                            Integer m = (Integer) channel.getAttribute(TRACE_MAX);
                            if (m != null) {
                                max = m;
                            }
                            // 获得跟踪数量
                            int count = 0;
                            AtomicInteger c = (AtomicInteger) channel.getAttribute(TRACE_COUNT);
                            if (c == null) {
                                c = new AtomicInteger();
                                channel.setAttribute(TRACE_COUNT, c);
                            }
                            count = c.getAndIncrement();
                            // 如果数量小于最大数量则发送
                            if (count < max) {
                                String prompt = channel.getUrl().getParameter(Constants.PROMPT_KEY, Constants.DEFAULT_PROMPT);
                                channel.send("\r\n" + RpcContext.getContext().getRemoteAddress() + " -> "
                                        + invoker.getInterface().getName()
                                        + "." + invocation.getMethodName()
                                        + "(" + JSON.toJSONString(invocation.getArguments()) + ")" + " -> " + JSON.toJSONString(result.getValue())
                                        + "\r\nelapsed: " + (end - start) + " ms."
                                        + "\r\n\r\n" + prompt);
                            }
                            // 如果数量大于等于max - 1，则移除该通道
                            if (count >= max - 1) {
                                channels.remove(channel);
                            }
                        } catch (Throwable e) {
                            channels.remove(channel);
                            logger.warn(e.getMessage(), e);
                        }
                    } else {
                        // 如果未连接，也移除该通道
                        channels.remove(channel);
                    }
                }
            }
        }
        return result;
    }

}
