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
package org.apache.dubbo.rpc.protocol.dubbo;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.bytecode.Wrapper;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ConcurrentHashSet;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.Constants;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.rpc.Exporter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.ProxyFactory;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.protocol.AsyncToSyncInvoker;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.apache.dubbo.common.constants.CommonConstants.CALLBACK_INSTANCES_LIMIT_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_CALLBACK_INSTANCES;
import static org.apache.dubbo.common.constants.CommonConstants.GROUP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.INTERFACE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.METHODS_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.VERSION_KEY;
import static org.apache.dubbo.rpc.Constants.IS_SERVER_KEY;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.CALLBACK_SERVICE_KEY;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.CALLBACK_SERVICE_PROXY_KEY;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.CHANNEL_CALLBACK_KEY;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.IS_CALLBACK_SERVICE;

/**
 * callback service helper
 * 该类是针对回调服务的编解码器
 */
class CallbackServiceCodec {
    private static final Logger logger = LoggerFactory.getLogger(CallbackServiceCodec.class);
    /**
     * 代理工厂
     */
    private static final ProxyFactory PROXY_FACTORY = ExtensionLoader.getExtensionLoader(ProxyFactory.class).getAdaptiveExtension();
    /**
     * dubbo协议
     */
    private static final DubboProtocol PROTOCOL = DubboProtocol.getDubboProtocol();
    /**
     * 回调的标志
     */
    private static final byte CALLBACK_NONE = 0x0;
    /**
     * 回调的创建标志
     */
    private static final byte CALLBACK_CREATE = 0x1;
    /**
     * 回调的销毁标志
     */
    private static final byte CALLBACK_DESTROY = 0x2;
    /**
     * 回调参数key
     */
    private static final String INV_ATT_CALLBACK_KEY = "sys_callback_arg-";

    /**
     * 该方法是根据url携带的参数设置回调的标志，以供执行不同的编解码逻辑。
     * @param url
     * @param protocolServiceKey
     * @param methodName
     * @param argIndex
     * @return
     */
    private static byte isCallBack(URL url, String protocolServiceKey, String methodName, int argIndex) {
        // parameter callback rule: method-name.parameter-index(starting from 0).callback
        // 参数的规则：ethod-name.parameter-index(starting from 0).callback
        byte isCallback = CALLBACK_NONE;
        if (url != null && url.hasServiceMethodParameter(protocolServiceKey, methodName)) {
            // 获得回调的值
            String callback = url.getServiceParameter(protocolServiceKey, methodName + "." + argIndex + ".callback");
            if (callback != null) {
                // 如果为true，则设置为创建标志
                if ("true".equalsIgnoreCase(callback)) {
                    isCallback = CALLBACK_CREATE;
                    // 如果为false，则设置为销毁标志
                } else if ("false".equalsIgnoreCase(callback)) {
                    isCallback = CALLBACK_DESTROY;
                }
            }
        }
        return isCallback;
    }

    /**
     * export or unexport callback service on client side
     * 该方法是在客户端侧暴露服务和取消暴露服务。
     * @param channel
     * @param url
     * @param clazz
     * @param inst
     * @param export
     * @throws IOException
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String exportOrUnexportCallbackService(Channel channel, URL url, Class clazz, Object inst, Boolean export) throws IOException {
        // 返回对象的hashCode
        int instid = System.identityHashCode(inst);

        Map<String, String> params = new HashMap<>(3);
        // no need to new client again
        // 设置不是服务端标志为否
        params.put(IS_SERVER_KEY, Boolean.FALSE.toString());
        // mark it's a callback, for troubleshooting
        // 设置是回调服务标志为true
        params.put(IS_CALLBACK_SERVICE, Boolean.TRUE.toString());
        String group = (url == null ? null : url.getParameter(GROUP_KEY));
        if (group != null && group.length() > 0) {
            // 设置是消费侧还是提供侧
            params.put(GROUP_KEY, group);
        }
        // add method, for verifying against method, automatic fallback (see dubbo protocol)
        // 添加方法，在dubbo的协议里面用到
        params.put(METHODS_KEY, StringUtils.join(Wrapper.getWrapper(clazz).getDeclaredMethodNames(), ","));

        Map<String, String> tmpMap = new HashMap<>();
        if (url != null) {
            Map<String, String> parameters = url.getParameters();
            if (parameters != null && !parameters.isEmpty()) {
                tmpMap.putAll(parameters);
            }
        }
        tmpMap.putAll(params);
        // 移除版本信息
        tmpMap.remove(VERSION_KEY);// doesn't need to distinguish version for callback
        tmpMap.remove(Constants.BIND_PORT_KEY); //callback doesn't needs bind.port
        // 设置接口名
        tmpMap.put(INTERFACE_KEY, clazz.getName());
        // 创建服务暴露的url
        URL exportUrl = new URL(DubboProtocol.NAME, channel.getLocalAddress().getAddress().getHostAddress(), channel.getLocalAddress().getPort(), clazz.getName() + "." + instid, tmpMap);

        // no need to generate multiple exporters for different channel in the same JVM, cache key cannot collide.
        // 获得缓存的key
        String cacheKey = getClientSideCallbackServiceCacheKey(instid);
        // 获得计数的key
        String countKey = getClientSideCountKey(clazz.getName());
        // 如果是暴露服务
        if (export) {
            // one channel can have multiple callback instances, no need to re-export for different instance.
            if (!channel.hasAttribute(cacheKey)) {
                if (!isInstancesOverLimit(channel, url, clazz.getName(), instid, false)) {
                    ApplicationModel.getServiceRepository().registerService(clazz);
                    // 获得代理对象
                    Invoker<?> invoker = PROXY_FACTORY.getInvoker(inst, clazz, exportUrl);
                    // should destroy resource?
                    // 暴露服务
                    Exporter<?> exporter = PROTOCOL.export(invoker);
                    // this is used for tracing if instid has published service or not.
                    // 放到通道
                    channel.setAttribute(cacheKey, exporter);
                    logger.info("Export a callback service :" + exportUrl + ", on " + channel + ", url is: " + url);
                    // 计数器加1
                    increaseInstanceCount(channel, countKey);
                }
            }
        } else {
            // 如果通道内已经有该服务的缓存
            if (channel.hasAttribute(cacheKey)) {
                // 则获得该暴露者
                Exporter<?> exporter = (Exporter<?>) channel.getAttribute(cacheKey);
                // 取消暴露
                exporter.unexport();
                // 移除该缓存
                channel.removeAttribute(cacheKey);
                // 计数器减1
                decreaseInstanceCount(channel, countKey);
            }
        }
        return String.valueOf(instid);
    }

    /**
     * refer or destroy callback service on server side
     * 该方法是在服务端侧进行服务引用或者销毁回调服务。
     * @param url
     */
    @SuppressWarnings("unchecked")
    private static Object referOrDestroyCallbackService(Channel channel, URL url, Class<?> clazz, Invocation inv, int instid, boolean isRefer) {
        Object proxy;
        // 获得服务调用的缓存key
        String invokerCacheKey = getServerSideCallbackInvokerCacheKey(channel, clazz.getName(), instid);
        // 获得代理缓存key
        String proxyCacheKey = getServerSideCallbackServiceCacheKey(channel, clazz.getName(), instid);
        // 从通道内获得代理对象
        proxy = channel.getAttribute(proxyCacheKey);
        // 获得计数器key
        String countkey = getServerSideCountKey(channel, clazz.getName());
        // 如果是服务引用
        if (isRefer) {
            // 如果代理对象为空
            if (proxy == null) {
                // 获得服务引用的url
                URL referurl = URL.valueOf("callback://" + url.getAddress() + "/" + clazz.getName() + "?" + INTERFACE_KEY + "=" + clazz.getName());
                referurl = referurl.addParametersIfAbsent(url.getParameters()).removeParameter(METHODS_KEY);
                if (!isInstancesOverLimit(channel, referurl, clazz.getName(), instid, true)) {
                    ApplicationModel.getServiceRepository().registerService(clazz);
                    @SuppressWarnings("rawtypes")
                    Invoker<?> invoker = new ChannelWrappedInvoker(clazz, channel, referurl, String.valueOf(instid));
                    // 获得代理类
                    proxy = PROXY_FACTORY.getProxy(new AsyncToSyncInvoker<>(invoker));
                    // 设置代理类
                    channel.setAttribute(proxyCacheKey, proxy);
                    // 设置实体域
                    channel.setAttribute(invokerCacheKey, invoker);
                    // 计数器加1
                    increaseInstanceCount(channel, countkey);

                    //convert error fail fast .
                    //ignore concurrent problem.
                    Set<Invoker<?>> callbackInvokers = (Set<Invoker<?>>) channel.getAttribute(CHANNEL_CALLBACK_KEY);
                    if (callbackInvokers == null) {
                        // 创建回调的服务实体域集合
                        callbackInvokers = new ConcurrentHashSet<>(1);
                        // 把该实体域加入集合中
                        channel.setAttribute(CHANNEL_CALLBACK_KEY, callbackInvokers);
                    }
                    callbackInvokers.add(invoker);
                    logger.info("method " + inv.getMethodName() + " include a callback service :" + invoker.getUrl() + ", a proxy :" + invoker + " has been created.");
                }
            }
        } else {
            // 销毁
            if (proxy != null) {
                Invoker<?> invoker = (Invoker<?>) channel.getAttribute(invokerCacheKey);
                try {
                    Set<Invoker<?>> callbackInvokers = (Set<Invoker<?>>) channel.getAttribute(CHANNEL_CALLBACK_KEY);
                    if (callbackInvokers != null) {
                        // 从集合中移除
                        callbackInvokers.remove(invoker);
                    }
                    // 销毁该调用
                    invoker.destroy();
                } catch (Exception e) {
                    logger.error(e.getMessage(), e);
                }
                // cancel refer, directly remove from the map
                channel.removeAttribute(proxyCacheKey);
                channel.removeAttribute(invokerCacheKey);
                decreaseInstanceCount(channel, countkey);
            }
        }
        return proxy;
    }

    private static String getClientSideCallbackServiceCacheKey(int instid) {
        return CALLBACK_SERVICE_KEY + "." + instid;
    }

    private static String getServerSideCallbackServiceCacheKey(Channel channel, String interfaceClass, int instid) {
        return CALLBACK_SERVICE_PROXY_KEY + "." + System.identityHashCode(channel) + "." + interfaceClass + "." + instid;
    }

    private static String getServerSideCallbackInvokerCacheKey(Channel channel, String interfaceClass, int instid) {
        return getServerSideCallbackServiceCacheKey(channel, interfaceClass, instid) + "." + "invoker";
    }

    private static String getClientSideCountKey(String interfaceClass) {
        return CALLBACK_SERVICE_KEY + "." + interfaceClass + ".COUNT";
    }

    private static String getServerSideCountKey(Channel channel, String interfaceClass) {
        return CALLBACK_SERVICE_PROXY_KEY + "." + System.identityHashCode(channel) + "." + interfaceClass + ".COUNT";
    }

    private static boolean isInstancesOverLimit(Channel channel, URL url, String interfaceClass, int instid, boolean isServer) {
        Integer count = (Integer) channel.getAttribute(isServer ? getServerSideCountKey(channel, interfaceClass) : getClientSideCountKey(interfaceClass));
        int limit = url.getParameter(CALLBACK_INSTANCES_LIMIT_KEY, DEFAULT_CALLBACK_INSTANCES);
        if (count != null && count >= limit) {
            //client side error
            throw new IllegalStateException("interface " + interfaceClass + " `s callback instances num exceed providers limit :" + limit
                    + " ,current num: " + (count + 1) + ". The new callback service will not work !!! you can cancle the callback service which exported before. channel :" + channel);
        } else {
            return false;
        }
    }

    private static void increaseInstanceCount(Channel channel, String countkey) {
        try {
            //ignore concurrent problem?
            Integer count = (Integer) channel.getAttribute(countkey);
            if (count == null) {
                count = 1;
            } else {
                count++;
            }
            channel.setAttribute(countkey, count);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
    }

    private static void decreaseInstanceCount(Channel channel, String countkey) {
        try {
            Integer count = (Integer) channel.getAttribute(countkey);
            if (count == null || count <= 0) {
                return;
            } else {
                count--;
            }
            channel.setAttribute(countkey, count);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
    }

    /**
     * 该方法是对会话域的信息进行编码。
     * @param channel
     * @param inv
     * @param paraIndex
     * @return
     * @throws IOException
     */
    public static Object encodeInvocationArgument(Channel channel, RpcInvocation inv, int paraIndex) throws IOException {
        // get URL directly
        // 直接获得url
        URL url = inv.getInvoker() == null ? null : inv.getInvoker().getUrl();
        // 设置回调标志
        byte callbackStatus = isCallBack(url, inv.getProtocolServiceKey(), inv.getMethodName(), paraIndex);
        // 获得参数集合
        Object[] args = inv.getArguments();
        // 获得参数类型集合
        Class<?>[] pts = inv.getParameterTypes();
        // 根据不同的回调状态来设置附加值和返回参数
        switch (callbackStatus) {
            case CallbackServiceCodec.CALLBACK_CREATE:
                inv.setAttachment(INV_ATT_CALLBACK_KEY + paraIndex, exportOrUnexportCallbackService(channel, url, pts[paraIndex], args[paraIndex], true));
                return null;
            case CallbackServiceCodec.CALLBACK_DESTROY:
                inv.setAttachment(INV_ATT_CALLBACK_KEY + paraIndex, exportOrUnexportCallbackService(channel, url, pts[paraIndex], args[paraIndex], false));
                return null;
            default:
                return args[paraIndex];
        }
    }

    /**
     * 该方法是对会话域内的信息进行解码。
     * @param channel
     * @param inv
     * @param pts
     * @param paraIndex
     * @param inObject
     * @return
     * @throws IOException
     */
    public static Object decodeInvocationArgument(Channel channel, RpcInvocation inv, Class<?>[] pts, int paraIndex, Object inObject) throws IOException {
        // if it's a callback, create proxy on client side, callback interface on client side can be invoked through channel
        // need get URL from channel and env when decode
        URL url = null;
        try {
            // 获得url
            url = DubboProtocol.getDubboProtocol().getInvoker(channel, inv).getUrl();
        } catch (RemotingException e) {
            if (logger.isInfoEnabled()) {
                logger.info(e.getMessage(), e);
            }
            return inObject;
        }
        // 获得回调状态
        byte callbackstatus = isCallBack(url, inv.getProtocolServiceKey(), inv.getMethodName(), paraIndex);
        // 根据回调状态来返回结果
        switch (callbackstatus) {
            case CallbackServiceCodec.CALLBACK_CREATE:
                try {
                    return referOrDestroyCallbackService(channel, url, pts[paraIndex], inv, Integer.parseInt(inv.getAttachment(INV_ATT_CALLBACK_KEY + paraIndex)), true);
                } catch (Exception e) {
                    logger.error(e.getMessage(), e);
                    throw new IOException(StringUtils.toString(e));
                }
            case CallbackServiceCodec.CALLBACK_DESTROY:
                try {
                    return referOrDestroyCallbackService(channel, url, pts[paraIndex], inv, Integer.parseInt(inv.getAttachment(INV_ATT_CALLBACK_KEY + paraIndex)), false);
                } catch (Exception e) {
                    throw new IOException(StringUtils.toString(e));
                }
            default:
                return inObject;
        }
    }
}
