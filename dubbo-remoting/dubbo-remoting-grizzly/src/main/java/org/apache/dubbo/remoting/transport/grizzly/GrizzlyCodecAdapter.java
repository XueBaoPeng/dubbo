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
package org.apache.dubbo.remoting.transport.grizzly;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.Codec2;
import org.apache.dubbo.remoting.buffer.ChannelBuffer;
import org.apache.dubbo.remoting.buffer.ChannelBuffers;
import org.apache.dubbo.remoting.buffer.DynamicChannelBuffer;

import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.filterchain.BaseFilter;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.NextAction;

import java.io.IOException;

import static org.apache.dubbo.remoting.Constants.BUFFER_KEY;
import static org.apache.dubbo.remoting.Constants.DEFAULT_BUFFER_SIZE;
import static org.apache.dubbo.remoting.Constants.MAX_BUFFER_SIZE;
import static org.apache.dubbo.remoting.Constants.MIN_BUFFER_SIZE;

/**
 * GrizzlyCodecAdapter
 * 该类是Grizzly编解码类，继承了BaseFilter。
 */
public class GrizzlyCodecAdapter extends BaseFilter {

    //编解码器
    private final Codec2 codec;

    private final URL url;

    //通道处理器
    private final ChannelHandler handler;

    //缓存大小
    private final int bufferSize;

    //空缓存区
    private ChannelBuffer previousData = ChannelBuffers.EMPTY_BUFFER;

    public GrizzlyCodecAdapter(Codec2 codec, URL url, ChannelHandler handler) {
        this.codec = codec;
        this.url = url;
        this.handler = handler;
        int b = url.getPositiveParameter(BUFFER_KEY, DEFAULT_BUFFER_SIZE);
        // 如果缓存区大小在16k以内，则设置配置大小，如果不是，则设置8k的缓冲区大小
        this.bufferSize = b >= MIN_BUFFER_SIZE && b <= MAX_BUFFER_SIZE ? b : DEFAULT_BUFFER_SIZE;
    }

    @Override
    public NextAction handleWrite(FilterChainContext context) throws IOException {
        Connection<?> connection = context.getConnection();
        GrizzlyChannel channel = GrizzlyChannel.getOrAddChannel(connection, url, handler);
        try {
            // 分配一个1024的动态缓冲区
            ChannelBuffer channelBuffer = ChannelBuffers.dynamicBuffer(1024); // Do not need to close
            // 获得消息
            Object msg = context.getMessage();
            // 编码
            codec.encode(channel, channelBuffer, msg);
            // 检测是否连接
            GrizzlyChannel.removeChannelIfDisconnected(connection);
            // 分配缓冲区
            Buffer buffer = connection.getTransport().getMemoryManager().allocate(channelBuffer.readableBytes());
            // 把channelBuffer的数据写到buffer
            buffer.put(channelBuffer.toByteBuffer());
            buffer.flip();
            buffer.allowBufferDispose(true);
            // 设置到上下文
            context.setMessage(buffer);
        } finally {
            GrizzlyChannel.removeChannelIfDisconnected(connection);
        }
        return context.getInvokeAction();
    }

    @Override
    public NextAction handleRead(FilterChainContext context) throws IOException {
        Object message = context.getMessage();
        Connection<?> connection = context.getConnection();
        Channel channel = GrizzlyChannel.getOrAddChannel(connection, url, handler);
        try {
            // 如果接收的是一个数据包
            if (message instanceof Buffer) { // receive a new packet
                Buffer grizzlyBuffer = (Buffer) message; // buffer

                ChannelBuffer frame;
                // 如果缓冲区可读
                if (previousData.readable()) {
                    // 如果该缓冲区是动态的缓冲区
                    if (previousData instanceof DynamicChannelBuffer) {
                        // 写入数据
                        previousData.writeBytes(grizzlyBuffer.toByteBuffer());
                        frame = previousData;
                    } else {
                        // 获得需要的缓冲区大小
                        int size = previousData.readableBytes() + grizzlyBuffer.remaining();
                        // 新建一个动态缓冲区
                        frame = ChannelBuffers.dynamicBuffer(size > bufferSize ? size : bufferSize);
                        // 写入previousData中的数据
                        frame.writeBytes(previousData, previousData.readableBytes());
                        // 写入grizzlyBuffer中的数据
                        frame.writeBytes(grizzlyBuffer.toByteBuffer());
                    }
                } else {
                    // 否则是基于Java NIO的ByteBuffer生成的缓冲区
                    frame = ChannelBuffers.wrappedBuffer(grizzlyBuffer.toByteBuffer());
                }

                Object msg;
                int savedReadIndex;

                do {
                    savedReadIndex = frame.readerIndex();
                    try {
                        // 解码
                        msg = codec.decode(channel, frame);
                    } catch (Exception e) {
                        previousData = ChannelBuffers.EMPTY_BUFFER;
                        throw new IOException(e.getMessage(), e);
                    }
                    // 拆包
                    if (msg == Codec2.DecodeResult.NEED_MORE_INPUT) {
                        frame.readerIndex(savedReadIndex);
                        // 结束调用链
                        return context.getStopAction();
                    } else {
                        if (savedReadIndex == frame.readerIndex()) {
                            // 没有可读内容
                            previousData = ChannelBuffers.EMPTY_BUFFER;
                            throw new IOException("Decode without read data.");
                        }
                        if (msg != null) {
                            // 把解码后信息放入上下文
                            context.setMessage(msg);
                        }
                        // 继续下面的调用链
                        return context.getInvokeAction();
                    }
                } while (frame.readable());
            } else { // Other events are passed down directly
                return context.getInvokeAction();
            }
        } finally {
            GrizzlyChannel.removeChannelIfDisconnected(connection);
        }
    }

}