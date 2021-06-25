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
package org.apache.dubbo.rpc.protocol.webservice;

import org.apache.cxf.binding.soap.SoapTransportFactory;
import org.apache.cxf.common.util.StringUtils;
import org.apache.cxf.endpoint.Server;
import org.apache.cxf.service.model.OperationInfo;
import org.apache.cxf.transport.Destination;

import org.apache.cxf.transport.http.HTTPConduit;
import org.apache.cxf.transport.http.AbstractHTTPDestination;
import org.apache.cxf.transport.http.DestinationRegistry;
import org.apache.cxf.transport.http.DestinationRegistryImpl;
import org.apache.cxf.transport.http.HttpDestinationFactory;
import org.apache.cxf.wsdl.service.factory.AbstractServiceConfiguration;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.remoting.Constants;
import org.apache.dubbo.remoting.RemotingServer;
import org.apache.dubbo.remoting.http.HttpBinder;
import org.apache.dubbo.remoting.http.HttpHandler;
import org.apache.dubbo.remoting.http.servlet.DispatcherServlet;
import org.apache.dubbo.rpc.ProtocolServer;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.protocol.AbstractProxyProtocol;

import org.apache.cxf.bus.extension.ExtensionManagerBus;
import org.apache.cxf.endpoint.Client;
import org.apache.cxf.frontend.ClientProxy;
import org.apache.cxf.frontend.ClientProxyFactoryBean;
import org.apache.cxf.frontend.ServerFactoryBean;
import org.apache.cxf.interceptor.Fault;
import org.apache.cxf.transport.servlet.ServletController;
import org.apache.cxf.transport.servlet.ServletDestinationFactory;
import org.apache.cxf.transports.http.configuration.HTTPClientPolicy;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.SocketTimeoutException;

import static org.apache.dubbo.common.constants.CommonConstants.SERVICE_PATH_PREFIX;
import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_TIMEOUT;
import static org.apache.dubbo.common.constants.CommonConstants.PROTOCOL_SERVER;
import static org.apache.dubbo.common.constants.CommonConstants.PROTOCOL_SERVER_SERVLET;
import static org.apache.dubbo.common.constants.CommonConstants.TIMEOUT_KEY;

/**
 * WebServiceProtocol.
 * 该类继承了AbstractProxyProtocol，是webservice协议的关键逻辑实现
 */
public class WebServiceProtocol extends AbstractProxyProtocol {
    /**
     * 默认端口
     */
    public static final int DEFAULT_PORT = 80;
    /**
     * 总线，该总线使用CXF内置的扩展管理器来加载组件（而不是使用Spring总线实现）。虽然加载速度更快，但它不允许像Spring总线那样进行大量配置和定制。
     */
    private final ExtensionManagerBus bus = new ExtensionManagerBus();
    /**
     * http通信工厂对象
     */
    private SoapTransportFactory transportFactory = null;

    private ServerFactoryBean serverFactoryBean = null;

    private DestinationRegistry destinationRegistry=null;
    /**
     * http绑定者
     */
    private HttpBinder httpBinder;

    private Server server = null;

    public WebServiceProtocol() {
        super(Fault.class);
        bus.setExtension(new ServletDestinationFactory(), HttpDestinationFactory.class);
    }

    public void setHttpBinder(HttpBinder httpBinder) {
        this.httpBinder = httpBinder;
    }

    @Override
    public int getDefaultPort() {
        return DEFAULT_PORT;
    }


    /**
     * 该方法是服务暴露的逻辑实现，基于cxf一些类。
     * @param impl
     * @param type
     * @param url
     * @param <T>
     * @return
     * @throws RpcException
     */
    @Override
    protected <T> Runnable doExport(T impl, Class<T> type, URL url) throws RpcException {
        transportFactory = new SoapTransportFactory();
        destinationRegistry  = new DestinationRegistryImpl();
        // 获得地址
        String addr = getAddr(url);
        ProtocolServer protocolServer = serverMap.get(addr);
        // 获得http服务
        if (protocolServer == null) {
            // 如果服务为空，则重新创建服务器。并且加入集合
            RemotingServer remotingServer = httpBinder.bind(url, new WebServiceHandler());
            serverMap.put(addr, new ProxyProtocolServer(remotingServer));
        }
        // 服务加载器
        serverFactoryBean = new ServerFactoryBean();
        // 设置地址
        serverFactoryBean.setAddress(url.getAbsolutePath());
        // 设置服务类型
        serverFactoryBean.setServiceClass(type);
        // 设置实现类
        serverFactoryBean.setServiceBean(impl);
        // 设置总线
        serverFactoryBean.setBus(bus);
        // 设置通信工厂
        serverFactoryBean.setDestinationFactory(transportFactory);
        serverFactoryBean.getServiceFactory().getConfigurations().add(new URLHashMethodNameSoapActionServiceConfiguration());
        // 创建
        server = serverFactoryBean.create();
        return new Runnable() {
            @Override
            public void run() {
                if(serverFactoryBean.getServer()!= null) {
                    serverFactoryBean.getServer().destroy();
                }
                if(serverFactoryBean.getBus()!=null) {
                    serverFactoryBean.getBus().shutdown(true);
                }
                ProtocolServer httpServer = serverMap.get(addr);
                if(httpServer != null){
                    httpServer.close();
                    serverMap.remove(addr);
                }
            }
        };
    }

    /**
     * 该方法是服务引用的逻辑实现
     * @param serviceType
     * @param url
     * @param <T>
     * @return
     * @throws RpcException
     */
    @Override
    @SuppressWarnings("unchecked")
    protected <T> T doRefer(final Class<T> serviceType, URL url) throws RpcException {
        // 创建代理工厂
        ClientProxyFactoryBean proxyFactoryBean = new ClientProxyFactoryBean();
        String servicePathPrefix = url.getParameter(SERVICE_PATH_PREFIX);
        if (!StringUtils.isEmpty(servicePathPrefix) && PROTOCOL_SERVER_SERVLET.equals(url.getParameter(PROTOCOL_SERVER))) {
            url = url.setPath(servicePathPrefix + "/" + url.getPath());
        }
        proxyFactoryBean.setAddress(url.setProtocol("http").toIdentityString());
        // 设置服务类型
        proxyFactoryBean.setServiceClass(serviceType);
        // 设置总线
        proxyFactoryBean.setBus(bus);
        // 创建
        T ref = (T) proxyFactoryBean.create();
        // 获得代理
        Client proxy = ClientProxy.getClient(ref);
        // 获得HTTPConduit 处理“http”和“https”传输协议。实例由显式设置或配置的策略控制
        HTTPConduit conduit = (HTTPConduit) proxy.getConduit();
        // 用于配置客户端HTTP端口的属性
        HTTPClientPolicy policy = new HTTPClientPolicy();
        // 配置连接超时时间
        policy.setConnectionTimeout(url.getParameter(Constants.CONNECT_TIMEOUT_KEY, Constants.DEFAULT_CONNECT_TIMEOUT));
        // 配置调用超时时间
        policy.setReceiveTimeout(url.getParameter(TIMEOUT_KEY, DEFAULT_TIMEOUT));
        conduit.setClient(policy);
        return ref;
    }

    @Override
    protected int getErrorCode(Throwable e) {
        if (e instanceof Fault) {
            e = e.getCause();
        }
        if (e instanceof SocketTimeoutException) {
            return RpcException.TIMEOUT_EXCEPTION;
        } else if (e instanceof IOException) {
            return RpcException.NETWORK_EXCEPTION;
        }
        return super.getErrorCode(e);
    }

    /**
     * 该内部类实现了HttpHandler接口，是WebService协议的请求的处理类。
     */
    private class WebServiceHandler implements HttpHandler {

        private volatile ServletController servletController;

        @Override
        public void handle(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
            // 如果servletController为空，则重新加载一个
            if (servletController == null) {
                HttpServlet httpServlet = DispatcherServlet.getInstance();
                if (httpServlet == null) {
                    response.sendError(500, "No such DispatcherServlet instance.");
                    return;
                }
                // 创建servletController
                synchronized (this) {
                    if (servletController == null) {

                        if(server == null){
                            server = WebServiceProtocol.this.serverFactoryBean.getServer();
                        }
                        Destination d = WebServiceProtocol.this.transportFactory.getDestination(server.getEndpoint().getEndpointInfo(),bus);
                        destinationRegistry.addDestination((AbstractHTTPDestination) d);
                        this.servletController = new ServletController(destinationRegistry, httpServlet.getServletConfig(), httpServlet);
                    }
                }
            }
            // 设置远程地址
            RpcContext.getContext().setRemoteAddress(request.getRemoteAddr(), request.getRemotePort());
            // 调用方法
            servletController.invoke(request, response);
        }

    }

    private class URLHashMethodNameSoapActionServiceConfiguration extends AbstractServiceConfiguration {
        public String getAction(OperationInfo op, Method method) {
            String uri = op.getName().getNamespaceURI();
            String action = op.getName().getLocalPart();
            if (StringUtils.isEmpty(action)) {
                action = method.getName();
            }
            return uri+"#"+action;
        }
    }

}
