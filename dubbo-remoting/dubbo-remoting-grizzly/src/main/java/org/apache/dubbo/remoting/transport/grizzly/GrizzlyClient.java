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
package org.apache.dubbo.remoting.transport.grizzly;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.transport.AbstractClient;

import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.filterchain.FilterChainBuilder;
import org.glassfish.grizzly.filterchain.TransportFilter;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;
import org.glassfish.grizzly.nio.transport.TCPNIOTransportBuilder;
import org.glassfish.grizzly.strategies.SameThreadIOStrategy;
import org.glassfish.grizzly.threadpool.ThreadPoolConfig;

import java.util.concurrent.TimeUnit;

import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_TIMEOUT;
import static org.apache.dubbo.common.constants.CommonConstants.TIMEOUT_KEY;

/**
 * GrizzlyClient
 *
 *该类是Grizzly的客户端实现类，继承了AbstractClient类
 */
public class GrizzlyClient extends AbstractClient {

    private static final Logger logger = LoggerFactory.getLogger(GrizzlyClient.class);

    //Grizzly中的传输对象
    private TCPNIOTransport transport;

    //连接实例
    private volatile Connection<?> connection; // volatile, please copy reference to use

    public GrizzlyClient(URL url, ChannelHandler handler) throws RemotingException {
        super(url, handler);
    }

    @Override
    protected void doOpen() throws Throwable {
        // 做一些过滤，用于处理消息
        FilterChainBuilder filterChainBuilder = FilterChainBuilder.stateless();
        filterChainBuilder.add(new TransportFilter());
        filterChainBuilder.add(new GrizzlyCodecAdapter(getCodec(), getUrl(), this));
        filterChainBuilder.add(new GrizzlyHandler(getUrl(), this));
        // 传输构建者
        TCPNIOTransportBuilder builder = TCPNIOTransportBuilder.newInstance();
        // 获得线程池配置实例
        ThreadPoolConfig config = builder.getWorkerThreadPoolConfig();
        // 设置线程池的配置，包括核心线程数等
        config.setPoolName(CLIENT_THREAD_POOL_NAME)
                .setQueueLimit(-1)
                .setCorePoolSize(0)
                .setMaxPoolSize(Integer.MAX_VALUE)
                .setKeepAliveTime(60L, TimeUnit.SECONDS);
        // 设置创建属性
        builder.setTcpNoDelay(true).setKeepAlive(true)
                .setConnectionTimeout(getConnectTimeout())
                .setIOStrategy(SameThreadIOStrategy.getInstance());
        // 创建一个transport
        transport = builder.build();
        transport.setProcessor(filterChainBuilder.build());
        // 创建客户端
        transport.start();
    }


    @Override
    protected void doConnect() throws Throwable {
        connection = transport.connect(getConnectAddress())
                .get(getUrl().getPositiveParameter(TIMEOUT_KEY, DEFAULT_TIMEOUT), TimeUnit.MILLISECONDS);
    }

    @Override
    protected void doDisConnect() throws Throwable {
        try {
            GrizzlyChannel.removeChannelIfDisconnected(connection);
        } catch (Throwable t) {
            logger.warn(t.getMessage());
        }
    }

    @Override
    protected void doClose() throws Throwable {
        try {
            transport.stop();
        } catch (Throwable e) {
            logger.warn(e.getMessage(), e);
        }
    }

    @Override
    protected Channel getChannel() {
        Connection<?> c = connection;
        if (c == null || !c.isOpen()) {
            return null;
        }
        return GrizzlyChannel.getOrAddChannel(c, getUrl(), this);
    }

}
