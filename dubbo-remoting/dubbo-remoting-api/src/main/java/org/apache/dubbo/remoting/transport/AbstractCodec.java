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
package org.apache.dubbo.remoting.transport;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.serialize.Serialization;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.Codec2;
import org.apache.dubbo.remoting.Constants;
import org.apache.dubbo.remoting.exchange.Request;
import org.apache.dubbo.remoting.exchange.Response;

import java.io.IOException;
import java.net.InetSocketAddress;

import static org.apache.dubbo.common.constants.CommonConstants.SIDE_KEY;

/**
 * AbstractCodec
 */
//实现 Codec2 接口，，其中实现了一些编解码的公共逻辑。
public abstract class AbstractCodec implements Codec2 {

    private static final Logger logger = LoggerFactory.getLogger(AbstractCodec.class);

    private static final String CLIENT_SIDE = "client";

    private static final String SERVER_SIDE = "server";

    //该方法是检验消息长度。
    protected static void checkPayload(Channel channel, long size) throws IOException {
        // 默认长度
        int payload = getPayload(channel);
        boolean overPayload = isOverPayload(payload, size);
        // 优先从url中获得消息长度配置，如果没有则用默认长度
        if (overPayload) {
            // 如果消息长度过长，则报错
            ExceedPayloadLimitException e = new ExceedPayloadLimitException(
                    "Data length too large: " + size + ", max payload: " + payload + ", channel: " + channel);
            logger.error(e);
            throw e;
        }
    }

    protected static int getPayload(Channel channel) {
        int payload = Constants.DEFAULT_PAYLOAD;
        if (channel != null && channel.getUrl() != null) {
            payload = channel.getUrl().getParameter(Constants.PAYLOAD_KEY, Constants.DEFAULT_PAYLOAD);
        }
        return payload;
    }

    protected static boolean isOverPayload(int payload, long size) {
        if (payload > 0 && size > payload) {
            return true;
        }
        return false;
    }

    //该方法是获得序列化对象
    protected Serialization getSerialization(Channel channel, Request req) {
        return CodecSupport.getSerialization(channel.getUrl());
    }

    protected Serialization getSerialization(Channel channel, Response res) {
        return CodecSupport.getSerialization(channel.getUrl());
    }

    protected Serialization getSerialization(Channel channel) {
        return CodecSupport.getSerialization(channel.getUrl());
    }

    // 该方法是判断是否为客户端侧的通道。
    protected boolean isClientSide(Channel channel) {
        String side = (String) channel.getAttribute(SIDE_KEY);
        if (CLIENT_SIDE.equals(side)) {
            return true;
        } else if (SERVER_SIDE.equals(side)) {
            return false;
        } else {
            InetSocketAddress address = channel.getRemoteAddress();
            URL url = channel.getUrl();
            // 判断url的主机地址是否和远程地址一样，如果是，则判断为client，如果不是，则判断为server
            boolean isClient = url.getPort() == address.getPort()
                && NetUtils.filterLocalHost(url.getIp()).equals(
                NetUtils.filterLocalHost(address.getAddress()
                    .getHostAddress()));
            // 把value设置进去
            channel.setAttribute(SIDE_KEY, isClient ? CLIENT_SIDE
                : SERVER_SIDE);
            return isClient;
        }
    }
   //该方法是判断是否为服务端侧的通道。
    protected boolean isServerSide(Channel channel) {
        return !isClientSide(channel);
    }

}
