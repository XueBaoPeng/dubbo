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
package org.apache.dubbo.rpc.cluster.loadbalance;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.io.Bytes;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.support.RpcUtils;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.apache.dubbo.common.constants.CommonConstants.COMMA_SPLIT_PATTERN;

/**
 * ConsistentHashLoadBalance
 * 该类是负载均衡基于 hash 一致性的逻辑实现。一致性哈希算法由麻省理工学院的 Karger 及其合作者于1997年提供出的，
 * 一开始被大量运用于缓存系统的负载均衡。它的工作原理是这样的：首先根据 ip 或其他的信息为缓存节点生成一个 hash，
 * 在dubbo中使用参数进行计算hash。并将这个 hash 投射到 [0, 232 - 1] 的圆环上，当有查询或写入请求时，
 * 则生成一个 hash 值。然后查找第一个大于或等于该 hash 值的缓存节点，并到这个节点中查询或写入缓存项。
 * 如果当前节点挂了，则在下一次查询或写入缓存时，为缓存项查找另一个大于其 hash 值的缓存节点即可
 * 每个缓存节点在圆环上占据一个位置。如果缓存项的 key 的 hash 值小于缓存节点 hash 值，则到该缓存节点中存储或读取缓存项，这里有两个概念不要弄混，
 * 缓存节点就好比dubbo中的服务提供者，会有很多的服务提供者，而缓存项就好比是服务引用的消费者。
 * 比如下面绿色点对应的缓存项也就是服务消费者将会被存储到 cache-2 节点中。由于 cache-3 挂了，
 * 原本应该存到该节点中的缓存项也就是服务消费者最终会存储到 cache-4 节点中，也就是调用cache-4 这个服务提供
 */
public class ConsistentHashLoadBalance extends AbstractLoadBalance {
    public static final String NAME = "consistenthash";

    /**
     * Hash nodes name
     */
    public static final String HASH_NODES = "hash.nodes";

    /**
     * Hash arguments name
     */
    public static final String HASH_ARGUMENTS = "hash.arguments";

    private final ConcurrentMap<String, ConsistentHashSelector<?>> selectors = new ConcurrentHashMap<String, ConsistentHashSelector<?>>();

    /**
     * 该方法也做了一些invokers 列表是不是变动过
     * ，以及创建 ConsistentHashSelector等工作，然后调用selector.select来进行选择。
     * @param invokers
     * @param url
     * @param invocation
     * @param <T>
     * @return
     */
    @SuppressWarnings("unchecked")
    @Override
    protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        // 获得方法名
        String methodName = RpcUtils.getMethodName(invocation);
        // 获得key
        String key = invokers.get(0).getUrl().getServiceKey() + "." + methodName;
        // using the hashcode of list to compute the hash only pay attention to the elements in the list
        // 获取 invokers 原始的 hashcode
        int invokersHashCode = invokers.hashCode();
        // 从一致性 hash 选择器集合中获得一致性 hash 选择器
        ConsistentHashSelector<T> selector = (ConsistentHashSelector<T>) selectors.get(key);
        // 如果等于空或者选择器的hash值不等于原始的值，则新建一个一致性 hash 选择器，并且加入到集合
        if (selector == null || selector.identityHashCode != invokersHashCode) {
            selectors.put(key, new ConsistentHashSelector<T>(invokers, methodName, invokersHashCode));
            selector = (ConsistentHashSelector<T>) selectors.get(key);
        }
        // 选择器选择一个invoker
        return selector.select(invocation);
    }

    /**
     * 该类是内部类，是一致性 hash 选择器，首先看它的属性，利用TreeMap来存储 Invoker 虚拟节点，因为需要提供高效的查询操作。
     * 再看看它的构造方法，执行了一系列的初始化逻辑，比如从配置中获取虚拟节点数以及参与 hash 计算的参数下标，
     * 默认情况下只使用第一个参数进行 hash，并且ConsistentHashLoadBalance 的负载均衡逻辑只受参数值影响，
     * 具有相同参数值的请求将会被分配给同一个服务提供者。还有一个select方法，比较简单，先进行md5运算。然后hash，最后选择出对应的invoker。
     * @param <T>
     */
    private static final class ConsistentHashSelector<T> {
        /**
         * 存储 Invoker 虚拟节点
         */
        private final TreeMap<Long, Invoker<T>> virtualInvokers;
        /**
         * 每个Invoker 对应的虚拟节点数
         */
        private final int replicaNumber;
        /**
         * 原始哈希值
         */
        private final int identityHashCode;
        /**
         * 取值参数位置数组
         */
        private final int[] argumentIndex;

        ConsistentHashSelector(List<Invoker<T>> invokers, String methodName, int identityHashCode) {
            this.virtualInvokers = new TreeMap<Long, Invoker<T>>();
            this.identityHashCode = identityHashCode;
            URL url = invokers.get(0).getUrl();
            // 获取虚拟节点数，默认为160
            this.replicaNumber = url.getMethodParameter(methodName, HASH_NODES, 160);
            // 获取参与 hash 计算的参数下标值，默认对第一个参数进行 hash 运算
            String[] index = COMMA_SPLIT_PATTERN.split(url.getMethodParameter(methodName, HASH_ARGUMENTS, "0"));
            // 创建下标数组
            argumentIndex = new int[index.length];
            // 遍历
            for (int i = 0; i < index.length; i++) {
                // 记录下标
                argumentIndex[i] = Integer.parseInt(index[i]);
            }
            // 遍历invokers
            for (Invoker<T> invoker : invokers) {
                String address = invoker.getUrl().getAddress();
                for (int i = 0; i < replicaNumber / 4; i++) {
                    // 对 address + i 进行 md5 运算，得到一个长度为16的字节数组
                    byte[] digest = Bytes.getMD5(address + i);
                    // // 对 digest 部分字节进行4次 hash 运算，得到四个不同的 long 型正整数
                    for (int h = 0; h < 4; h++) {
                        // h = 0 时，取 digest 中下标为 0 ~ 3 的4个字节进行位运算
                        // h = 1 时，取 digest 中下标为 4 ~ 7 的4个字节进行位运算
                        // h = 2, h = 3 时过程同上
                        long m = hash(digest, h);
                        // 将 hash 到 invoker 的映射关系存储到 virtualInvokers 中，
                        // virtualInvokers 需要提供高效的查询操作，因此选用 TreeMap 作为存储结构
                        virtualInvokers.put(m, invoker);
                    }
                }
            }
        }
        /**
         * 选择一个invoker
         * @param invocation
         * @return
         */
        public Invoker<T> select(Invocation invocation) {
            // 将参数转为 key
            String key = toKey(invocation.getArguments());
            // 对参数 key 进行 md5 运算
            byte[] digest = Bytes.getMD5(key);
            // 取 digest 数组的前四个字节进行 hash 运算，再将 hash 值传给 selectForKey 方法，
            // 寻找合适的 Invoker
            return selectForKey(hash(digest, 0));
        }
        /**
         * 将参数转为 key
         * @param args
         * @return
         */
        private String toKey(Object[] args) {
            StringBuilder buf = new StringBuilder();
            // 遍历参数下标
            for (int i : argumentIndex) {
                if (i >= 0 && i < args.length) {
                    // 拼接参数，生成key
                    buf.append(args[i]);
                }
            }
            return buf.toString();
        }
        /**
         * 通过hash选择invoker
         * @param hash
         * @return
         */
        private Invoker<T> selectForKey(long hash) {
            // 到 TreeMap 中查找第一个节点值大于或等于当前 hash 的 Invoker
            Map.Entry<Long, Invoker<T>> entry = virtualInvokers.ceilingEntry(hash);
            // 如果 hash 大于 Invoker 在圆环上最大的位置，此时 entry = null，
            // 需要将 TreeMap 的头节点赋值给 entry
            if (entry == null) {
                entry = virtualInvokers.firstEntry();
            }
            // 返回选择的invoker
            return entry.getValue();
        }
        /**
         * 计算hash值
         * @param digest
         * @param number
         * @return
         */
        private long hash(byte[] digest, int number) {
            return (((long) (digest[3 + number * 4] & 0xFF) << 24)
                    | ((long) (digest[2 + number * 4] & 0xFF) << 16)
                    | ((long) (digest[1 + number * 4] & 0xFF) << 8)
                    | (digest[number * 4] & 0xFF))
                    & 0xFFFFFFFFL;
        }
    }

}
