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
package org.apache.dubbo.rpc.cluster;

import org.apache.dubbo.common.extension.Adaptive;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.extension.SPI;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.RpcException;

/**
 * Cluster. (SPI, Singleton, ThreadSafe)
 * <p>
 * <a href="http://en.wikipedia.org/wiki/Computer_cluster">Cluster</a>
 * <a href="http://en.wikipedia.org/wiki/Fault-tolerant_system">Fault-Tolerant</a>
 * 该接口是集群容错接口，可以看到它是一个可扩展接口，默认实现FailoverCluster，当然它还会有其他的实现，每一种实现都代表了一种集群容错的方式，具体有哪些，
 * 可以看下面文章的介绍，他们都在support包下面，在本文只是让读者知道接口的定义。那么它还定义了一个join方法，作用就是把Directory对象变成一个
 * Invoker 对象用来后续的一系列调用。该Invoker代表了一个集群实现。似懂非懂就够了，后面看具体的实现会比较清晰
 *集群工作过程可分为两个阶段，第一个阶段是在服务消费者初始化期间，集群 Cluster 实现类为服务消费者创建
 * Cluster Invoker 实例，即上图中的 merge 操作。第二个阶段是在服务消费者进行远程调用时。以 FailoverClusterInvoker 为例，
 * 该类型 Cluster Invoker 首先会调用 Directory 的 list 方法列举 Invoker 列表（可将 Invoker 简单理解为服务提供者）
 * 。Directory 的用途是保存 Invoker，可简单类比为 List<invoker>。其实现类 RegistryDirectory 是一个动态服务目录，可感知注册中心配置的变化，
 * 它所持有的 Inovker 列表会随着注册中心内容的变化而变化。每次变化后，RegistryDirectory 会动态增删 Inovker，
 * 并调用 Router 的 route 方法进行路由，过滤掉不符合路由规则的 Invoker。当 FailoverClusterInvoker 拿到 Directory 返回的 Invoker 列表后，
 * 它会通过 LoadBalance 从 Invoker 列表中选择一个 Inovker。最后 FailoverClusterInvoker 会将参数传给 LoadBalance 选择出的 Invoker
 * 实例的 invoker 方法，进行真正的远程调用。
 */
@SPI(Cluster.DEFAULT)
public interface Cluster {

    String DEFAULT = "failover";

    /**
     * Merge the directory invokers to a virtual invoker.
     * 将目录调用程序合并到虚拟调用程序。
     * @param <T>
     * @param directory
     * @return cluster invoker
     * @throws RpcException
     */
    @Adaptive
    <T> Invoker<T> join(Directory<T> directory) throws RpcException;

    static Cluster getCluster(String name) {
        return getCluster(name, true);
    }

    static Cluster getCluster(String name, boolean wrap) {
        if (StringUtils.isEmpty(name)) {
            name = Cluster.DEFAULT;
        }
        return ExtensionLoader.getExtensionLoader(Cluster.class).getExtension(name, wrap);
    }
}
