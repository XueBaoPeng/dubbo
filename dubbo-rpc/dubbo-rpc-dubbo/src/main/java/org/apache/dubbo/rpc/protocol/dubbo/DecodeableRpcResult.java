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

import org.apache.dubbo.common.config.ConfigurationUtils;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.serialize.Cleanable;
import org.apache.dubbo.common.serialize.ObjectInput;
import org.apache.dubbo.common.utils.ArrayUtils;
import org.apache.dubbo.common.utils.Assert;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.Codec;
import org.apache.dubbo.remoting.Decodeable;
import org.apache.dubbo.remoting.exchange.Response;
import org.apache.dubbo.remoting.transport.CodecSupport;
import org.apache.dubbo.rpc.AppResponse;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.support.RpcUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Type;

import static org.apache.dubbo.rpc.Constants.SERIALIZATION_ID_KEY;
import static org.apache.dubbo.rpc.Constants.SERIALIZATION_SECURITY_CHECK_KEY;

/**
 * 该类是做了基于dubbo协议对prc结果的解码
 */
public class DecodeableRpcResult extends AppResponse implements Codec, Decodeable {

    private static final Logger log = LoggerFactory.getLogger(DecodeableRpcResult.class);
    /**
     * 通道
     */
    private Channel channel;
    /**
     * 序列化类型
     */
    private byte serializationType;
    /**
     * 输入流
     */
    private InputStream inputStream;
    /**
     * 响应
     */
    private Response response;
    /**
     * 会话域
     */
    private Invocation invocation;
    /**
     * 是否解码
     */
    private volatile boolean hasDecoded;

    public DecodeableRpcResult(Channel channel, Response response, InputStream is, Invocation invocation, byte id) {
        Assert.notNull(channel, "channel == null");
        Assert.notNull(response, "response == null");
        Assert.notNull(is, "inputStream == null");
        this.channel = channel;
        this.response = response;
        this.inputStream = is;
        this.invocation = invocation;
        this.serializationType = id;
    }

    @Override
    public void encode(Channel channel, OutputStream output, Object message) throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Object decode(Channel channel, InputStream input) throws IOException {
        if (log.isDebugEnabled()) {
            Thread thread = Thread.currentThread();
            log.debug("Decoding in thread -- [" + thread.getName() + "#" + thread.getId() + "]");
        }
        // 反序列化
        ObjectInput in = CodecSupport.getSerialization(channel.getUrl(), serializationType)
                .deserialize(channel.getUrl(), input);

        byte flag = in.readByte();
        // 根据返回的不同结果来进行处理
        switch (flag) {
            case DubboCodec.RESPONSE_NULL_VALUE:
                // 返回结果为空
                break;
            case DubboCodec.RESPONSE_VALUE:
                handleValue(in);
                break;
            case DubboCodec.RESPONSE_WITH_EXCEPTION:
                // 返回结果有异常
                handleException(in);
                break;
            case DubboCodec.RESPONSE_NULL_VALUE_WITH_ATTACHMENTS:
                // 返回值为空，但是有附加值
                handleAttachment(in);
                break;
            case DubboCodec.RESPONSE_VALUE_WITH_ATTACHMENTS:
                // 返回值
                handleValue(in);
                // 设置附加值
                handleAttachment(in);
                break;
            case DubboCodec.RESPONSE_WITH_EXCEPTION_WITH_ATTACHMENTS:
                // 返回结果有异常并且有附加值
                handleException(in);
                // 设置附加值
                handleAttachment(in);
                break;
            default:
                throw new IOException("Unknown result flag, expect '0' '1' '2' '3' '4' '5', but received: " + flag);
        }
        if (in instanceof Cleanable) {
            ((Cleanable) in).cleanup();
        }
        return this;
    }

    /**
     * 该方法是对响应结果的解码，其中根据不同的返回结果来对RpcResult设置不同的值。
     * @throws Exception
     */
    @Override
    public void decode() throws Exception {
        // 如果没有解码
        if (!hasDecoded && channel != null && inputStream != null) {
            try {
                if (ConfigurationUtils.getSystemConfiguration().getBoolean(SERIALIZATION_SECURITY_CHECK_KEY, false)) {
                    Object serializationType_obj = invocation.get(SERIALIZATION_ID_KEY);
                    if (serializationType_obj != null) {
                        if ((byte) serializationType_obj != serializationType) {
                            throw new IOException("Unexpected serialization id:" + serializationType + " received from network, please check if the peer send the right id.");
                        }
                    }
                }
                // 进行解码
                decode(channel, inputStream);
            } catch (Throwable e) {
                if (log.isWarnEnabled()) {
                    log.warn("Decode rpc result failed: " + e.getMessage(), e);
                }
                response.setStatus(Response.CLIENT_ERROR);
                response.setErrorMessage(StringUtils.toString(e));
            } finally {
                hasDecoded = true;
            }
        }
    }

    private void handleValue(ObjectInput in) throws IOException {
        try {
            Type[] returnTypes;
            if (invocation instanceof RpcInvocation) {
                // 获得返回类型数组
                returnTypes = ((RpcInvocation) invocation).getReturnTypes();
            } else {
                returnTypes = RpcUtils.getReturnTypes(invocation);
            }
            Object value = null;
            // 根据返回类型读取返回结果并且放入RpcResult
            if (ArrayUtils.isEmpty(returnTypes)) {
                // This almost never happens?
                value = in.readObject();
            } else if (returnTypes.length == 1) {
                value = in.readObject((Class<?>) returnTypes[0]);
            } else {
                value = in.readObject((Class<?>) returnTypes[0], returnTypes[1]);
            }
            // 设置返回结果
            setValue(value);
        } catch (ClassNotFoundException e) {
            rethrow(e);
        }
    }

    private void handleException(ObjectInput in) throws IOException {
        try {
            // 把异常放入RpcResult
            setException(in.readThrowable());
        } catch (ClassNotFoundException e) {
            rethrow(e);
        }
    }

    private void handleAttachment(ObjectInput in) throws IOException {
        try {
            // 把附加值加入到RpcResult
            addObjectAttachments(in.readAttachments());
        } catch (ClassNotFoundException e) {
            rethrow(e);
        }
    }

    private void rethrow(Exception e) throws IOException {
        throw new IOException(StringUtils.toString("Read response data failed.", e));
    }
}
