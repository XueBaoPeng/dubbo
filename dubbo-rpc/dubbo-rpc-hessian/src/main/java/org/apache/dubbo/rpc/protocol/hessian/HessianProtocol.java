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
package org.apache.dubbo.rpc.protocol.hessian;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.remoting.RemotingServer;
import org.apache.dubbo.remoting.http.HttpBinder;
import org.apache.dubbo.remoting.http.HttpHandler;
import org.apache.dubbo.rpc.ProtocolServer;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.protocol.AbstractProxyProtocol;
import org.apache.dubbo.rpc.service.GenericService;
import org.apache.dubbo.rpc.support.ProtocolUtils;

import com.caucho.hessian.HessianException;
import com.caucho.hessian.client.HessianConnectionException;
import com.caucho.hessian.client.HessianConnectionFactory;
import com.caucho.hessian.client.HessianProxyFactory;
import com.caucho.hessian.io.HessianMethodSerializationException;
import com.caucho.hessian.server.HessianSkeleton;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_TIMEOUT;
import static org.apache.dubbo.common.constants.CommonConstants.TIMEOUT_KEY;
import static org.apache.dubbo.remoting.Constants.CLIENT_KEY;
import static org.apache.dubbo.remoting.Constants.DEFAULT_EXCHANGER;
import static org.apache.dubbo.rpc.Constants.GENERIC_KEY;
import static org.apache.dubbo.rpc.protocol.hessian.Constants.DEFAULT_HESSIAN2_REQUEST;
import static org.apache.dubbo.rpc.protocol.hessian.Constants.DEFAULT_HESSIAN_OVERLOAD_METHOD;
import static org.apache.dubbo.rpc.protocol.hessian.Constants.DEFAULT_HTTP_CLIENT;
import static org.apache.dubbo.rpc.protocol.hessian.Constants.HESSIAN2_REQUEST_KEY;
import static org.apache.dubbo.rpc.protocol.hessian.Constants.HESSIAN_OVERLOAD_METHOD_KEY;

/**
 * http rpc support.
 * 该类继承了AbstractProxyProtocol类，是hessian协议的实现类。其中实现类基于hessian协议的服务引用、服务暴露等方法。
 */
public class HessianProtocol extends AbstractProxyProtocol {
    /**
     * HessianSkeleto　集合
     * key为服务名
     */
    private final Map<String, HessianSkeleton> skeletonMap = new ConcurrentHashMap<String, HessianSkeleton>();

    /**
     * HttpBinder对象，默认是jetty实现
     */
    private HttpBinder httpBinder;

    public HessianProtocol() {
        super(HessianException.class);
    }

    public void setHttpBinder(HttpBinder httpBinder) {
        this.httpBinder = httpBinder;
    }

    @Override
    public int getDefaultPort() {
        return 80;
    }

    /**
     * 该方法是服务暴露的主要逻辑实现
     * @param impl
     * @param type
     * @param url
     * @param <T>
     * @return
     * @throws RpcException
     */
    @Override
    protected <T> Runnable doExport(T impl, Class<T> type, URL url) throws RpcException {
        // 获得ip地址
        String addr = getAddr(url);
        // 获得http服务器对象
        ProtocolServer protocolServer = serverMap.get(addr);
        // 如果为空，则重新创建一个server，然后放入集合
        if (protocolServer == null) {
            RemotingServer remotingServer = httpBinder.bind(url, new HessianHandler());
            serverMap.put(addr, new ProxyProtocolServer(remotingServer));
        }
        // 获得服务path
        final String path = url.getAbsolutePath();
        // 创建Hessian服务端对象
        final HessianSkeleton skeleton = new HessianSkeleton(impl, type);
        // 加入集合
        skeletonMap.put(path, skeleton);

        // 获得通用的path
        final String genericPath = path + "/" + GENERIC_KEY;
        // 加入集合
        skeletonMap.put(genericPath, new HessianSkeleton(impl, GenericService.class));
        // 返回一个线程
        return new Runnable() {
            @Override
            public void run() {
                skeletonMap.remove(path);
                skeletonMap.remove(genericPath);
            }
        };
    }

    /**
     * 该方法是服务引用的主要逻辑实现，根据客户端配置，来选择标准 Hessian 接口调用还是Dubbo 的 Hessian 协议调用。
     * @param serviceType
     * @param url
     * @param <T>
     * @return
     * @throws RpcException
     */
    @Override
    @SuppressWarnings("unchecked")
    protected <T> T doRefer(Class<T> serviceType, URL url) throws RpcException {
        // 获得泛化的参数
        String generic = url.getParameter(GENERIC_KEY);
        // 是否是泛化调用
        boolean isGeneric = ProtocolUtils.isGeneric(generic) || serviceType.equals(GenericService.class);
        // 如果是泛化调用。则设置泛化的path和附加值
        if (isGeneric) {
            RpcContext.getContext().setAttachment(GENERIC_KEY, generic);
            url = url.setPath(url.getPath() + "/" + GENERIC_KEY);
        }
        // 创建代理工厂
        HessianProxyFactory hessianProxyFactory = new HessianProxyFactory();
        // 是否是Hessian2的请求 默认为否
        boolean isHessian2Request = url.getParameter(HESSIAN2_REQUEST_KEY, DEFAULT_HESSIAN2_REQUEST);
        // 设置是否应使用Hessian协议的版本2来解析请求
        hessianProxyFactory.setHessian2Request(isHessian2Request);
        // 是否应为远程调用启用重载方法，默认为否
        boolean isOverloadEnabled = url.getParameter(HESSIAN_OVERLOAD_METHOD_KEY, DEFAULT_HESSIAN_OVERLOAD_METHOD);
        // 设置是否应为远程调用启用重载方法。
        hessianProxyFactory.setOverloadEnabled(isOverloadEnabled);
        // 获得client实现方式，默认为jdk
        String client = url.getParameter(CLIENT_KEY, DEFAULT_HTTP_CLIENT);
        if ("httpclient".equals(client)) {
            // 用http来创建
            HessianConnectionFactory factory = new HttpClientConnectionFactory();
            factory.setHessianProxyFactory(hessianProxyFactory);
            hessianProxyFactory.setConnectionFactory(factory);
        } else if (client != null && client.length() > 0 && !DEFAULT_HTTP_CLIENT.equals(client)) {
            // 抛出不支持的协议异常
            throw new IllegalStateException("Unsupported http protocol client=\"" + client + "\"!");
        } else {
            // 创建一个HessianConnectionFactory对象
            HessianConnectionFactory factory = new DubboHessianURLConnectionFactory();
            // 设置代理工厂
            factory.setHessianProxyFactory(hessianProxyFactory);
            // 设置工厂
            hessianProxyFactory.setConnectionFactory(factory);
        }
        // 获得超时时间
        int timeout = url.getParameter(TIMEOUT_KEY, DEFAULT_TIMEOUT);
        // 设置超时时间
        hessianProxyFactory.setConnectTimeout(timeout);
        hessianProxyFactory.setReadTimeout(timeout);
        // 创建代理
        return (T) hessianProxyFactory.create(serviceType, url.setProtocol("http").toJavaURL(), Thread.currentThread().getContextClassLoader());
    }

    @Override
    protected int getErrorCode(Throwable e) {
        // 如果属于HessianConnectionException异常
        if (e instanceof HessianConnectionException) {
            if (e.getCause() != null) {
                Class<?> cls = e.getCause().getClass();
                // 如果属于超时异常，则返回超时异常
                if (SocketTimeoutException.class.equals(cls)) {
                    return RpcException.TIMEOUT_EXCEPTION;
                }
            }
            // 否则返回网络异常
            return RpcException.NETWORK_EXCEPTION;
        } else if (e instanceof HessianMethodSerializationException) {
            // 序列化异常
            return RpcException.SERIALIZATION_EXCEPTION;
        }
        return super.getErrorCode(e);
    }

    @Override
    public void destroy() {
        super.destroy();
        for (String key : new ArrayList<String>(serverMap.keySet())) {
            ProtocolServer protocolServer = serverMap.remove(key);
            if (protocolServer != null) {
                try {
                    if (logger.isInfoEnabled()) {
                        logger.info("Close hessian server " + protocolServer.getUrl());
                    }
                    protocolServer.close();
                } catch (Throwable t) {
                    logger.warn(t.getMessage(), t);
                }
            }
        }
    }

    private class HessianHandler implements HttpHandler {

        @Override
        public void handle(HttpServletRequest request, HttpServletResponse response)
                throws IOException, ServletException {
            // 获得请求的uri
            String uri = request.getRequestURI();
            // 获得对应的HessianSkeleton对象
            HessianSkeleton skeleton = skeletonMap.get(uri);
            // 如果如果不是post方法
            if (!"POST".equalsIgnoreCase(request.getMethod())) {
                // 返回状态设置为500
                response.setStatus(500);
            } else {
                // 设置远程地址
                RpcContext.getContext().setRemoteAddress(request.getRemoteAddr(), request.getRemotePort());
                // 获得请求头内容
                Enumeration<String> enumeration = request.getHeaderNames();
                // 遍历请求头内容
                while (enumeration.hasMoreElements()) {
                    String key = enumeration.nextElement();
                    // 如果key开头是deader，则把附加值取出来放入上下文
                    if (key.startsWith(DEFAULT_EXCHANGER)) {
                        RpcContext.getContext().setAttachment(key.substring(DEFAULT_EXCHANGER.length()),
                                request.getHeader(key));
                    }
                }

                try {
                    // 执行下一个
                    skeleton.invoke(request.getInputStream(), response.getOutputStream());
                } catch (Throwable e) {
                    throw new ServletException(e);
                }
            }
        }

    }

}
