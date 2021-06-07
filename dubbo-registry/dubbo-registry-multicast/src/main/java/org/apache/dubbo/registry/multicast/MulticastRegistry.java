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
package org.apache.dubbo.registry.multicast;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConcurrentHashSet;
import org.apache.dubbo.common.utils.ExecutorUtil;
import org.apache.dubbo.common.utils.NamedThreadFactory;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.UrlUtils;
import org.apache.dubbo.registry.NotifyListener;
import org.apache.dubbo.registry.support.FailbackRegistry;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.apache.dubbo.common.constants.CommonConstants.ANY_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_TIMEOUT;
import static org.apache.dubbo.common.constants.CommonConstants.TIMEOUT_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.DYNAMIC_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.EMPTY_PROTOCOL;
import static org.apache.dubbo.common.constants.RegistryConstants.OVERRIDE_PROTOCOL;
import static org.apache.dubbo.common.constants.RegistryConstants.ROUTE_PROTOCOL;
import static org.apache.dubbo.registry.Constants.CONSUMER_PROTOCOL;
import static org.apache.dubbo.registry.Constants.DEFAULT_SESSION_TIMEOUT;
import static org.apache.dubbo.registry.Constants.REGISTER;
import static org.apache.dubbo.registry.Constants.REGISTER_KEY;
import static org.apache.dubbo.registry.Constants.SESSION_TIMEOUT_KEY;
import static org.apache.dubbo.registry.Constants.SUBSCRIBE;
import static org.apache.dubbo.registry.Constants.UNREGISTER;
import static org.apache.dubbo.registry.Constants.UNSUBSCRIBE;

/**
 * MulticastRegistry
 */
public class MulticastRegistry extends FailbackRegistry {

    private static final int DEFAULT_MULTICAST_PORT = 1234;
   // 多点广播的地址
    private final InetAddress multicastAddress;
    // 多点广播
    private final MulticastSocket multicastSocket;

    // 多点广播端口
    private final int multicastPort;

    //收到的URL
    private final ConcurrentMap<URL, Set<URL>> received = new ConcurrentHashMap<URL, Set<URL>>();

    // 任务调度器
    private final ScheduledExecutorService cleanExecutor = Executors.newScheduledThreadPool(1, new NamedThreadFactory("DubboMulticastRegistryCleanTimer", true));

    // 定时清理执行器，一定时间清理过期的url
    private final ScheduledFuture<?> cleanFuture;

    // 清理的间隔时间
    private final int cleanPeriod;

    // 管理员权限
    private volatile boolean admin = false;

    public MulticastRegistry(URL url) {
        super(url);
        if (url.isAnyHost()) {
            throw new IllegalStateException("registry address == null");
        }
        try {
            multicastAddress = InetAddress.getByName(url.getHost());
            checkMulticastAddress(multicastAddress);

            // 如果url携带的配置中没有端口号，则使用默认端口号
            multicastPort = url.getPort() <= 0 ? DEFAULT_MULTICAST_PORT : url.getPort();
            multicastSocket = new MulticastSocket(multicastPort);
            // 加入同一组广播
            NetUtils.joinMulticastGroup(multicastSocket, multicastAddress);
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    byte[] buf = new byte[2048];
                    // 实例化数据报
                    DatagramPacket recv = new DatagramPacket(buf, buf.length);
                    while (!multicastSocket.isClosed()) {
                        try {
                            // 接收数据包
                            multicastSocket.receive(recv);
                            String msg = new String(recv.getData()).trim();
                            int i = msg.indexOf('\n');
                            if (i > 0) {
                                msg = msg.substring(0, i).trim();
                            }
                            // 接收消息请求，根据消息并相应操作，比如注册，订阅等
                            MulticastRegistry.this.receive(msg, (InetSocketAddress) recv.getSocketAddress());
                            Arrays.fill(buf, (byte) 0);
                        } catch (Throwable e) {
                            if (!multicastSocket.isClosed()) {
                                logger.error(e.getMessage(), e);
                            }
                        }
                    }
                }
            }, "DubboMulticastRegistryReceiver");
            // 设置为守护进程
            thread.setDaemon(true);
            // 开启线程
            thread.start();
        } catch (IOException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
        // 优先从url中获取清理延迟配置，若没有，则默认为60s
        this.cleanPeriod = url.getParameter(SESSION_TIMEOUT_KEY, DEFAULT_SESSION_TIMEOUT);
        // 如果配置了需要清理
        if (url.getParameter("clean", true)) {
            // 开启计时器
            this.cleanFuture = cleanExecutor.scheduleWithFixedDelay(new Runnable() {
                @Override
                public void run() {
                    try {
                        // 清理过期的服务
                        clean(); // Remove the expired
                    } catch (Throwable t) { // Defensive fault tolerance
                        logger.error("Unexpected exception occur at clean expired provider, cause: " + t.getMessage(), t);
                    }
                }
            }, cleanPeriod, cleanPeriod, TimeUnit.MILLISECONDS);
        } else {
            this.cleanFuture = null;
        }
    }

    private void checkMulticastAddress(InetAddress multicastAddress) {
        if (!multicastAddress.isMulticastAddress()) {
            String message = "Invalid multicast address " + multicastAddress;
            if (multicastAddress instanceof Inet4Address) {
                throw new IllegalArgumentException(message + ", " +
                        "ipv4 multicast address scope: 224.0.0.0 - 239.255.255.255.");
            } else {
                throw new IllegalArgumentException(message + ", " + "ipv6 multicast address must start with ff, " +
                        "for example: ff01::1");
            }
        }
    }

    /**
     * Remove the expired providers, only when "clean" parameter is true.
     */
    private void clean() {
        // 当url中携带的服务接口配置为是*时候，才可以执行清理
        if (admin) {
            for (Set<URL> providers : new HashSet<Set<URL>>(received.values())) {
                for (URL url : new HashSet<URL>(providers)) {
                    // 判断是否过期
                    if (isExpired(url)) {
                        if (logger.isWarnEnabled()) {
                            logger.warn("Clean expired provider " + url);
                        }
                        //取消注册
                        doUnregister(url);
                    }
                }
            }
        }
    }

    //这个方法就是判断服务是否过期，有两次尝试socket的操作，如果尝试失败，则判断为过期。
    private boolean isExpired(URL url) {
        // 如果为非动态管理模式或者协议是consumer、route或者override，则没有过期
        if (!url.getParameter(DYNAMIC_KEY, true) || url.getPort() <= 0 || CONSUMER_PROTOCOL.equals(url.getProtocol()) || ROUTE_PROTOCOL.equals(url.getProtocol()) || OVERRIDE_PROTOCOL.equals(url.getProtocol())) {
            return false;
        }
        // 利用url携带的主机地址和端口号实例化socket
        try (Socket socket = new Socket(url.getHost(), url.getPort())) {
        } catch (Throwable e) {
            // 如果实例化失败，等待100ms重试第二次，如果还失败，则判定已过期
            try {
                Thread.sleep(100);
            } catch (Throwable e2) {
            }
            try (Socket socket2 = new Socket(url.getHost(), url.getPort())) {
            } catch (Throwable e2) {
                return true;
            }
        }
        return false;
    }

    private void receive(String msg, InetSocketAddress remoteAddress) {
        if (logger.isInfoEnabled()) {
            logger.info("Receive multicast message: " + msg + " from " + remoteAddress);
        }
        // 如果这个消息是以register、unregister、subscribe开头的，则进行相应的操作
        if (msg.startsWith(REGISTER)) {
            URL url = URL.valueOf(msg.substring(REGISTER.length()).trim());
            // 注册服务
            registered(url);
        } else if (msg.startsWith(UNREGISTER)) {
            URL url = URL.valueOf(msg.substring(UNREGISTER.length()).trim());
            // 取消注册服务
            unregistered(url);
        } else if (msg.startsWith(SUBSCRIBE)) {
            URL url = URL.valueOf(msg.substring(SUBSCRIBE.length()).trim());
            // 获得以及注册的url集合
            Set<URL> urls = getRegistered();
            if (CollectionUtils.isNotEmpty(urls)) {
                for (URL u : urls) {
                    // 判断是否合法
                    if (UrlUtils.isMatch(url, u)) {
                        String host = remoteAddress != null && remoteAddress.getAddress() != null ? remoteAddress.getAddress().getHostAddress() : url.getIp();
                        // 建议服务提供者和服务消费者在不同机器上运行，如果在同一机器上，需设置unicast=false
                        // 同一台机器中的多个进程不能单播单播，或者只有一个进程接收信息，发给消费者的单播消息可能被提供者抢占，两个消费者在同一台机器也一样，
                        // 只有multicast注册中心有此问题
                        if (url.getParameter("unicast", true) // Whether the consumer's machine has only one process
                                && !NetUtils.getLocalHost().equals(host)) { // Multiple processes in the same machine cannot be unicast with unicast or there will be only one process receiving information
                            unicast(REGISTER + " " + u.toFullString(), host);
                        } else {
                            multicast(REGISTER + " " + u.toFullString());
                        }
                    }
                }
            }
        }/* else if (msg.startsWith(UNSUBSCRIBE)) {
        }*/
    }

    private void multicast(String msg) {
        if (logger.isInfoEnabled()) {
            logger.info("Send multicast message: " + msg + " to " + multicastAddress + ":" + multicastPort);
        }
        try {
            byte[] data = (msg + "\n").getBytes();
            // 实例化数据报,重点是目的地址是mutilcastAddress
            DatagramPacket hi = new DatagramPacket(data, data.length, multicastAddress, multicastPort);
            // 发送数据报
            multicastSocket.send(hi);
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private void unicast(String msg, String host) {
        if (logger.isInfoEnabled()) {
            logger.info("Send unicast message: " + msg + " to " + host + ":" + multicastPort);
        }
        try {
            byte[] data = (msg + "\n").getBytes();
            // 实例化数据报,重点是目的地址是只是单个地址
            DatagramPacket hi = new DatagramPacket(data, data.length, InetAddress.getByName(host), multicastPort);
            // 发送数据报
            multicastSocket.send(hi);
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    @Override
    public void doRegister(URL url) {
        multicast(REGISTER + " " + url.toFullString());
    }

    @Override
    public void doUnregister(URL url) {
        multicast(UNREGISTER + " " + url.toFullString());
    }

    @Override
    public void doSubscribe(URL url, final NotifyListener listener) {
        // 当url中携带的服务接口配置为是*时候，才可以执行清理，类似管理员权限
        if (ANY_VALUE.equals(url.getServiceInterface())) {
            admin = true;
        }
        multicast(SUBSCRIBE + " " + url.toFullString());
        // 对监听器进行同步锁
        synchronized (listener) {
            try {
                listener.wait(url.getParameter(TIMEOUT_KEY, DEFAULT_TIMEOUT));
            } catch (InterruptedException e) {
            }
        }
    }

    @Override
    public void doUnsubscribe(URL url, NotifyListener listener) {
        if (!ANY_VALUE.equals(url.getServiceInterface()) && url.getParameter(REGISTER_KEY, true)) {
            unregister(url);
        }
        multicast(UNSUBSCRIBE + " " + url.toFullString());
    }

    @Override
    public boolean isAvailable() {
        try {
            return multicastSocket != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Remove the expired providers(if clean is true), leave the multicast group and close the multicast socket.
     */
    @Override
    public void destroy() {
        super.destroy();
        try {
            // 取消清理任务
            ExecutorUtil.cancelScheduledFuture(cleanFuture);
        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
        }
        try {
            // 把该地址从组内移除
            multicastSocket.leaveGroup(multicastAddress);
            // 关闭mutilcastSocket
            multicastSocket.close();
        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
        }
        // 关闭线程池
        ExecutorUtil.gracefulShutdown(cleanExecutor, cleanPeriod);
    }

    protected void registered(URL url) {
        // 遍历订阅的监听器集合
        for (Map.Entry<URL, Set<NotifyListener>> entry : getSubscribed().entrySet()) {
            URL key = entry.getKey();
            // 判断是否合法
            if (UrlUtils.isMatch(key, url)) {
                // 通过消费者url获得接收到的服务url集合
                Set<URL> urls = received.computeIfAbsent(key, k -> new ConcurrentHashSet<>());
                // 加入服务url
                urls.add(url);
                List<URL> list = toList(urls);
                for (final NotifyListener listener : entry.getValue()) {
                    // 把服务url的变化通知监听器
                    notify(key, listener, list);
                    synchronized (listener) {
                        listener.notify();
                    }
                }
            }
        }
    }

    protected void unregistered(URL url) {
        // 遍历订阅的监听器集合
        for (Map.Entry<URL, Set<NotifyListener>> entry : getSubscribed().entrySet()) {
            URL key = entry.getKey();
            if (UrlUtils.isMatch(key, url)) {
                Set<URL> urls = received.get(key);
                // 缓存中移除
                if (urls != null) {
                    urls.remove(url);
                }
                if (CollectionUtils.isEmpty(urls)) {
                    if (urls == null) {
                        urls = new ConcurrentHashSet<URL>();
                    }
                    // 设置携带empty协议的url
                    URL empty = url.setProtocol(EMPTY_PROTOCOL);
                    urls.add(empty);
                }
                List<URL> list = toList(urls);
                // 通知监听器 服务url变化
                for (NotifyListener listener : entry.getValue()) {
                    notify(key, listener, list);
                }
            }
        }
    }

    protected void subscribed(URL url, NotifyListener listener) {
        List<URL> urls = lookup(url);
        notify(url, listener, urls);
    }

    private List<URL> toList(Set<URL> urls) {
        List<URL> list = new ArrayList<URL>();
        if (CollectionUtils.isNotEmpty(urls)) {
            list.addAll(urls);
        }
        return list;
    }

    @Override
    public void register(URL url) {
        super.register(url);
        registered(url);
    }

    @Override
    public void unregister(URL url) {
        super.unregister(url);
        unregistered(url);
    }

    @Override
    public void subscribe(URL url, NotifyListener listener) {
        super.subscribe(url, listener);
        subscribed(url, listener);
    }

    @Override
    public void unsubscribe(URL url, NotifyListener listener) {
        super.unsubscribe(url, listener);
        received.remove(url);
    }

    @Override
    public List<URL> lookup(URL url) {
        List<URL> urls = new ArrayList<>();
        // 通过消费者url获得订阅的服务的监听器
        Map<String, List<URL>> notifiedUrls = getNotified().get(url);
        // 获得注册的服务url集合
        if (notifiedUrls != null && notifiedUrls.size() > 0) {
            for (List<URL> values : notifiedUrls.values()) {
                urls.addAll(values);
            }
        }
        // 如果为空，则从内存缓存properties获得相关value，并且返回为注册的服务
        if (urls.isEmpty()) {
            List<URL> cacheUrls = getCacheUrls(url);
            if (CollectionUtils.isNotEmpty(cacheUrls)) {
                urls.addAll(cacheUrls);
            }
        }
        // 如果还是为空则从缓存registered中获得已注册 服务URL 集合
        if (urls.isEmpty()) {
            for (URL u : getRegistered()) {
                if (UrlUtils.isMatch(url, u)) {
                    urls.add(u);
                }
            }
        }
        // 如果url携带的配置服务接口为*，也就是所有服务，则从缓存subscribed获得已注册 服务URL 集合
        if (ANY_VALUE.equals(url.getServiceInterface())) {
            for (URL u : getSubscribed().keySet()) {
                if (UrlUtils.isMatch(url, u)) {
                    urls.add(u);
                }
            }
        }
        return urls;
    }

    public MulticastSocket getMulticastSocket() {
        return multicastSocket;
    }

    public Map<URL, Set<URL>> getReceived() {
        return received;
    }

}
