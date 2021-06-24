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
package org.apache.dubbo.rpc.protocol.hessian;

import org.apache.dubbo.rpc.RpcContext;

import com.caucho.hessian.client.HessianConnection;
import com.caucho.hessian.client.HessianConnectionFactory;
import com.caucho.hessian.client.HessianProxyFactory;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.HttpClientBuilder;

import java.net.URL;

import static org.apache.dubbo.remoting.Constants.DEFAULT_EXCHANGER;

/**
 * HttpClientConnectionFactory
 * TODO, Consider using connection pool
 * 该类实现了HessianConnectionFactory接口，是创建HttpClientConnection的工厂类。
 * 该类的实现跟DubboHessianURLConnectionFactory类类似，
 * 但是DubboHessianURLConnectionFactory是标准的Hessian接口调用会采用的工厂类，
 * 而HttpClientConnectionFactory是Dubbo 的 Hessian 协议调用。当然Dubbo 的 Hessian 协议也是基于http的
 * 实现了两个方法，第一个方法是给http连接设置两个参数配置，第二个方法是创建一个连接。
 */
public class HttpClientConnectionFactory implements HessianConnectionFactory {
    /**
     * httpClient对象
     */
    private HttpClient httpClient;

    @Override
    public void setHessianProxyFactory(HessianProxyFactory factory) {
        // 设置连接超时时间
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout((int) factory.getConnectTimeout())
                .setSocketTimeout((int) factory.getReadTimeout())
                .build();
        // 设置读取数据时阻塞链路的超时时间
        httpClient = HttpClientBuilder.create().setDefaultRequestConfig(requestConfig).build();
    }

    @Override
    public HessianConnection open(URL url) {
        // 创建一个HttpClientConnection实例
        HttpClientConnection httpClientConnection = new HttpClientConnection(httpClient, url);
        // 获得上下文，用来获得附加值
        RpcContext context = RpcContext.getContext();
        // 遍历附加值，放入到协议头里面
        for (String key : context.getObjectAttachments().keySet()) {
            httpClientConnection.addHeader(DEFAULT_EXCHANGER + key, context.getAttachment(key));
        }
        return httpClientConnection;
    }
}
