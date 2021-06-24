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
package org.apache.dubbo.rpc.protocol.rest;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.remoting.http.HttpBinder;
import org.apache.dubbo.remoting.http.HttpHandler;
import org.apache.dubbo.remoting.http.HttpServer;
import org.apache.dubbo.remoting.http.servlet.BootstrapListener;
import org.apache.dubbo.remoting.http.servlet.ServletManager;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;

import org.jboss.resteasy.plugins.server.servlet.HttpServletDispatcher;
import org.jboss.resteasy.spi.ResteasyDeployment;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Enumeration;

/**
 * 该类继承了BaseRestServer，实现了doStart和getDeployment方法，当配置选择servlet、jetty或者tomcat作为远程通信的实现时，实现的服务器类
 */
public class DubboHttpProtocolServer extends BaseRestProtocolServer {
    /**
     * HttpServletDispatcher实例
     */
    private final HttpServletDispatcher dispatcher = new HttpServletDispatcher();
    /**
     * Resteasy的服务部署器
     */
    private final ResteasyDeployment deployment = new ResteasyDeployment();
    /**
     * http绑定者
     */
    private HttpBinder httpBinder;
    /**
     * http服务器
     */
    private HttpServer httpServer;
//    private boolean isExternalServer;

    public DubboHttpProtocolServer(HttpBinder httpBinder) {
        this.httpBinder = httpBinder;
    }

    @Override
    protected void doStart(URL url) {
        // TODO jetty will by default enable keepAlive so the xml config has no effect now
        // 创建http服务器
        httpServer = httpBinder.bind(url, new RestHandler());
        // 获得ServletContext
        ServletContext servletContext = ServletManager.getInstance().getServletContext(url.getPort());
        // 如果为空 ，则获得默认端口对应的ServletContext对象
        if (servletContext == null) {
            servletContext = ServletManager.getInstance().getServletContext(ServletManager.EXTERNAL_SERVER_PORT);
        }
        // 如果还是为空 ，则抛出异常
        if (servletContext == null) {
            throw new RpcException("No servlet context found. If you are using server='servlet', " +
                    "make sure that you've configured " + BootstrapListener.class.getName() + " in web.xml");
        }
        // 设置属性部署器
        servletContext.setAttribute(ResteasyDeployment.class.getName(), deployment);

        try {
            // 初始化
            dispatcher.init(new SimpleServletConfig(servletContext));
        } catch (ServletException e) {
            throw new RpcException(e);
        }
    }

    @Override
    public void close() {
        httpServer.close();
    }

    @Override
    protected ResteasyDeployment getDeployment() {
        return deployment;
    }

    /**
     * 该内部类是服务请求的处理器
     */
    private class RestHandler implements HttpHandler {

        @Override
        public void handle(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
            // 设置远程地址
            RpcContext.getContext().setRemoteAddress(request.getRemoteAddr(), request.getRemotePort());
            // 请求相关的服务
            dispatcher.service(request, response);
        }
    }

    /**
     * 该内部类是配置类
     */
    private static class SimpleServletConfig implements ServletConfig {
        // ServletContext对象
        private final ServletContext servletContext;

        public SimpleServletConfig(ServletContext servletContext) {
            this.servletContext = servletContext;
        }

        @Override
        public String getServletName() {
            return "DispatcherServlet";
        }

        @Override
        public ServletContext getServletContext() {
            return servletContext;
        }

        @Override
        public String getInitParameter(String s) {
            return null;
        }

        @Override
        public Enumeration getInitParameterNames() {
            return new Enumeration() {
                @Override
                public boolean hasMoreElements() {
                    return false;
                }

                @Override
                public Object nextElement() {
                    return null;
                }
            };
        }
    }
}
