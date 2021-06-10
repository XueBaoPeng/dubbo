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
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.Constants;
import org.apache.dubbo.remoting.Endpoint;
import org.apache.dubbo.remoting.RemotingException;

/**
 * AbstractPeer
 * 实现ChannelHandler接口并且有在属性中还有一个handler，下面很多实现方法也是直接调用了handler方法，这种模式叫做装饰模式，这样做可以对装饰对象灵活的增强功能。
 * 在该类中有closing和closed属性，在Endpoint中有很多关于关闭通道的操作，会有关闭中和关闭完成的状态区分，在该类中就缓存了这两个属性来判断关闭的状态。
 */
public abstract class AbstractPeer implements Endpoint, ChannelHandler {

    private final ChannelHandler handler;

    private volatile URL url;

    //是否正在关闭
    // closing closed means the process is being closed and close is finished
    private volatile boolean closing;

    //是否关闭完成
    private volatile boolean closed;

    public AbstractPeer(URL url, ChannelHandler handler) {
        if (url == null) {
            throw new IllegalArgumentException("url == null");
        }
        if (handler == null) {
            throw new IllegalArgumentException("handler == null");
        }
        this.url = url;
        this.handler = handler;
    }

    @Override
    public void send(Object message) throws RemotingException {
        // url中sent的配置项
        send(message, url.getParameter(Constants.SENT_KEY, false));
    }

    @Override
    public void close() {
        closed = true;
    }

    @Override
    public void close(int timeout) {
        close();
    }

    @Override
    public void startClose() {
        if (isClosed()) {
            return;
        }
        closing = true;
    }

    @Override
    public URL getUrl() {
        return url;
    }

    protected void setUrl(URL url) {
        if (url == null) {
            throw new IllegalArgumentException("url == null");
        }
        this.url = url;
    }

    @Override
    public ChannelHandler getChannelHandler() {
        if (handler instanceof ChannelHandlerDelegate) {
            return ((ChannelHandlerDelegate) handler).getHandler();
        } else {
            return handler;
        }
    }

    /**
     * @return ChannelHandler
     */
    @Deprecated
    public ChannelHandler getHandler() {
        return getDelegateHandler();
    }

    /**
     * Return the final handler (which may have been wrapped). This method should be distinguished with getChannelHandler() method
     *
     * @return ChannelHandler
     */
    public ChannelHandler getDelegateHandler() {
        return handler;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    public boolean isClosing() {
        return closing && !closed;
    }

    @Override
    public void connected(Channel ch) throws RemotingException {
        if (closed) {
            return;
        }
        handler.connected(ch);
    }

    @Override
    public void disconnected(Channel ch) throws RemotingException {
        handler.disconnected(ch);
    }

    @Override
    public void sent(Channel ch, Object msg) throws RemotingException {
        if (closed) {
            return;
        }
        handler.sent(ch, msg);
    }

    @Override
    public void received(Channel ch, Object msg) throws RemotingException {
        if (closed) {
            return;
        }
        handler.received(ch, msg);
    }

    @Override
    public void caught(Channel ch, Throwable ex) throws RemotingException {
        handler.caught(ch, ex);
    }
}
