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
package org.apache.dubbo.rpc.protocol.dubbo;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.config.ConfigurationUtils;
import org.apache.dubbo.common.utils.AtomicPositiveInteger;
import org.apache.dubbo.remoting.Constants;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.TimeoutException;
import org.apache.dubbo.remoting.exchange.ExchangeClient;
import org.apache.dubbo.rpc.AppResponse;
import org.apache.dubbo.rpc.AsyncRpcResult;
import org.apache.dubbo.rpc.FutureContext;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.TimeoutCountDown;
import org.apache.dubbo.rpc.protocol.AbstractInvoker;
import org.apache.dubbo.rpc.support.RpcUtils;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_TIMEOUT;
import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_VERSION;
import static org.apache.dubbo.common.constants.CommonConstants.ENABLE_TIMEOUT_COUNTDOWN_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.GROUP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.INTERFACE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PATH_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.TIMEOUT_ATTACHMENT_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.TIMEOUT_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.TIME_COUNTDOWN_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.VERSION_KEY;
import static org.apache.dubbo.rpc.Constants.TOKEN_KEY;

/**
 * DubboInvoker
 * 该类是dubbo协议独自实现的的invoker，其中实现了调用方法的三种模式，分别是异步发送、单向发送和同步发送，具体在下面介绍。
 */
public class DubboInvoker<T> extends AbstractInvoker<T> {
    /**
     * 信息交换客户端数组
     */
    private final ExchangeClient[] clients;
    /**
     * 客户端数组位置
     */
    private final AtomicPositiveInteger index = new AtomicPositiveInteger();
    /**
     * 版本号
     */
    private final String version;
    /**
     * 销毁锁
     */
    private final ReentrantLock destroyLock = new ReentrantLock();
    /**
     * Invoker对象集合
     */
    private final Set<Invoker<?>> invokers;

    public DubboInvoker(Class<T> serviceType, URL url, ExchangeClient[] clients) {
        this(serviceType, url, clients, null);
    }

    public DubboInvoker(Class<T> serviceType, URL url, ExchangeClient[] clients, Set<Invoker<?>> invokers) {
        super(serviceType, url, new String[]{INTERFACE_KEY, GROUP_KEY, TOKEN_KEY});
        this.clients = clients;
        // get version.
        this.version = url.getParameter(VERSION_KEY, DEFAULT_VERSION);
        this.invokers = invokers;
    }

    /**
     * 在调用invoker的时候，通过远程通信将Invocation信息传递给服务端，服务端在接收到该invocation信息后，
     * 要找到对应的本地方法，然后通过反射执行该方法，将方法的执行结果返回给客户端，在这里，客户端发送有三种模式：
     * 异步发送，也就是当我发送调用后，我不阻塞等待结果，直接返回，将返回的future保存到上下文，方便后期使用。
     * 单向发送，执行方法不需要返回结果。
     * 同步发送，执行方法后，等待结果返回，否则一直阻塞。
     * @param invocation
     * @return
     * @throws Throwable
     */
    @Override
    protected Result doInvoke(final Invocation invocation) throws Throwable {
        // rpc会话域
        RpcInvocation inv = (RpcInvocation) invocation;
        // 获得方法名
        final String methodName = RpcUtils.getMethodName(invocation);
        // 把path放入到附加值中
        inv.setAttachment(PATH_KEY, getUrl().getPath());
        // 把版本号放入到附加值
        inv.setAttachment(VERSION_KEY, version);

        // 当前的客户端
        ExchangeClient currentClient;
        // 如果数组内就一个客户端，则直接取出
        if (clients.length == 1) {
            currentClient = clients[0];
        } else {
            // 取模轮询 从数组中取，当取到最后一个时，从头开始
            currentClient = clients[index.getAndIncrement() % clients.length];
        }
        try {
            // 是否是单向发送
            boolean isOneway = RpcUtils.isOneway(getUrl(), invocation);
            int timeout = calculateTimeout(invocation, methodName);
            invocation.put(TIMEOUT_KEY, timeout);
            if (isOneway) {
                boolean isSent = getUrl().getMethodParameter(methodName, Constants.SENT_KEY, false);
                // 单向发送只负责发送消息，不等待服务端应答，所以没有返回值
                currentClient.send(inv, isSent);
                return AsyncRpcResult.newDefaultAsyncResult(invocation);
            } else {
                // 异步调用
                ExecutorService executor = getCallbackExecutor(getUrl(), inv);
                CompletableFuture<AppResponse> appResponseFuture =
                        currentClient.request(inv, timeout, executor).thenApply(obj -> (AppResponse) obj);
                // save for 2.6.x compatibility, for example, TraceFilter in Zipkin uses com.alibaba.xxx.FutureAdapter
                // 保存future，方便后期处理
                FutureContext.getContext().setCompatibleFuture(appResponseFuture);
                AsyncRpcResult result = new AsyncRpcResult(appResponseFuture, inv);
                result.setExecutor(executor);
                return result;
            }
        } catch (TimeoutException e) {
            throw new RpcException(RpcException.TIMEOUT_EXCEPTION, "Invoke remote method timeout. method: " + invocation.getMethodName() + ", provider: " + getUrl() + ", cause: " + e.getMessage(), e);
        } catch (RemotingException e) {
            throw new RpcException(RpcException.NETWORK_EXCEPTION, "Failed to invoke remote method: " + invocation.getMethodName() + ", provider: " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    /**
     * 该方法是检查服务端是否存活
     * @return
     */
    @Override
    public boolean isAvailable() {
        if (!super.isAvailable()) {
            return false;
        }
        for (ExchangeClient client : clients) {
            // 只要有一个客户端连接并且不是只读，则表示存活
            if (client.isConnected() && !client.hasAttribute(Constants.CHANNEL_ATTRIBUTE_READONLY_KEY)) {
                //cannot write == not Available ?
                return true;
            }
        }
        return false;
    }

    /**
     * 该方法是销毁服务端，关闭所有连接到远程通信客户端。
     */
    @Override
    public void destroy() {
        // in order to avoid closing a client multiple times, a counter is used in case of connection per jvm, every
        // time when client.close() is called, counter counts down once, and when counter reaches zero, client will be
        // closed.
        if (super.isDestroyed()) {
            return;
        } else {
            // double check to avoid dup close
            // 获得销毁锁
            destroyLock.lock();
            try {
                if (super.isDestroyed()) {
                    return;
                }
                // 销毁
                super.destroy();
                // 从集合中移除
                if (invokers != null) {
                    invokers.remove(this);
                }
                for (ExchangeClient client : clients) {
                    try {
                        // 关闭每一个客户端
                        client.close(ConfigurationUtils.getServerShutdownTimeout());
                    } catch (Throwable t) {
                        logger.warn(t.getMessage(), t);
                    }
                }

            } finally {
                // 释放锁
                destroyLock.unlock();
            }
        }
    }

    private int calculateTimeout(Invocation invocation, String methodName) {
        Object countdown = RpcContext.getContext().get(TIME_COUNTDOWN_KEY);
        int timeout = DEFAULT_TIMEOUT;
        if (countdown == null) {
            timeout = (int) RpcUtils.getTimeout(getUrl(), methodName, RpcContext.getContext(), DEFAULT_TIMEOUT);
            if (getUrl().getParameter(ENABLE_TIMEOUT_COUNTDOWN_KEY, false)) {
                invocation.setObjectAttachment(TIMEOUT_ATTACHMENT_KEY, timeout); // pass timeout to remote server
            }
        } else {
            TimeoutCountDown timeoutCountDown = (TimeoutCountDown) countdown;
            timeout = (int) timeoutCountDown.timeRemaining(TimeUnit.MILLISECONDS);
            invocation.setObjectAttachment(TIMEOUT_ATTACHMENT_KEY, timeout);// pass timeout to remote server
        }
        return timeout;
    }
}
