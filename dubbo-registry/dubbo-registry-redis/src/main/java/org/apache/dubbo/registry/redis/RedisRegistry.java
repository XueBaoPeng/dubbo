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
package org.apache.dubbo.registry.redis;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.URLBuilder;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ExecutorUtil;
import org.apache.dubbo.common.utils.NamedThreadFactory;
import org.apache.dubbo.common.utils.UrlUtils;
import org.apache.dubbo.registry.NotifyListener;
import org.apache.dubbo.registry.support.FailbackRegistry;
import org.apache.dubbo.remoting.redis.RedisClient;
import org.apache.dubbo.remoting.redis.jedis.ClusterRedisClient;
import org.apache.dubbo.remoting.redis.jedis.MonoRedisClient;
import org.apache.dubbo.remoting.redis.jedis.SentinelRedisClient;
import org.apache.dubbo.rpc.RpcException;

import redis.clients.jedis.JedisPubSub;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.apache.dubbo.common.constants.CommonConstants.ANYHOST_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.ANY_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.CLUSTER_REDIS;
import static org.apache.dubbo.common.constants.CommonConstants.MONO_REDIS;
import static org.apache.dubbo.common.constants.CommonConstants.GROUP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PATH_SEPARATOR;
import static org.apache.dubbo.common.constants.CommonConstants.REDIS_CLIENT_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.SENTINEL_REDIS;
import static org.apache.dubbo.common.constants.RegistryConstants.CATEGORY_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.DEFAULT_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.DYNAMIC_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.EMPTY_PROTOCOL;
import static org.apache.dubbo.registry.Constants.DEFAULT_REGISTRY_RECONNECT_PERIOD;
import static org.apache.dubbo.registry.Constants.DEFAULT_SESSION_TIMEOUT;
import static org.apache.dubbo.registry.Constants.REGISTER;
import static org.apache.dubbo.registry.Constants.REGISTRY_RECONNECT_PERIOD_KEY;
import static org.apache.dubbo.registry.Constants.SESSION_TIMEOUT_KEY;
import static org.apache.dubbo.registry.Constants.UNREGISTER;

/**
 * RedisRegistry
 */
//该类继承了FailbackRegistry类，该类就是针对注册中心核心的功能注册、订阅、取消注册、取消订阅，查询注册列表进行展开，基于redis来实现
public class RedisRegistry extends FailbackRegistry {

    // 默认 Redis 根节点，涉及到的是dubbo的分组配置
    private static final String DEFAULT_ROOT = "dubbo";

    // 任务调度器
    private final ScheduledExecutorService expireExecutor = Executors.newScheduledThreadPool(1, new NamedThreadFactory("DubboRegistryExpireTimer", true));

    //Redis Key 过期机制执行器
    private final ScheduledFuture<?> expireFuture;

    // Redis 根节点
    private final String root;

    private RedisClient redisClient;

    // 通知器集合，key为 Root + Service的形式
    // 例如 /dubbo/com.alibaba.dubbo.demo.DemoService
    private final ConcurrentMap<String, Notifier> notifiers = new ConcurrentHashMap<>();

    // 重连时间间隔，单位：ms
    private final int reconnectPeriod;

    // 过期周期，单位：ms
    private final int expirePeriod;

    // 是否通过监控中心，用于判断脏数据，脏数据由监控中心删除
    private volatile boolean admin = false;

    public RedisRegistry(URL url) {
        super(url);
        String type = url.getParameter(REDIS_CLIENT_KEY, MONO_REDIS);
        if (SENTINEL_REDIS.equals(type)) {
            redisClient = new SentinelRedisClient(url);
        } else if (CLUSTER_REDIS.equals(type)) {
            redisClient = new ClusterRedisClient(url);
        } else {
            redisClient = new MonoRedisClient(url);
        }
        // 判断地址是否为空
        if (url.isAnyHost()) {
            throw new IllegalStateException("registry address == null");
        }
        // 设置url携带的连接超时时间，如果没有配置，则设置默认为3s
        this.reconnectPeriod = url.getParameter(REGISTRY_RECONNECT_PERIOD_KEY, DEFAULT_REGISTRY_RECONNECT_PERIOD);
        // 获取url中的分组配置，如果没有配置，则默认为dubbo
        String group = url.getParameter(GROUP_KEY, DEFAULT_ROOT);
        if (!group.startsWith(PATH_SEPARATOR)) {
            group = PATH_SEPARATOR + group;
        }
        if (!group.endsWith(PATH_SEPARATOR)) {
            group = group + PATH_SEPARATOR;
        }
        // 设置redis 的根节点
        this.root = group;

        // 获取过期周期配置，如果没有，则默认为60s
        this.expirePeriod = url.getParameter(SESSION_TIMEOUT_KEY, DEFAULT_SESSION_TIMEOUT);
        // 创建过期机制执行器
        this.expireFuture = expireExecutor.scheduleWithFixedDelay(() -> {
            try {
                // 延长到期时间
                deferExpired(); // Extend the expiration time
            } catch (Throwable t) { // Defensive fault tolerance
                logger.error("Unexpected exception occur at defer expire time, cause: " + t.getMessage(), t);
            }
        }, expirePeriod / 2, expirePeriod / 2, TimeUnit.MILLISECONDS);
    }

    private void deferExpired() {
        // 遍历已经注册的服务url集合
        for (URL url : new HashSet<>(getRegistered())) {
            // 如果是非动态管理模式
            if (url.getParameter(DYNAMIC_KEY, true)) {
                // 获得分类路径
                String key = toCategoryPath(url);
                // 以hash 散列表的形式存储
                if (redisClient.hset(key, url.toFullString(), String.valueOf(System.currentTimeMillis() + expirePeriod)) == 1) {
                    // 发布 Redis 注册事件
                    redisClient.publish(key, REGISTER);
                }
            }
        }
        // 如果通过监控中心
        if (admin) {
            // 删除过时的脏数据
            clean();
        }
    }

    private void clean() {
        // 获得所有的服务
        Set<String> keys = redisClient.scan(root + ANY_VALUE);
        if (CollectionUtils.isNotEmpty(keys)) {
            // 遍历所有的服务
            for (String key : keys) {
                // 返回hash表key对应的所有域和值
                // redis的key为服务名称和服务的类型。map中的key为URL地址，map中的value为过期时间，用于判断脏数据，脏数据由监控中心删除
                Map<String, String> values = redisClient.hgetAll(key);
                if (CollectionUtils.isNotEmptyMap(values)) {
                    boolean delete = false;
                    long now = System.currentTimeMillis();
                    for (Map.Entry<String, String> entry : values.entrySet()) {
                        URL url = URL.valueOf(entry.getKey());
                        // 是否为动态节点
                        if (url.getParameter(DYNAMIC_KEY, true)) {
                            long expire = Long.parseLong(entry.getValue());
                            // 判断是否过期
                            if (expire < now) {
                                // 删除记录
                                redisClient.hdel(key, entry.getKey());
                                delete = true;
                                if (logger.isWarnEnabled()) {
                                    logger.warn("Delete expired key: " + key + " -> value: " + entry.getKey() + ", expire: " + new Date(expire) + ", now: " + new Date(now));
                                }
                            }
                        }
                    }
                    // 取消注册
                    if (delete) {
                        redisClient.publish(key, UNREGISTER);
                    }
                }
            }
        }
    }

    @Override
    public boolean isAvailable() {
        return redisClient.isConnected();
    }

    @Override
    public void destroy() {
        super.destroy();
        try {
            // 关闭过期执行器
            expireFuture.cancel(true);
        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
        }
        try {
            // 关闭通知器
            for (Notifier notifier : notifiers.values()) {
                notifier.shutdown();
            }
        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
        }
        try {
            // 销毁连接池
            redisClient.destroy();
        } catch (Throwable t) {
            logger.warn("Failed to destroy the redis registry client. registry: " + getUrl().getAddress() + ", cause: " + t.getMessage(), t);
        }
        // 关闭任务调度器
        ExecutorUtil.gracefulShutdown(expireExecutor, expirePeriod);
    }

    @Override
    public void doRegister(URL url) {
        // 获得分类路径
        String key = toCategoryPath(url);
        // 获得URL字符串作为 Value
        String value = url.toFullString();
        // 计算过期时间
        String expire = String.valueOf(System.currentTimeMillis() + expirePeriod);
        try {

            // 写入 Redis Map 键
            redisClient.hset(key, value, expire);
            // 发布 Redis 注册事件
            // 这样订阅该 Key 的服务消费者和监控中心，就会实时从 Redis 读取该服务的最新数据。
            redisClient.publish(key, REGISTER);
        } catch (Throwable t) {
            throw new RpcException("Failed to register service to redis registry. registry: " + url.getAddress() + ", service: " + url + ", cause: " + t.getMessage(), t);
        }
    }

    @Override
    public void doUnregister(URL url) {
        // 获得分类路径
        String key = toCategoryPath(url);
        // 获得URL字符串作为 Value
        String value = url.toFullString();
        try {
            // 删除redis中的记录
            redisClient.hdel(key, value);
            // 发布redis取消注册事件
            redisClient.publish(key, UNREGISTER);
        } catch (Throwable t) {
            throw new RpcException("Failed to unregister service to redis registry. registry: " + url.getAddress() + ", service: " + url + ", cause: " + t.getMessage(), t);
        }
    }

    @Override
    public void doSubscribe(final URL url, final NotifyListener listener) {
        // 返回服务地址
        String service = toServicePath(url);
        // 获得通知器
        Notifier notifier = notifiers.get(service);
        // 如果没有该服务的通知器，则创建一个
        if (notifier == null) {
            Notifier newNotifier = new Notifier(service);
            notifiers.putIfAbsent(service, newNotifier);
            notifier = notifiers.get(service);
            // 保证并发情况下，有且只有一个通知器启动
            if (notifier == newNotifier) {
                notifier.start();
            }
        }
        try {
            // 如果服务地址为*结尾，也就是处理所有的服务层发起的订阅
            if (service.endsWith(ANY_VALUE)) {
                admin = true;
                // 获得分类层的集合 例如：/dubbo/com.alibaba.dubbo.demo.DemoService/providers
                Set<String> keys = redisClient.scan(service);
                if (CollectionUtils.isNotEmpty(keys)) {
                    // 按照服务聚合url
                    Map<String, Set<String>> serviceKeys = new HashMap<>();
                    for (String key : keys) {
                        // 获得服务路径，截掉多余部分
                        String serviceKey = toServicePath(key);
                        Set<String> sk = serviceKeys.computeIfAbsent(serviceKey, k -> new HashSet<>());
                        sk.add(key);
                    }
                    // 按照每个服务层进行发起通知，因为服务地址为*结尾
                    for (Set<String> sk : serviceKeys.values()) {
                        doNotify(sk, url, Collections.singletonList(listener));
                    }
                }
            } else {
                // 处理指定的服务层发起的通知
                doNotify(redisClient.scan(service + PATH_SEPARATOR + ANY_VALUE), url, Collections.singletonList(listener));
            }
        } catch (Throwable t) {
            throw new RpcException("Failed to subscribe service from redis registry. registry: " + url.getAddress() + ", service: " + url + ", cause: " + t.getMessage(), t);
        }
    }

    @Override
    public void doUnsubscribe(URL url, NotifyListener listener) {
    }

    private void doNotify(String key) {
        // 遍历所有的通知器，调用重载方法今天通知
        for (Map.Entry<URL, Set<NotifyListener>> entry : new HashMap<>(getSubscribed()).entrySet()) {
            doNotify(Collections.singletonList(key), entry.getKey(), new HashSet<>(entry.getValue()));
        }
    }

    private void doNotify(Collection<String> keys, URL url, Collection<NotifyListener> listeners) {
        if (keys == null || keys.isEmpty()
                || listeners == null || listeners.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        List<URL> result = new ArrayList<>();
        // 获得分类集合
        List<String> categories = Arrays.asList(url.getParameter(CATEGORY_KEY, new String[0]));
        // 通过url获得服务接口
        String consumerService = url.getServiceInterface();
        // 遍历分类路径，例如/dubbo/com.alibaba.dubbo.demo.DemoService/providers
        for (String key : keys) {
            if (!ANY_VALUE.equals(consumerService)) {
                String providerService = toServiceName(key);
                if (!providerService.equals(consumerService)) {
                    continue;
                }
            }
            // 从分类路径上获得分类名
            String category = toCategoryName(key);
            // 判断订阅的分类是否包含该分类
            if (!categories.contains(ANY_VALUE) && !categories.contains(category)) {
                continue;
            }
            List<URL> urls = new ArrayList<>();
            // 返回所有的URL集合
            Map<String, String> values = redisClient.hgetAll(key);
            if (CollectionUtils.isNotEmptyMap(values)) {
                for (Map.Entry<String, String> entry : values.entrySet()) {
                    URL u = URL.valueOf(entry.getKey());
                    // 判断是否为动态节点，因为动态节点不受过期限制。并且判断是否过期
                    if (!u.getParameter(DYNAMIC_KEY, true)
                            || Long.parseLong(entry.getValue()) >= now) {
                        // 判断url是否合法
                        if (UrlUtils.isMatch(url, u)) {
                            urls.add(u);
                        }
                    }
                }
            }
            // 若不存在匹配的url，则创建 `empty://` 的 URL返回，用于清空该服务的该分类。
            if (urls.isEmpty()) {
                urls.add(URLBuilder.from(url)
                        .setProtocol(EMPTY_PROTOCOL)
                        .setAddress(ANYHOST_VALUE)
                        .setPath(toServiceName(key))
                        .addParameter(CATEGORY_KEY, category)
                        .build());
            }
            result.addAll(urls);
            if (logger.isInfoEnabled()) {
                logger.info("redis notify: " + key + " = " + urls);
            }
        }
        if (CollectionUtils.isEmpty(result)) {
            return;
        }
        // 全部数据完成后，调用通知方法，来通知监听器
        for (NotifyListener listener : listeners) {
            notify(url, listener, result);
        }
    }

    //该方法很简单，就是从服务路径上获得服务名
    private String toServiceName(String categoryPath) {
        String servicePath = toServicePath(categoryPath);
        return servicePath.startsWith(root) ? servicePath.substring(root.length()) : servicePath;
    }

    //从分类路径上获得分类名
    private String toCategoryName(String categoryPath) {
        int i = categoryPath.lastIndexOf(PATH_SEPARATOR);
        return i > 0 ? categoryPath.substring(i + 1) : categoryPath;
    }

    private String toServicePath(String categoryPath) {
        int i;
        if (categoryPath.startsWith(root)) {
            i = categoryPath.indexOf(PATH_SEPARATOR, root.length());
        } else {
            i = categoryPath.indexOf(PATH_SEPARATOR);
        }
        return i > 0 ? categoryPath.substring(0, i) : categoryPath;
    }

    private String toServicePath(URL url) {
        return root + url.getServiceInterface();
    }

    private String toCategoryPath(URL url) {
        return toServicePath(url) + PATH_SEPARATOR + url.getParameter(CATEGORY_KEY, DEFAULT_CATEGORY);
    }

    private class NotifySub extends JedisPubSub {
        public NotifySub() {}

        @Override
        public void onMessage(String key, String msg) {
            if (logger.isInfoEnabled()) {
                logger.info("redis event: " + key + " = " + msg);
            }
            // 如果是注册事件或者取消注册事件
            if (msg.equals(REGISTER)
                    || msg.equals(UNREGISTER)) {
                try {
                    // 通知监听器
                    doNotify(key);
                } catch (Throwable t) { // TODO Notification failure does not restore mechanism guarantee
                    logger.error(t.getMessage(), t);
                }
            }
        }

        @Override
        public void onPMessage(String pattern, String key, String msg) {
            onMessage(key, msg);
        }

        @Override
        public void onSubscribe(String key, int num) {
        }

        @Override
        public void onPSubscribe(String pattern, int num) {
        }

        @Override
        public void onUnsubscribe(String key, int num) {
        }

        @Override
        public void onPUnsubscribe(String pattern, int num) {
        }

    }

    //该类继承 Thread 类，负责向 Redis 发起订阅逻辑。
    private class Notifier extends Thread {

        // 服务名：Root + Service
        private final String service;
        // 需要忽略连接的次数
        private final AtomicInteger connectSkip = new AtomicInteger();
        // 已经忽略连接的次数
        private final AtomicInteger connectSkipped = new AtomicInteger();

        // 是否是首次通知
        private volatile boolean first = true;
        // 是否运行中
        private volatile boolean running = true;
        // 连接次数随机数
        private volatile int connectRandom;

        //上述属性中，部分属性都是为了redis的重连策略，用于在和redis断开链接时，忽略一定的次数和redis的连接，避免空跑。
        public Notifier(String service) {
            super.setDaemon(true);
            super.setName("DubboRedisSubscribe");
            this.service = service;
        }

        //该方法就是重置忽略连接的信息。
        private void resetSkip() {
            connectSkip.set(0);
            connectSkipped.set(0);
            connectRandom = 0;
        }

        private boolean isSkip() {
            // 获得忽略次数
            int skip = connectSkip.get(); // Growth of skipping times
            // 如果忽略次数超过10次，那么取随机数，加上一个10以内的随机数
            // 连接失败的次数越多，每一轮加大需要忽略的总次数，并且带有一定的随机性。
            if (skip >= 10) { // If the number of skipping times increases by more than 10, take the random number
                if (connectRandom == 0) {
                    connectRandom = ThreadLocalRandom.current().nextInt(10);
                }
                skip = 10 + connectRandom;
            }
            // 自增忽略次数。若忽略次数不够，则继续忽略。
            if (connectSkipped.getAndIncrement() < skip) { // Check the number of skipping times
                return true;
            }
            // 增加需要忽略的次数
            connectSkip.incrementAndGet();
            // 重置已忽略次数和随机数
            connectSkipped.set(0);
            connectRandom = 0;
            return false;
        }

        @Override
        public void run() {
            // 当通知器正在运行中时
            while (running) {
                try {
                    // 如果不忽略连接
                    if (!isSkip()) {
                        try {
                            if (!redisClient.isConnected()) {
                                continue;
                            }
                            try {
                                // 是否为监控中心
                                if (service.endsWith(ANY_VALUE)) {
                                    // 如果不是第一次通知
                                    if (first) {
                                        first = false;
                                        Set<String> keys = redisClient.scan(service);
                                        if (CollectionUtils.isNotEmpty(keys)) {
                                            for (String s : keys) {
                                                // 通知
                                                doNotify(s);
                                            }
                                        }
                                        // 重置
                                        resetSkip();
                                    }
                                    // 批准订阅
                                    redisClient.psubscribe(new NotifySub(), service);
                                } else {
                                    // 如果不是监控中心，并且不是第一次通知
                                    if (first) {
                                        first = false;
                                        // 单独通知一个服务
                                        doNotify(service);
                                        // 重置
                                        resetSkip();
                                    }
                                    // 批准订阅
                                    redisClient.psubscribe(new NotifySub(), service + PATH_SEPARATOR + ANY_VALUE); // blocking
                                }
                            } catch (Throwable t) { // Retry another server
                                logger.warn("Failed to subscribe service from redis registry. registry: " + getUrl().getAddress() + ", cause: " + t.getMessage(), t);
                                // If you only have a single redis, you need to take a rest to avoid overtaking a lot of CPU resources
                                // 发生异常，说明 Redis 连接断开了，需要等待reconnectPeriod时间
                                //通过这样的方式，避免执行，占用大量的 CPU 资源。
                                sleep(reconnectPeriod);
                            }
                        } catch (Throwable t) {
                            logger.error(t.getMessage(), t);
                            sleep(reconnectPeriod);
                        }
                    }
                } catch (Throwable t) {
                    logger.error(t.getMessage(), t);
                }
            }
        }

        public void shutdown() {
            try {
                // 更改状态
                running = false;
                // jedis断开连接
                redisClient.disconnect();
            } catch (Throwable t) {
                logger.warn(t.getMessage(), t);
            }
        }

    }

}
