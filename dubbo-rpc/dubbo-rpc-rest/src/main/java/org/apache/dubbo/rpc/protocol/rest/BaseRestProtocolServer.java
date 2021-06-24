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
import org.apache.dubbo.common.utils.StringUtils;

import org.jboss.resteasy.spi.ResteasyDeployment;

import static org.apache.dubbo.common.constants.CommonConstants.COMMA_SPLIT_PATTERN;
import static org.apache.dubbo.rpc.protocol.rest.Constants.EXTENSION_KEY;

/**
 * 该类实现了RestServer接口，是rest服务的抽象类，把getDeployment和doStart方法进行抽象，让子类专注于中这两个方法的实现。
 */
public abstract class BaseRestProtocolServer implements RestProtocolServer {

    private String address;

    @Override
    public void start(URL url) {
        // 支持两种 Content-Type
        getDeployment().getMediaTypeMappings().put("json", "application/json");
        getDeployment().getMediaTypeMappings().put("xml", "text/xml");
//        server.getDeployment().getMediaTypeMappings().put("xml", "application/xml");
        // 添加拦截器
        getDeployment().getProviderClasses().add(RpcContextFilter.class.getName());
        // TODO users can override this mapper, but we just rely on the current priority strategy of resteasy\
        // 异常类映射
        getDeployment().getProviderClasses().add(RpcExceptionMapper.class.getName());
        // 添加需要加载的类
        loadProviders(url.getParameter(EXTENSION_KEY, ""));
        // 开启服务器
        doStart(url);
    }

    @Override
    public void deploy(Class resourceDef, Object resourceInstance, String contextPath) {
        // 如果
        if (StringUtils.isEmpty(contextPath)) {
            // 添加自定义资源实现端点，部署服务器
            getDeployment().getRegistry().addResourceFactory(new DubboResourceFactory(resourceInstance, resourceDef));
        } else {
            // 添加自定义资源实现端点。指定contextPath
            getDeployment().getRegistry().addResourceFactory(new DubboResourceFactory(resourceInstance, resourceDef), contextPath);
        }
    }

    @Override
    public void undeploy(Class resourceDef) {
        // 取消服务器部署
        getDeployment().getRegistry().removeRegistrations(resourceDef);
    }

    @Override
    public String getAddress() {
        return address;
    }

    @Override
    public void setAddress(String address) {
        this.address = address;
    }

    /**
     * 该方法是把类都加入到ResteasyDeployment的providerClasses中，加入各类组件。
     * @param value
     */
    protected void loadProviders(String value) {
        for (String clazz : COMMA_SPLIT_PATTERN.split(value)) {
            if (!StringUtils.isEmpty(clazz)) {
                getDeployment().getProviderClasses().add(clazz.trim());
            }
        }
    }

    protected abstract ResteasyDeployment getDeployment();

    protected abstract void doStart(URL url);
}
