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

import org.apache.dubbo.remoting.Constants;
import org.apache.dubbo.rpc.RpcContext;

import com.caucho.hessian.client.HessianConnection;
import com.caucho.hessian.client.HessianURLConnectionFactory;

import java.io.IOException;
import java.net.URL;

/**
 * 该类继承了HessianURLConnectionFactory类，是dubbo，用于创建与服务器的连接的内部工厂，重写了父类中open方法。
 * 在hessian上加入dubbo自己所需要的附加值，放到协议头里面进行发送。
 */
public class DubboHessianURLConnectionFactory extends HessianURLConnectionFactory {
    /**
     * 打开与HTTP服务器的新连接或循环连接
     * @param url
     * @return
     * @throws IOException
     */
    @Override
    public HessianConnection open(URL url) throws IOException {
        // 获得一个连接
        HessianConnection connection = super.open(url);
        // 获得上下文
        RpcContext context = RpcContext.getContext();
        for (String key : context.getObjectAttachments().keySet()) {
            // 在http协议头里面加入dubbo中附加值，key为 header+key  value为附加值的value
            connection.addHeader(Constants.DEFAULT_EXCHANGER + key, context.getAttachment(key));
        }

        return connection;
    }
}
