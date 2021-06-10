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
package org.apache.dubbo.remoting.transport.dispatcher.execution;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.threadpool.ThreadlessExecutor;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.ExecutionException;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.exchange.Request;
import org.apache.dubbo.remoting.transport.dispatcher.ChannelEventRunnable;
import org.apache.dubbo.remoting.transport.dispatcher.ChannelEventRunnable.ChannelState;
import org.apache.dubbo.remoting.transport.dispatcher.WrappedChannelHandler;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * Only request message will be dispatched to thread pool. Other messages like response, connect, disconnect,
 * heartbeat will be directly executed by I/O thread.
 */
//该类继承了WrappedChannelHandler，也是增强了功能，处理的是接收请求消息时，把请求消息分发到线程池，而除了请求消息以外，其他消息类型都直接通过I / O线程直接执行。
public class ExecutionChannelHandler extends WrappedChannelHandler {

    public ExecutionChannelHandler(ChannelHandler handler, URL url) {
        super(handler, url);
    }

    @Override
    public void received(Channel channel, Object message) throws RemotingException {
        // 获得线程池实例
        ExecutorService executor = getPreferredExecutorService(message);
        // 如果消息是request类型，才会分发到线程池，其他消息，如响应，连接，断开连接，心跳将由I / O线程直接执行。
        if (message instanceof Request) {
            try {
                // 把请求消息分发到线程池
                executor.execute(new ChannelEventRunnable(channel, handler, ChannelState.RECEIVED, message));
            } catch (Throwable t) {
                // FIXME: when the thread pool is full, SERVER_THREADPOOL_EXHAUSTED_ERROR cannot return properly,
                // therefore the consumer side has to wait until gets timeout. This is a temporary solution to prevent
                // this scenario from happening, but a better solution should be considered later.
                // 当线程池满了，SERVER_THREADPOOL_EXHAUSTED_ERROR错误无法正常返回
                // 因此消费者方必须等到超时。这是一种预防的临时解决方案，所以这里直接返回该错误
                if (t instanceof RejectedExecutionException) {
                    sendFeedback(channel, (Request) message, t);
                }
                throw new ExecutionException(message, channel, getClass() + " error when process received event.", t);
            }
        } else if (executor instanceof ThreadlessExecutor) {
            executor.execute(new ChannelEventRunnable(channel, handler, ChannelState.RECEIVED, message));
        } else {
            // 如果消息不是request类型，则直接处理
            handler.received(channel, message);
        }
    }
}
