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
package org.apache.dubbo.remoting.zookeeper;

import org.apache.dubbo.common.URL;

import java.util.List;
import java.util.concurrent.Executor;

public interface ZookeeperClient {
    /**
     * 创建client
     * @param path
     * @param ephemeral
     */
    void create(String path, boolean ephemeral);
    /**
     * 删除client
     * @param path
     */
    void delete(String path);
    /**
     * 获得子节点集合
     * @param path
     * @return
     */
    List<String> getChildren(String path);
    /**
     * 向zookeeper的该节点发起订阅，获得该节点所有
     * @param path
     * @param listener
     * @return
     */
    List<String> addChildListener(String path, ChildListener listener);

    /**移除该节点的子节点监听器
     * @param path:    directory. All of child of path will be listened.
     * @param listener
     */
    void addDataListener(String path, DataListener listener);

    /**
     * @param path:    directory. All of child of path will be listened.
     * @param listener
     * @param executor another thread
     */
    void addDataListener(String path, DataListener listener, Executor executor);

    void removeDataListener(String path, DataListener listener);

    void removeChildListener(String path, ChildListener listener);
    /**
     * 新增状态监听器
     * @param listener
     */
    void addStateListener(StateListener listener);
    /**
     * 移除状态监听
     * @param listener
     */
    void removeStateListener(StateListener listener);
    /**
     * 判断是否连接
     * @return
     */
    boolean isConnected();
    /**
     * 关闭客户端
     */
    void close();

    /**
     * 获得url
     * @return
     */
    URL getUrl();

    void create(String path, String content, boolean ephemeral);

    String getContent(String path);

    boolean checkExists(String path);

}
