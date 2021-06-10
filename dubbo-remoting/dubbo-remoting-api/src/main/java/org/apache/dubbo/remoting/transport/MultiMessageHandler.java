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

import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.ExecutionException;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.exchange.support.MultiMessage;

/**
 * @see MultiMessage
 */
//多消息处理器的抽象类。同样继承了AbstractChannelHandlerDelegate类
public class MultiMessageHandler extends AbstractChannelHandlerDelegate {

    protected static final Logger logger = LoggerFactory.getLogger(MultiMessageHandler.class);

    public MultiMessageHandler(ChannelHandler handler) {
        super(handler);
    }

    @SuppressWarnings("unchecked")
    @Override
    public void received(Channel channel, Object message) throws RemotingException {
        // 当消息为多消息时 循环交给handler处理接收到当消息
        if (message instanceof MultiMessage) {
            MultiMessage list = (MultiMessage) message;
            for (Object obj : list) {
                try {
                    handler.received(channel, obj);
                } catch (ExecutionException e) {
                    logger.error("MultiMessageHandler received fail.", e);
                    handler.caught(channel, e);
                }
            }
        } else {
            // 如果是单消息，就直接交给handler处理器
            handler.received(channel, message);
        }
    }
}
