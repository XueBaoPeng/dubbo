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
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.transport.AbstractServer;

import org.glassfish.grizzly.filterchain.FilterChainBuilder;
import org.glassfish.grizzly.filterchain.TransportFilter;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;
import org.glassfish.grizzly.nio.transport.TCPNIOTransportBuilder;
import org.glassfish.grizzly.strategies.SameThreadIOStrategy;
import org.glassfish.grizzly.threadpool.ThreadPoolConfig;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_THREADPOOL;
import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_THREADS;
import static org.apache.dubbo.common.constants.CommonConstants.THREADPOOL_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.THREADS_KEY;

/**
 * GrizzlyServer
 * 该类是Grizzly的服务器实现类，继承了AbstractServer类。
 */
public class GrizzlyServer extends AbstractServer {

    private static final Logger logger = LoggerFactory.getLogger(GrizzlyServer.class);

    //连接该服务器的客户端通道集合
    private final Map<String, Channel> channels = new ConcurrentHashMap<String, Channel>(); // <ip:port, channel>

    //传输实例
    private TCPNIOTransport transport;

    public GrizzlyServer(URL url, ChannelHandler handler) throws RemotingException {
        super(url, handler);
    }

    @Override
    protected void doOpen() throws Throwable {
        // 增加过滤器，来处理信息
        FilterChainBuilder filterChainBuilder = FilterChainBuilder.stateless();
        filterChainBuilder.add(new TransportFilter());
        filterChainBuilder.add(new GrizzlyCodecAdapter(getCodec(), getUrl(), this));
        filterChainBuilder.add(new GrizzlyHandler(getUrl(), this));

        TCPNIOTransportBuilder builder = TCPNIOTransportBuilder.newInstance();
        // 获得线程池配置
        ThreadPoolConfig config = ThreadPoolConfig.defaultConfig();
        config.setPoolName(SERVER_THREAD_POOL_NAME).setQueueLimit(-1);
        // 获得url配置中线程池类型
        String threadpool = getUrl().getParameter(THREADPOOL_KEY, DEFAULT_THREADPOOL);
        if (DEFAULT_THREADPOOL.equals(threadpool)) {
            // 优先从url获得线程池的线程数，默认线程数为200
            int threads = getUrl().getPositiveParameter(THREADS_KEY, DEFAULT_THREADS);
            // 设置线程池配置
            config.setCorePoolSize(threads).setMaxPoolSize(threads)
                    .setKeepAliveTime(0L, TimeUnit.SECONDS);
            // 如果是cached类型的线程池
        } else if ("cached".equals(threadpool)) {
            // 设置核心线程数为0、最大线程数为threads
            int threads = getUrl().getPositiveParameter(THREADS_KEY, Integer.MAX_VALUE);
            config.setCorePoolSize(0).setMaxPoolSize(threads)
                    .setKeepAliveTime(60L, TimeUnit.SECONDS);
        } else {
            throw new IllegalArgumentException("Unsupported threadpool type " + threadpool);
        }
        builder.setWorkerThreadPoolConfig(config)
                .setKeepAlive(true)
                .setReuseAddress(false)
                .setIOStrategy(SameThreadIOStrategy.getInstance());
        // 创建transport
        transport = builder.build();
        transport.setProcessor(filterChainBuilder.build());
        transport.bind(getBindAddress());
        transport.start();
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
    public boolean isBound() {
        return !transport.isStopped();
    }

    @Override
    public Collection<Channel> getChannels() {
        return channels.values();
    }

    @Override
    public Channel getChannel(InetSocketAddress remoteAddress) {
        return channels.get(NetUtils.toAddressString(remoteAddress));
    }

    @Override
    public void connected(Channel ch) throws RemotingException {
        channels.put(NetUtils.toAddressString(ch.getRemoteAddress()), ch);
        super.connected(ch);
    }

    @Override
    public void disconnected(Channel ch) throws RemotingException {
        channels.remove(NetUtils.toAddressString(ch.getRemoteAddress()));
        super.disconnected(ch);
    }

}
