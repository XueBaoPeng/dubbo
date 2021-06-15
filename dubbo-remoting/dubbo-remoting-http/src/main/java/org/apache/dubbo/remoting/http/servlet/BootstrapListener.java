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
package org.apache.dubbo.remoting.http.servlet;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

/**
 * This class must be defined before something like spring's ContextLoaderListener in web.xml
 * 该类实现了ServletContextListener，是 启动监听器，当context创建和销毁的时候对ServletContext做处理
 * 。不过需要配置BootstrapListener到web.xml，通过这样的方式，让外部的 ServletContext 对象，添加到 ServletManager 中
 */
public class BootstrapListener implements ServletContextListener {

    @Override
    public void contextInitialized(ServletContextEvent servletContextEvent) {
        // context创建的时候，把ServletContext添加到ServletManager
        ServletManager.getInstance().addServletContext(ServletManager.EXTERNAL_SERVER_PORT, servletContextEvent.getServletContext());
    }

    @Override
    public void contextDestroyed(ServletContextEvent servletContextEvent) {
        // context销毁到时候，把servletContextEvent移除
        ServletManager.getInstance().removeServletContext(ServletManager.EXTERNAL_SERVER_PORT);
    }
}
