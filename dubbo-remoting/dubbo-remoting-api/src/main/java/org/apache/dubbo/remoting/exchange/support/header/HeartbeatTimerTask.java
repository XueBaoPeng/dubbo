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

package org.apache.dubbo.remoting.exchange.support.header;

import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.exchange.Request;

import static org.apache.dubbo.common.constants.CommonConstants.HEARTBEAT_EVENT;

/**
 * HeartbeatTimerTask
 */
public class HeartbeatTimerTask extends AbstractTimerTask {

    private static final Logger logger = LoggerFactory.getLogger(HeartbeatTimerTask.class);
    //心跳间隔 单位：ms
    private final int heartbeat;

    HeartbeatTimerTask(ChannelProvider channelProvider, Long heartbeatTick, int heartbeat) {
        super(channelProvider, heartbeatTick);
        this.heartbeat = heartbeat;
    }

    //如果需要心跳的通道本身如果关闭了，那么跳过，不添加心跳机制。
    //无论是接收消息还是发送消息，只要超过了设置的心跳间隔，就发送心跳消息来测试是否断开
    //如果最后一次接收到消息到到现在已经超过了心跳超时时间，那就认定对方的确断开，分两种情况来处理对方断开的情况。
    //分别是服务端断开，客户端重连以及客户端断开，服务端断开这个客户端的连接。这里要好好品味一下谁是发送方，谁在等谁的响应，苦苦没有等到
    @Override
    protected void doTask(Channel channel) {
        try {
            // 最后一次接收到消息的时间戳
            Long lastRead = lastRead(channel);
            // 最后一次发送消息的时间戳
            Long lastWrite = lastWrite(channel);
            // 如果最后一次接收或者发送消息到时间到现在的时间间隔超过了心跳间隔时间
            if ((lastRead != null && now() - lastRead > heartbeat)
                    || (lastWrite != null && now() - lastWrite > heartbeat)) {
                // 创建一个request
                Request req = new Request();
                //设置版本号
                req.setVersion(Version.getProtocolVersion());
                req.setTwoWay(true);
                // 设置事件类型，为心跳事件
                req.setEvent(HEARTBEAT_EVENT);
                // 发送心跳请求
                channel.send(req);
                if (logger.isDebugEnabled()) {
                    logger.debug("Send heartbeat to remote channel " + channel.getRemoteAddress()
                            + ", cause: The channel has no data-transmission exceeds a heartbeat period: "
                            + heartbeat + "ms");
                }
            }
        } catch (Throwable t) {
            logger.warn("Exception when heartbeat to remote channel " + channel.getRemoteAddress(), t);
        }
    }
}
