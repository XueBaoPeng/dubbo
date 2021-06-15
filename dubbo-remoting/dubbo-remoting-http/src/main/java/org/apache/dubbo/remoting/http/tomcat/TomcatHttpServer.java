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
package org.apache.dubbo.remoting.http.tomcat;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.remoting.http.HttpHandler;
import org.apache.dubbo.remoting.http.servlet.DispatcherServlet;
import org.apache.dubbo.remoting.http.servlet.ServletManager;
import org.apache.dubbo.remoting.http.support.AbstractHttpServer;

import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;

import java.io.File;

import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_THREADS;
import static org.apache.dubbo.common.constants.CommonConstants.THREADS_KEY;
import static org.apache.dubbo.remoting.Constants.ACCEPTS_KEY;

//该类是基于Tomcat来实现服务器的实现类，它继承了AbstractHttpServer。
public class TomcatHttpServer extends AbstractHttpServer {

    private static final Logger logger = LoggerFactory.getLogger(TomcatHttpServer.class);

    //内嵌的tomcat对象
    private final Tomcat tomcat;

    //url对象
    private final URL url;

    public TomcatHttpServer(URL url, final HttpHandler handler) {
        super(url, handler);

        this.url = url;
        // 添加处理器
        DispatcherServlet.addHttpHandler(url.getPort(), handler);
        // 获得java.io.tmpdir的绝对路径目录
        String baseDir = new File(System.getProperty("java.io.tmpdir")).getAbsolutePath();
        // 创建内嵌的tomcat对象
        tomcat = new Tomcat();

        Connector connector = tomcat.getConnector();
        connector.setPort(url.getPort());
        // 给默认的http连接器。设置最大线程数
        connector.setProperty("maxThreads", String.valueOf(url.getParameter(THREADS_KEY, DEFAULT_THREADS)));
        // 设置最大的连接数
        connector.setProperty("maxConnections", String.valueOf(url.getParameter(ACCEPTS_KEY, -1)));
        // 设置URL编码格式
        connector.setProperty("URIEncoding", "UTF-8");
        // 设置连接超时事件为60s
        connector.setProperty("connectionTimeout", "60000");
        // 设置最大长连接个数为不限制个数
        connector.setProperty("maxKeepAliveRequests", "-1");

        // 设置根目录
        tomcat.setBaseDir(baseDir);
        // 设置端口号
        tomcat.setPort(url.getPort());
        // 添加上下文
        Context context = tomcat.addContext("/", baseDir);
        // 添加servlet，把servlet添加到context
        Tomcat.addServlet(context, "dispatcher", new DispatcherServlet());
        // Issue : https://github.com/apache/dubbo/issues/6418
        // addServletMapping method will be removed since Tomcat 9
        // context.addServletMapping("/*", "dispatcher");
        // 添加servlet映射
        context.addServletMappingDecoded("/*", "dispatcher");
        // 添加servlet上下文
        ServletManager.getInstance().addServletContext(url.getPort(), context.getServletContext());

        // tell tomcat to fail on startup failures.
        System.setProperty("org.apache.catalina.startup.EXIT_ON_INIT_FAILURE", "true");

        try {
            // 开启tomcat
            tomcat.start();
        } catch (LifecycleException e) {
            throw new IllegalStateException("Failed to start tomcat server at " + url.getAddress(), e);
        }
    }

    @Override
    public void close() {
        super.close();

        // 移除相关的servlet上下文
        ServletManager.getInstance().removeServletContext(url.getPort());

        try {
            // 停止tomcat
            tomcat.stop();
        } catch (Exception e) {
            logger.warn(e.getMessage(), e);
        }
    }
}
