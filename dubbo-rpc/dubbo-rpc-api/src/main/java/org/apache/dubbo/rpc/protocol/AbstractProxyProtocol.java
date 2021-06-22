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

package org.apache.dubbo.rpc.protocol;

import org.apache.dubbo.common.Parameters;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.Constants;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.RemotingServer;
import org.apache.dubbo.rpc.Exporter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.ProtocolServer;
import org.apache.dubbo.rpc.ProxyFactory;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.apache.dubbo.common.constants.CommonConstants.ANYHOST_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.ANYHOST_VALUE;

/**
 * AbstractProxyProtocol
 * 该类继承了AbstractProtocol类，其中利用了代理工厂对AbstractProtocol中的两个集合进行了填充，并且对异常做了处理
 */
public abstract class AbstractProxyProtocol extends AbstractProtocol {
    /**
     * rpc的异常类集合
     */
    private final List<Class<?>> rpcExceptions = new CopyOnWriteArrayList<Class<?>>();
    /**
     * 代理工厂
     */
    protected ProxyFactory proxyFactory;

    public AbstractProxyProtocol() {
    }

    public AbstractProxyProtocol(Class<?>... exceptions) {
        for (Class<?> exception : exceptions) {
            addRpcException(exception);
        }
    }

    public void addRpcException(Class<?> exception) {
        this.rpcExceptions.add(exception);
    }

    public ProxyFactory getProxyFactory() {
        return proxyFactory;
    }

    public void setProxyFactory(ProxyFactory proxyFactory) {
        this.proxyFactory = proxyFactory;
    }

    /**
     * 其中分为两个步骤，创建一个exporter，放入到集合汇中。在创建exporter时对unexport方法进行了重写。
     * @param invoker Service invoker 服务的实体域
     * @param <T>
     * @return
     * @throws RpcException
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> Exporter<T> export(final Invoker<T> invoker) throws RpcException {
        // 获得uri
        final String uri = serviceKey(invoker.getUrl());
        // 获得服务暴露者
        Exporter<T> exporter = (Exporter<T>) exporterMap.get(uri);
        if (exporter != null) {
            // When modifying the configuration through override, you need to re-expose the newly modified service.
            if (Objects.equals(exporter.getInvoker().getUrl(), invoker.getUrl())) {
                return exporter;
            }
        }
        // 新建一个线程
        final Runnable runnable = doExport(proxyFactory.getProxy(invoker, true), invoker.getInterface(), invoker.getUrl());
        exporter = new AbstractExporter<T>(invoker) {
            /**
             * 取消暴露
             */
            @Override
            public void afterUnExport() {
                // 移除该key对应的服务暴露者
                exporterMap.remove(uri);
                if (runnable != null) {
                    try {
                        // 启动线程
                        runnable.run();
                    } catch (Throwable t) {
                        logger.warn(t.getMessage(), t);
                    }
                }
            }
        };
        // 加入集合
        exporterMap.put(uri, exporter);
        return exporter;
    }

    /**
     * 该方法是服务引用，先从代理工厂中获得Invoker对象target，然后创建了真实的invoker在重写方法中调用代理的方法，最后加入到集合
     * @param type
     * @param url
     * @param <T>
     * @return
     * @throws RpcException
     */
    @Override
    protected <T> Invoker<T> protocolBindingRefer(final Class<T> type, final URL url) throws RpcException {
        // 通过代理获得实体域
        final Invoker<T> target = proxyFactory.getInvoker(doRefer(type, url), type, url);
        Invoker<T> invoker = new AbstractInvoker<T>(type, url) {
            @Override
            protected Result doInvoke(Invocation invocation) throws Throwable {
                try {
                    // 获得调用结果
                    Result result = target.invoke(invocation);
                    // FIXME result is an AsyncRpcResult instance.
                    Throwable e = result.getException();
                    // 如果抛出异常，则抛出相应异常
                    if (e != null) {
                        for (Class<?> rpcException : rpcExceptions) {
                            if (rpcException.isAssignableFrom(e.getClass())) {
                                throw getRpcException(type, url, invocation, e);
                            }
                        }
                    }
                    return result;
                } catch (RpcException e) {
                    // 抛出未知异常
                    if (e.getCode() == RpcException.UNKNOWN_EXCEPTION) {
                        e.setCode(getErrorCode(e.getCause()));
                    }
                    throw e;
                } catch (Throwable e) {
                    throw getRpcException(type, url, invocation, e);
                }
            }

            @Override
            public void destroy() {
                super.destroy();
                target.destroy();
                invokers.remove(this);
            }
        };
        // 加入集合
        invokers.add(invoker);
        return invoker;
    }

    protected RpcException getRpcException(Class<?> type, URL url, Invocation invocation, Throwable e) {
        RpcException re = new RpcException("Failed to invoke remote service: " + type + ", method: "
                + invocation.getMethodName() + ", cause: " + e.getMessage(), e);
        re.setCode(getErrorCode(e));
        return re;
    }

    protected String getAddr(URL url) {
        String bindIp = url.getParameter(Constants.BIND_IP_KEY, url.getHost());
        if (url.getParameter(ANYHOST_KEY, false)) {
            bindIp = ANYHOST_VALUE;
        }
        return NetUtils.getIpByHost(bindIp) + ":" + url.getParameter(Constants.BIND_PORT_KEY, url.getPort());
    }

    protected int getErrorCode(Throwable e) {
        return RpcException.UNKNOWN_EXCEPTION;
    }

    //可以看到其中抽象了服务引用和暴露的方法，让各类协议各自实现。
    protected abstract <T> Runnable doExport(T impl, Class<T> type, URL url) throws RpcException;

    protected abstract <T> T doRefer(Class<T> type, URL url) throws RpcException;

    protected class ProxyProtocolServer implements ProtocolServer {

        private RemotingServer server;
        private String address;

        public ProxyProtocolServer(RemotingServer server) {
            this.server = server;
        }

        @Override
        public RemotingServer getRemotingServer() {
            return server;
        }

        @Override
        public String getAddress() {
            return StringUtils.isNotEmpty(address) ? address : server.getUrl().getAddress();
        }

        @Override
        public void setAddress(String address) {
            this.address = address;
        }

        @Override
        public URL getUrl() {
            return server.getUrl();
        }

        @Override
        public void close() {
            server.close();
        }
    }

    protected abstract class RemotingServerAdapter implements RemotingServer {

        public abstract Object getDelegateServer();

        /**
         * @return
         */
        @Override
        public boolean isBound() {
            return false;
        }

        @Override
        public Collection<Channel> getChannels() {
            return null;
        }

        @Override
        public Channel getChannel(InetSocketAddress remoteAddress) {
            return null;
        }

        @Override
        public void reset(Parameters parameters) {

        }

        @Override
        public void reset(URL url) {

        }

        @Override
        public URL getUrl() {
            return null;
        }

        @Override
        public ChannelHandler getChannelHandler() {
            return null;
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            return null;
        }

        @Override
        public void send(Object message) throws RemotingException {

        }

        @Override
        public void send(Object message, boolean sent) throws RemotingException {

        }

        @Override
        public void close() {

        }

        @Override
        public void close(int timeout) {

        }

        @Override
        public void startClose() {

        }

        @Override
        public boolean isClosed() {
            return false;
        }
    }


}
