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

import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ConcurrentHashSet;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;

import java.util.Set;

import static org.apache.dubbo.rpc.Constants.DEPRECATED_KEY;

/**
 * DeprecatedFilter logs error message if a invoked method has been marked as deprecated. To check whether a method
 * is deprecated or not it looks for <b>deprecated</b> attribute value and consider it is deprecated it value is <b>true</b>
 * 该过滤器的作用是调用了废弃的方法时打印错误日志。
 * @see Filter
 */
@Activate(group = CommonConstants.CONSUMER, value = DEPRECATED_KEY)
public class DeprecatedFilter implements Filter {

    private static final Logger LOGGER = LoggerFactory.getLogger(DeprecatedFilter.class);

    //日志集合
    private static final Set<String> LOGGED = new ConcurrentHashSet<String>();

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        // 获得key 服务+方法
        String key = invoker.getInterface().getName() + "." + invocation.getMethodName();
        // 如果集合中没有该key
        if (!LOGGED.contains(key)) {
            // 则加入集合
            LOGGED.add(key);
            // 如果该服务方法是废弃的，则打印错误日志
            if (invoker.getUrl().getMethodParameter(invocation.getMethodName(), DEPRECATED_KEY, false)) {
                LOGGER.error("The service method " + invoker.getInterface().getName() + "." + getMethodSignature(invocation) + " is DEPRECATED! Declare from " + invoker.getUrl());
            }
        }
        // 调用下一个调用链
        return invoker.invoke(invocation);
    }
    /**
     * 获得方法定义
     * @param invocation
     * @return
     */
    private String getMethodSignature(Invocation invocation) {
        // 方法名
        StringBuilder buf = new StringBuilder(invocation.getMethodName());
        buf.append("(");
        // 参数类型
        Class<?>[] types = invocation.getParameterTypes();
        // 拼接参数
        if (types != null && types.length > 0) {
            boolean first = true;
            for (Class<?> type : types) {
                if (first) {
                    first = false;
                } else {
                    buf.append(", ");
                }
                buf.append(type.getSimpleName());
            }
        }
        buf.append(")");
        return buf.toString();
    }

}
