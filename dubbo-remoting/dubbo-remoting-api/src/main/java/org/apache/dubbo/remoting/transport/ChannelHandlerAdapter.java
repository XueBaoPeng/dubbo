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

import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.RemotingException;

/**
 * ChannelHandlerAdapter.
 * 该类实现了ChannelHandler接口，是通道处理器适配类，该类中所有实现方法都是空的，
 * 所有想实现ChannelHandler接口的类可以直接继承该类，选择需要实现的方法进行实现，不需要实现ChannelHandler接口中所有方法。
 */
public class ChannelHandlerAdapter implements ChannelHandler {

    @Override
    public void connected(Channel channel) throws RemotingException {
    }

    @Override
    public void disconnected(Channel channel) throws RemotingException {
    }

    @Override
    public void sent(Channel channel, Object message) throws RemotingException {
    }

    @Override
    public void received(Channel channel, Object message) throws RemotingException {
    }

    @Override
    public void caught(Channel channel, Throwable exception) throws RemotingException {
    }

}
