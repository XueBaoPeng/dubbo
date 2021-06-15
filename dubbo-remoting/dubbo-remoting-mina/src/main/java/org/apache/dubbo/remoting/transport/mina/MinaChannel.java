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
package org.apache.dubbo.remoting.transport.mina;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.transport.AbstractChannel;

import org.apache.mina.common.IoSession;
import org.apache.mina.common.WriteFuture;

import java.net.InetSocketAddress;

import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_TIMEOUT;
import static org.apache.dubbo.common.constants.CommonConstants.TIMEOUT_KEY;

/**
 * MinaChannel
 */
final class MinaChannel extends AbstractChannel {

    private static final Logger logger = LoggerFactory.getLogger(MinaChannel.class);

    //通道的key
    private static final String CHANNEL_KEY = MinaChannel.class.getName() + ".CHANNEL";

    //mina中的一个句柄，表示两个端点之间的连接，与传输类型无关
    private final IoSession session;

    private MinaChannel(IoSession session, URL url, ChannelHandler handler) {
        super(url, handler);
        if (session == null) {
            throw new IllegalArgumentException("mina session == null");
        }
        this.session = session;
    }

    static MinaChannel getOrAddChannel(IoSession session, URL url, ChannelHandler handler) {
        // 如果连接session为空，则返回空
        if (session == null) {
            return null;
        }
        // 获得MinaChannel实例
        MinaChannel ret = (MinaChannel) session.getAttribute(CHANNEL_KEY);
        // 如果不存在，则创建
        if (ret == null) {
            // 创建一个MinaChannel实例
            ret = new MinaChannel(session, url, handler);
            // 如果两个端点连接
            if (session.isConnected()) {
                // 把新创建的MinaChannel添加到session 中
                MinaChannel old = (MinaChannel) session.setAttribute(CHANNEL_KEY, ret);
                // 如果属性的旧值不为空，则重新设置旧值
                if (old != null) {
                    session.setAttribute(CHANNEL_KEY, old);
                    ret = old;
                }
            }
        }
        return ret;
    }

    static void removeChannelIfDisconnected(IoSession session) {
        if (session != null && !session.isConnected()) {
            //该方法是当没有连接时移除该通道，比较简单
            session.removeAttribute(CHANNEL_KEY);
        }
    }

    @Override
    public InetSocketAddress getLocalAddress() {
        return (InetSocketAddress) session.getLocalAddress();
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
        return (InetSocketAddress) session.getRemoteAddress();
    }

    @Override
    public boolean isConnected() {
        return session.isConnected();
    }

    @Override
    public void send(Object message, boolean sent) throws RemotingException {
        super.send(message, sent);

        boolean success = true;
        int timeout = 0;
        try {
            // 发送消息，返回future
            WriteFuture future = session.write(message);
            // 如果已经发送过了
            if (sent) {
                // 获得延迟时间
                timeout = getUrl().getPositiveParameter(TIMEOUT_KEY, DEFAULT_TIMEOUT);
                // 等待timeout的连接时间后查看是否发送成功
                success = future.join(timeout);
            }
        } catch (Throwable e) {
            throw new RemotingException(this, "Failed to send message " + message + " to " + getRemoteAddress() + ", cause: " + e.getMessage(), e);
        }

        if (!success) {
            throw new RemotingException(this, "Failed to send message " + message + " to " + getRemoteAddress()
                    + "in timeout(" + timeout + "ms) limit");
        }
    }

    @Override
    public void close() {
        try {
            super.close();
        } catch (Exception e) {
            logger.warn(e.getMessage(), e);
        }
        try {
            removeChannelIfDisconnected(session);
        } catch (Exception e) {
            logger.warn(e.getMessage(), e);
        }
        try {
            if (logger.isInfoEnabled()) {
                logger.info("CLose mina channel " + session);
            }
            session.close();
        } catch (Exception e) {
            logger.warn(e.getMessage(), e);
        }
    }

    @Override
    public boolean hasAttribute(String key) {
        return session.containsAttribute(key);
    }

    @Override
    public Object getAttribute(String key) {
        return session.getAttribute(key);
    }

    @Override
    public void setAttribute(String key, Object value) {
        session.setAttribute(key, value);
    }

    @Override
    public void removeAttribute(String key) {
        session.removeAttribute(key);
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((session == null) ? 0 : session.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        MinaChannel other = (MinaChannel) obj;
        if (session == null) {
            if (other.session != null) {
                return false;
            }
        } else if (!session.equals(other.session)) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return "MinaChannel [session=" + session + "]";
    }

}
