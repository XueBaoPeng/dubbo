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
package org.apache.dubbo.rpc.protocol.dubbo.status;

import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.common.status.Status;
import org.apache.dubbo.common.status.StatusChecker;
import org.apache.dubbo.remoting.RemotingServer;
import org.apache.dubbo.rpc.ProtocolServer;
import org.apache.dubbo.rpc.protocol.dubbo.DubboProtocol;

import java.util.List;

/**
 * ServerStatusChecker
 * 该类是对于服务状态的监控设置。
 */
@Activate
public class ServerStatusChecker implements StatusChecker {

    @Override
    public Status check() {
        // 获得服务集合
        List<ProtocolServer> servers = DubboProtocol.getDubboProtocol().getServers();
        // 如果为空则返回UNKNOWN的状态
        if (servers == null || servers.isEmpty()) {
            return new Status(Status.Level.UNKNOWN);
        }
        // 设置状态为ok
        Status.Level level = Status.Level.OK;
        StringBuilder buf = new StringBuilder();
        // 遍历集合
        for (ProtocolServer protocolServer : servers) {
            RemotingServer server = protocolServer.getRemotingServer();
            // 如果服务没有绑定到本地端口
            if (!server.isBound()) {
                // 状态改为error
                level = Status.Level.ERROR;
                // 加入服务本地地址
                buf.setLength(0);
                buf.append(server.getLocalAddress());
                break;
            }
            if (buf.length() > 0) {
                buf.append(",");
            }
            // 如果服务绑定了本地端口，拼接clients数量
            buf.append(server.getLocalAddress());
            buf.append("(clients:");
            buf.append(server.getChannels().size());
            buf.append(")");
        }
        return new Status(level, buf.toString());
    }

}
