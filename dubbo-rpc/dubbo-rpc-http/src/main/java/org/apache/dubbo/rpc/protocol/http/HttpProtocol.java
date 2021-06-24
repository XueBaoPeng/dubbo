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
package org.apache.dubbo.rpc.protocol.http;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.remoting.RemotingServer;
import org.apache.dubbo.remoting.http.HttpBinder;
import org.apache.dubbo.remoting.http.HttpHandler;
import org.apache.dubbo.rpc.ProtocolServer;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.protocol.AbstractProxyProtocol;

import com.googlecode.jsonrpc4j.HttpException;
import com.googlecode.jsonrpc4j.JsonRpcClientException;
import com.googlecode.jsonrpc4j.JsonRpcServer;
import com.googlecode.jsonrpc4j.spring.JsonProxyFactoryBean;
import org.apache.dubbo.rpc.service.GenericService;
import org.apache.dubbo.rpc.support.ProtocolUtils;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.remoting.RemoteAccessException;
import org.springframework.remoting.support.RemoteInvocation;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.apache.dubbo.rpc.Constants.GENERIC_KEY;

//该类是http实现的核心，跟我在《dubbo源码解析（二十五）远程调用——hessian协议》中讲到的HessianProtocol实现有很多地方相似。
public class HttpProtocol extends AbstractProxyProtocol {
    public static final String ACCESS_CONTROL_ALLOW_ORIGIN_HEADER = "Access-Control-Allow-Origin";
    public static final String ACCESS_CONTROL_ALLOW_METHODS_HEADER = "Access-Control-Allow-Methods";
    public static final String ACCESS_CONTROL_ALLOW_HEADERS_HEADER = "Access-Control-Allow-Headers";
    /**
     * http服务器集合
     */
    private final Map<String, JsonRpcServer> skeletonMap = new ConcurrentHashMap<>();
    /**
     * HttpBinder对象
     */
    private HttpBinder httpBinder;

    public HttpProtocol() {
        super(HttpException.class, JsonRpcClientException.class);
    }

    public void setHttpBinder(HttpBinder httpBinder) {
        this.httpBinder = httpBinder;
    }

    /**
     * 默认的端口号
     */
    @Override
    public int getDefaultPort() {
        return 80;
    }

    private class InternalHandler implements HttpHandler {

        private boolean cors;

        public InternalHandler(boolean cors) {
            this.cors = cors;
        }

        @Override
        public void handle(HttpServletRequest request, HttpServletResponse response)
                throws ServletException {
            // 获得请求uri
            String uri = request.getRequestURI();
            // 获得服务暴露者HttpInvokerServiceExporter对象
            JsonRpcServer skeleton = skeletonMap.get(uri);
            if (cors) {
                response.setHeader(ACCESS_CONTROL_ALLOW_ORIGIN_HEADER, "*");
                response.setHeader(ACCESS_CONTROL_ALLOW_METHODS_HEADER, "POST");
                response.setHeader(ACCESS_CONTROL_ALLOW_HEADERS_HEADER, "*");
            }
            if (request.getMethod().equalsIgnoreCase("OPTIONS")) {
                response.setStatus(200);
            } else if (request.getMethod().equalsIgnoreCase("POST")) {
                // 远程地址放到上下文
                RpcContext.getContext().setRemoteAddress(request.getRemoteAddr(), request.getRemotePort());
                try {
                    // 调用下一个调用
                    skeleton.handle(request.getInputStream(), response.getOutputStream());
                } catch (Throwable e) {
                    throw new ServletException(e);
                }
            } else {
                // 如果不是post，则返回码设置500
                response.setStatus(500);
            }
        }

    }

    @Override
    protected <T> Runnable doExport(final T impl, Class<T> type, URL url) throws RpcException {
        // 获得http服务器
        String addr = getAddr(url);
        // 获得http服务器
        ProtocolServer protocolServer = serverMap.get(addr);
        // 如果服务器为空，则重新创建服务器，并且加入到集合
        if (protocolServer == null) {
            RemotingServer remotingServer = httpBinder.bind(url, new InternalHandler(url.getParameter("cors", false)));
            serverMap.put(addr, new ProxyProtocolServer(remotingServer));
        }
        // 获得服务path
        final String path = url.getAbsolutePath();
        // 通用path
        final String genericPath = path + "/" + GENERIC_KEY;
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        JsonRpcServer skeleton = new JsonRpcServer(mapper, impl, type);
        // 添加泛化的服务调用
        JsonRpcServer genericServer = new JsonRpcServer(mapper, impl, GenericService.class);
        skeletonMap.put(path, skeleton);
        // 加入集合
        skeletonMap.put(genericPath, genericServer);
        return () -> {
            skeletonMap.remove(path);
            skeletonMap.remove(genericPath);
        };
    }

    /**
     * 该方法是服务引用的方法，其中根据url配置simple还是commons来选择创建连接池的方式。
     * 其中的区别就是SimpleHttpInvokerRequestExecutor使用的是JDK HttpClient，
     * HttpComponentsHttpInvokerRequestExecutor 使用的是Apache HttpClient。
     * @param serviceType
     * @param url
     * @param <T>
     * @return
     * @throws RpcException
     */
    @SuppressWarnings("unchecked")
    @Override
    protected <T> T doRefer(final Class<T> serviceType, URL url) throws RpcException {
        // 获得泛化配置
        final String generic = url.getParameter(GENERIC_KEY);
        // 是否为泛化调用
        final boolean isGeneric = ProtocolUtils.isGeneric(generic) || serviceType.equals(GenericService.class);
        // 创建HttpInvokerProxyFactoryBean
        JsonProxyFactoryBean jsonProxyFactoryBean = new JsonProxyFactoryBean();
        JsonRpcProxyFactoryBean jsonRpcProxyFactoryBean = new JsonRpcProxyFactoryBean(jsonProxyFactoryBean);
        // 设置RemoteInvocation的工厂类
        jsonRpcProxyFactoryBean.setRemoteInvocationFactory((methodInvocation) -> {
            /**
             * 为给定的AOP方法调用创建一个新的RemoteInvocation对象。
             * @param methodInvocation
             * @return
             */
            // 新建一个HttpRemoteInvocation
            RemoteInvocation invocation = new JsonRemoteInvocation(methodInvocation);
            // 如果是泛化调用
            if (isGeneric) {
                // 设置标志
                invocation.addAttribute(GENERIC_KEY, generic);
            }
            return invocation;
        });
        // 获得identity message
        String key = url.setProtocol("http").toIdentityString();
        // 如果是泛化调用
        if (isGeneric) {
            key = key + "/" + GENERIC_KEY;
        }
        // 设置服务url
        jsonRpcProxyFactoryBean.setServiceUrl(key);
        // 设置服务接口
        jsonRpcProxyFactoryBean.setServiceInterface(serviceType);
        // 获得客户端参数
        jsonProxyFactoryBean.afterPropertiesSet();
        return (T) jsonProxyFactoryBean.getObject();
    }

    protected int getErrorCode(Throwable e) {
        if (e instanceof RemoteAccessException) {
            e = e.getCause();
        }
        if (e != null) {
            Class<?> cls = e.getClass();
            if (SocketTimeoutException.class.equals(cls)) {
                // 返回超时异常
                return RpcException.TIMEOUT_EXCEPTION;
            } else if (IOException.class.isAssignableFrom(cls)) {
                // 返回网络异常
                return RpcException.NETWORK_EXCEPTION;
            } else if (ClassNotFoundException.class.isAssignableFrom(cls)) {
                // 返回序列化异常
                return RpcException.SERIALIZATION_EXCEPTION;
            }

            if (e instanceof HttpProtocolErrorCode) {
                return ((HttpProtocolErrorCode) e).getErrorCode();
            }
        }
        return super.getErrorCode(e);
    }

    @Override
    public void destroy() {
        super.destroy();
        for (String key : new ArrayList<>(serverMap.keySet())) {
            ProtocolServer server = serverMap.remove(key);
            if (server != null) {
                try {
                    if (logger.isInfoEnabled()) {
                        logger.info("Close jsonrpc server " + server.getUrl());
                    }
                    server.close();
                } catch (Throwable t) {
                    logger.warn(t.getMessage(), t);
                }
            }
        }
    }


}
