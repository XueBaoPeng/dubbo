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
package org.apache.dubbo.remoting;

import org.apache.dubbo.common.Parameters;
import org.apache.dubbo.common.Resetable;

import java.net.InetSocketAddress;
import java.util.Collection;

/**
 * Remoting Server. (API/SPI, Prototype, ThreadSafe)
 * <p>
 * <a href="http://en.wikipedia.org/wiki/Client%E2%80%93server_model">Client/Server</a>
 *该接口是服务端接口，继承了Endpoint和Resetable，继承Endpoint是因为服务端也是一个点，继承Resetable接口是为了继承reset方法。
 * 除了这些以外，服务端独有的是检测是否启动成功，还有事获得连接该服务器上所有通道，这里获得所有通道其实就意味着获得了所有连接该服务器的客户端，因为客户端和通道是一一对应的。
 * @see org.apache.dubbo.remoting.Transporter#bind(org.apache.dubbo.common.URL, ChannelHandler)
 */
public interface RemotingServer extends Endpoint, Resetable, IdleSensible {

    /**
     * is bound.
     * 判断是否绑定到本地端口，也就是该服务器是否启动成功，能够连接、接收消息，提供服务。
     * @return bound
     */
    boolean isBound();

    /**
     * get channels.
     * 获得连接该服务器的通道
     * @return channels
     */
    Collection<Channel> getChannels();

    /**
     * get channel.
     *  通过远程地址获得该地址对应的通道
     * @param remoteAddress
     * @return channel
     */
    Channel getChannel(InetSocketAddress remoteAddress);

    @Deprecated
    void reset(Parameters parameters);

}
