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
package org.apache.dubbo.rpc.protocol.rmi;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.protocol.AbstractProxyProtocol;
import org.apache.dubbo.rpc.service.GenericService;
import org.apache.dubbo.rpc.support.ProtocolUtils;

import org.springframework.remoting.RemoteAccessException;
import org.springframework.remoting.rmi.RmiProxyFactoryBean;
import org.springframework.remoting.rmi.RmiServiceExporter;
import org.springframework.remoting.support.RemoteInvocation;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.rmi.RemoteException;

import static org.apache.dubbo.common.Version.isRelease263OrHigher;
import static org.apache.dubbo.common.Version.isRelease270OrHigher;
import static org.apache.dubbo.common.constants.CommonConstants.DUBBO_VERSION_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.RELEASE_KEY;
import static org.apache.dubbo.rpc.Constants.GENERIC_KEY;

/**
 * RmiProtocol.
 * 该类继承了AbstractProxyProtocol类，是rmi协议实现的核心，跟其他协议一样，也实现了自己的服务暴露和服务引用方法。
 */
public class RmiProtocol extends AbstractProxyProtocol{

    public static final int DEFAULT_PORT = 1099;

    public RmiProtocol() {
        super(RemoteAccessException.class, RemoteException.class);
    }

    @Override
    public int getDefaultPort() {
        return DEFAULT_PORT;
    }

    /**
     * 该方法是服务暴露的逻辑实现
     * @param impl
     * @param type
     * @param url
     * @param <T>
     * @return
     * @throws RpcException
     */
    @Override
    protected <T> Runnable doExport(final T impl, Class<T> type, URL url) throws RpcException {
        // rmi暴露者
        RmiServiceExporter rmiServiceExporter = createExporter(impl, type, url, false);
        RmiServiceExporter genericServiceExporter = createExporter(impl, GenericService.class, url, true);
        return new Runnable() {
            @Override
            public void run() {
                try {
                    // 销毁
                    rmiServiceExporter.destroy();
                    genericServiceExporter.destroy();
                } catch (Throwable e) {
                    logger.warn(e.getMessage(), e);
                }
            }
        };
    }

    @Override
    @SuppressWarnings("unchecked")
    protected <T> T doRefer(final Class<T> serviceType, final URL url) throws RpcException {
        // FactoryBean对于RMI代理，支持传统的RMI服务和RMI调用者，创建RmiProxyFactoryBean对象
        final RmiProxyFactoryBean rmiProxyFactoryBean = new RmiProxyFactoryBean();
        final String generic = url.getParameter(GENERIC_KEY);
        final boolean isGeneric = ProtocolUtils.isGeneric(generic) || serviceType.equals(GenericService.class);
        /*
          RMI needs extra parameter since it uses customized remote invocation object

          The customized RemoteInvocation was firstly introduced in v2.6.3; The package was renamed to 'org.apache.*' since v2.7.0
          Considering the above two conditions, we need to check before sending customized RemoteInvocation:
          1. if the provider version is v2.7.0 or higher, send 'org.apache.dubbo.rpc.protocol.rmi.RmiRemoteInvocation'.
          2. if the provider version is v2.6.3 or higher, send 'com.alibaba.dubbo.rpc.protocol.rmi.RmiRemoteInvocation'.
          3. if the provider version is lower than v2.6.3, does not use customized RemoteInvocation.
         */
        if (isRelease270OrHigher(url.getParameter(RELEASE_KEY))) {
            // 设置RemoteInvocationFactory以用于此访问器
            rmiProxyFactoryBean.setRemoteInvocationFactory(methodInvocation -> {
                // 自定义调用工厂可以向调用添加更多上下文信息
                RemoteInvocation invocation = new RmiRemoteInvocation(methodInvocation);
                if (isGeneric) {
                    invocation.addAttribute(GENERIC_KEY, generic);
                }
                return invocation;
            });
        } else if (isRelease263OrHigher(url.getParameter(DUBBO_VERSION_KEY))) {
            rmiProxyFactoryBean.setRemoteInvocationFactory(methodInvocation -> {
                RemoteInvocation invocation = new com.alibaba.dubbo.rpc.protocol.rmi.RmiRemoteInvocation(methodInvocation);
                if (isGeneric) {
                    invocation.addAttribute(GENERIC_KEY, generic);
                }
                return invocation;
            });
        }
        String serviceUrl = url.toIdentityString();
        if (isGeneric) {
            serviceUrl = serviceUrl + "/" + GENERIC_KEY;
        }
        // 设置此远程访问者的目标服务的URL。URL必须与特定远程处理提供程序的规则兼容。
        rmiProxyFactoryBean.setServiceUrl(serviceUrl);
        // 设置要访问的服务的接口。界面必须适合特定的服务和远程处理策略
        rmiProxyFactoryBean.setServiceInterface(serviceType);
        // 设置是否在找到RMI存根后缓存它
        rmiProxyFactoryBean.setCacheStub(true);
        // 设置是否在启动时查找RMI存根
        rmiProxyFactoryBean.setLookupStubOnStartup(true);
        // 设置是否在连接失败时刷新RMI存根
        rmiProxyFactoryBean.setRefreshStubOnConnectFailure(true);
        // // 初始化bean的时候执行
        rmiProxyFactoryBean.afterPropertiesSet();
        return (T) rmiProxyFactoryBean.getObject();
    }

    @Override
    protected int getErrorCode(Throwable e) {
        if (e instanceof RemoteAccessException) {
            e = e.getCause();
        }
        if (e != null && e.getCause() != null) {
            Class<?> cls = e.getCause().getClass();
            if (SocketTimeoutException.class.equals(cls)) {
                return RpcException.TIMEOUT_EXCEPTION;
            } else if (IOException.class.isAssignableFrom(cls)) {
                return RpcException.NETWORK_EXCEPTION;
            } else if (ClassNotFoundException.class.isAssignableFrom(cls)) {
                return RpcException.SERIALIZATION_EXCEPTION;
            }
        }
        return super.getErrorCode(e);
    }

    private <T> RmiServiceExporter createExporter(T impl, Class<?> type, URL url, boolean isGeneric) {
        final RmiServiceExporter rmiServiceExporter = new RmiServiceExporter();
        // 设置端口
        rmiServiceExporter.setRegistryPort(url.getPort());
        if (isGeneric) {
            // 设置服务名称
            rmiServiceExporter.setServiceName(url.getPath() + "/" + GENERIC_KEY);
        } else {
            // 设置服务名称
            rmiServiceExporter.setServiceName(url.getPath());
        }
        // 设置接口
        rmiServiceExporter.setServiceInterface(type);
        // 设置服务实现
        rmiServiceExporter.setService(impl);
        try {
            // 初始化bean的时候执行
            rmiServiceExporter.afterPropertiesSet();
        } catch (RemoteException e) {
            throw new RpcException(e.getMessage(), e);
        }
        return rmiServiceExporter;
    }

}
