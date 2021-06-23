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

import org.apache.dubbo.rpc.Exporter;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.protocol.AbstractExporter;

import java.util.Map;

/**
 * DubboExporter
 * 该类继承了AbstractExporter，是dubbo协议中独有的服务暴露者。
 * 其中对于服务暴露者用集合做了缓存，并且只重写了了unexport
 */
public class DubboExporter<T> extends AbstractExporter<T> {
    /**
     * 服务key
     */
    private final String key;
    /**
     * 服务暴露者集合
     */
    private final Map<String, Exporter<?>> exporterMap;

    public DubboExporter(Invoker<T> invoker, String key, Map<String, Exporter<?>> exporterMap) {
        super(invoker);
        this.key = key;
        this.exporterMap = exporterMap;
    }

    @Override
    public void afterUnExport() {
        // 从集合中移除该key
        exporterMap.remove(key);
    }

}