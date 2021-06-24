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

import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.rpc.RpcContext;

import org.aopalliance.intercept.MethodInvocation;
import org.springframework.remoting.support.RemoteInvocation;

import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.Map;

import static org.apache.dubbo.rpc.Constants.GENERIC_KEY;

/**
 * JsonRemoteInvocation
 * 该类继承了RemoteInvocation类，是在RemoteInvocation上增加了泛化调用的参数设置，以及增加了dubbo本身需要的附加值设置。
 */
public class JsonRemoteInvocation extends RemoteInvocation {
    private static final long serialVersionUID = 1L;
    /**
     * dubbo的附加值名称
     */
    private static final String DUBBO_ATTACHMENTS_ATTR_NAME = "dubbo.attachments";

    public JsonRemoteInvocation(MethodInvocation methodInvocation) {
        super(methodInvocation);
        // 把附加值加入到会话域的属性里面
        addAttribute(DUBBO_ATTACHMENTS_ATTR_NAME, new HashMap<>(RpcContext.getContext().getAttachments()));
    }

    @Override
    public Object invoke(Object targetObject) throws NoSuchMethodException, IllegalAccessException,
            InvocationTargetException {
        // 获得上下文
        RpcContext context = RpcContext.getContext();
        // 获得附加值
        context.setAttachments((Map<String, String>) getAttribute(DUBBO_ATTACHMENTS_ATTR_NAME));
        // 泛化标志
        String generic = (String) getAttribute(GENERIC_KEY);
        // 如果不为空，则设置泛化标志
        if (StringUtils.isNotEmpty(generic)) {
            context.setAttachment(GENERIC_KEY, generic);
        }
        try {
            // 调用下一个调用链
            return super.invoke(targetObject);
        } finally {
            context.setAttachments(null);

        }
    }
}
