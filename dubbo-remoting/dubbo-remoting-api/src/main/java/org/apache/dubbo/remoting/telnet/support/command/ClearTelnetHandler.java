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
package org.apache.dubbo.remoting.telnet.support.command;

import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.telnet.TelnetHandler;
import org.apache.dubbo.remoting.telnet.support.Help;

/**
 * ClearTelnetHandler
 */
//该类实现了TelnetHandler接口，封装了clear命令的实现。
@Activate
@Help(parameter = "[lines]", summary = "Clear screen.", detail = "Clear screen.")
public class ClearTelnetHandler implements TelnetHandler {

    private static final int MAX_LINES = 1000;

    @Override
    public String telnet(Channel channel, String message) {
        int lines = 100;
        // 清除屏幕上的内容行数
        if (message.length() > 0) {
            // 如果不是一个数字
            if (!StringUtils.isInteger(message)) {
                return "Illegal lines " + message + ", must be integer.";
            }
            lines = Math.min(MAX_LINES,Integer.parseInt(message));
        }
        StringBuilder buf = new StringBuilder();
        // 一行一行清除
        for (int i = 0; i < lines; i++) {
            buf.append("\r\n");
        }
        return buf.toString();
    }

}
