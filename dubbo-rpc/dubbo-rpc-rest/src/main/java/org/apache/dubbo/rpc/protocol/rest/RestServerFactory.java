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
package org.apache.dubbo.rpc.protocol.rest;

import org.apache.dubbo.remoting.http.HttpBinder;

/**
 * Only the server that implements servlet container
 * could support something like @Context injection of servlet objects.
 * 该类是服务器工程类，用来提供相应的实例，里面逻辑比较简单。
 */
public class RestServerFactory {
    /**
     * http绑定者
     */
    private HttpBinder httpBinder;

    public void setHttpBinder(HttpBinder httpBinder) {
        this.httpBinder = httpBinder;
    }

    /**
     * 创建服务器
     * @param name
     * @return
     */
    public RestProtocolServer createServer(String name) {
        // TODO move names to Constants
        // 如果是servlet或者jetty或者tomcat，则创建DubboHttpServer
        if ("servlet".equalsIgnoreCase(name) || "jetty".equalsIgnoreCase(name) || "tomcat".equalsIgnoreCase(name)) {
            return new DubboHttpProtocolServer(httpBinder);
        } else if ("netty".equalsIgnoreCase(name)) {
            // 如果是netty，那么直接创建netty服务器
            return new NettyRestProtocolServer();
        } else {
            // 否则 抛出异常
            throw new IllegalArgumentException("Unrecognized server name: " + name);
        }
    }
}
