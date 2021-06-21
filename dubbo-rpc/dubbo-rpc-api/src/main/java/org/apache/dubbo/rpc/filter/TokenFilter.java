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
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;

import java.util.Map;

import static org.apache.dubbo.rpc.Constants.TOKEN_KEY;

/**
 * Perform check whether given provider token is matching with remote token or not. If it does not match
 * it will not allow to invoke remote method.
 * 该过滤器提供了token的验证功能，关于token的介绍可以查看官方文档。
 * @see Filter
 */
@Activate(group = CommonConstants.PROVIDER, value = TOKEN_KEY)
public class TokenFilter implements Filter {

    @Override
    public Result invoke(Invoker<?> invoker, Invocation inv)
            throws RpcException {
        // 获得token值
        String token = invoker.getUrl().getParameter(TOKEN_KEY);
        if (ConfigUtils.isNotEmpty(token)) {
            // 获得服务类型
            Class<?> serviceType = invoker.getInterface();
            // 获得附加值
            Map<String, Object> attachments = inv.getObjectAttachments();
            String remoteToken = (attachments == null ? null : (String) attachments.get(TOKEN_KEY));
            // 如果令牌不一样，则抛出异常
            if (!token.equals(remoteToken)) {
                throw new RpcException("Invalid token! Forbid invoke remote service " + serviceType + " method " + inv.getMethodName()
                        + "() from consumer " + RpcContext.getContext().getRemoteHost() + " to provider " + RpcContext.getContext().getLocalHost()
                        + ", consumer incorrect token is " + remoteToken);
            }
        }
        // 调用下一个调用链
        return invoker.invoke(inv);
    }

}
