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
package org.apache.dubbo.rpc.filter;

import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.CompatibleTypeUtils;
import org.apache.dubbo.common.utils.PojoUtils;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;

import java.lang.reflect.Method;
import java.lang.reflect.Type;

import static org.apache.dubbo.remoting.Constants.SERIALIZATION_KEY;

/**
 * CompatibleFilter make the remote method's return value compatible to invoker's version of object.
 * To make return object compatible it does
 * <pre>
 *    1)If the url contain serialization key of type <b>json</b> or <b>fastjson</b> then transform
 *    the return value to instance of {@link java.util.Map}
 *    2)If the return value is not a instance of invoked method's return type available at
 *    local jvm then POJO conversion.
 *    3)If return value is other than above return value as it is.
 * </pre>
 * 该过滤器是做兼容性的过滤器。
 * @see Filter
 *
 */
public class CompatibleFilter implements Filter, Filter.Listener {

    private static Logger logger = LoggerFactory.getLogger(CompatibleFilter.class);

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        // 调用下一个调用链
        return invoker.invoke(invocation);
    }

    /**
     * 可以看到对于调用链的返回结果，如果返回值类型和返回值不一样的时候，就需要做兼容类型的转化。重新把结果放入RpcResult，返回。
     * @param appResponse
     * @param invoker
     * @param invocation
     */
    @Override
    public void onResponse(Result appResponse, Invoker<?> invoker, Invocation invocation) {
        // 如果方法前面没有$或者结果没有异常
        if (!invocation.getMethodName().startsWith("$") && !appResponse.hasException()) {
            Object value = appResponse.getValue();
            if (value != null) {
                try {
                    // 获得方法
                    Method method = invoker.getInterface().getMethod(invocation.getMethodName(), invocation.getParameterTypes());
                    // 获得返回的数据类型
                    Class<?> type = method.getReturnType();
                    Object newValue;
                    // 序列化方法
                    String serialization = invoker.getUrl().getParameter(SERIALIZATION_KEY);
                    // 如果是json或者fastjson形式
                    if ("json".equals(serialization) || "fastjson".equals(serialization)) {
                        // If the serialization key is json or fastjson
                        // 获得方法的泛型返回值类型
                        Type gtype = method.getGenericReturnType();
                        // 把数据结果进行类型转化
                        newValue = PojoUtils.realize(value, type, gtype);
                        // 如果value不是type类型
                    } else if (!type.isInstance(value)) {
                        //if local service interface's method's return type is not instance of return value
                        // 如果是pojo，则，转化为type类型，如果不是，则进行兼容类型转化。
                        newValue = PojoUtils.isPojo(type) ? PojoUtils.realize(value, type) : CompatibleTypeUtils.compatibleTypeConvert(value, type);

                    } else {
                        newValue = value;
                    }
                    // 重新设置RpcResult的result
                    if (newValue != value) {
                        appResponse.setValue(newValue);
                    }
                } catch (Throwable t) {
                    logger.warn(t.getMessage(), t);
                }
            }
        }
    }

    @Override
    public void onError(Throwable t, Invoker<?> invoker, Invocation invocation) {

    }
}
