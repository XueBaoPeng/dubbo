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
package org.apache.dubbo.remoting.transport.mina;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.Codec2;
import org.apache.dubbo.remoting.buffer.ChannelBuffer;
import org.apache.dubbo.remoting.buffer.ChannelBuffers;
import org.apache.dubbo.remoting.buffer.DynamicChannelBuffer;

import org.apache.mina.common.ByteBuffer;
import org.apache.mina.common.IoSession;
import org.apache.mina.filter.codec.ProtocolCodecFactory;
import org.apache.mina.filter.codec.ProtocolDecoder;
import org.apache.mina.filter.codec.ProtocolDecoderOutput;
import org.apache.mina.filter.codec.ProtocolEncoder;
import org.apache.mina.filter.codec.ProtocolEncoderOutput;

import static org.apache.dubbo.remoting.Constants.BUFFER_KEY;
import static org.apache.dubbo.remoting.Constants.DEFAULT_BUFFER_SIZE;
import static org.apache.dubbo.remoting.Constants.MAX_BUFFER_SIZE;
import static org.apache.dubbo.remoting.Constants.MIN_BUFFER_SIZE;

/**
 * MinaCodecAdapter.
 * 该类是基于mina实现的编解码类，实现了ProtocolCodecFactory。
 * 该编解码器用到了ProtocolEncoder和ProtocolDecoder
 * ，而InternalEncoder和InternalDecoder两个类是该类的内部类，
 * 它们实现了ProtocolEncoder和ProtocolDecoder，关键的编解码逻辑在这两个类中实现。
 */
final class MinaCodecAdapter implements ProtocolCodecFactory {

    //编码对象
    private final ProtocolEncoder encoder = new InternalEncoder();

    //解码对象
    private final ProtocolDecoder decoder = new InternalDecoder();

    //编解码器
    private final Codec2 codec;

    private final URL url;

    //通道处理器对象
    private final ChannelHandler handler;

    //缓冲区大小
    private final int bufferSize;

    public MinaCodecAdapter(Codec2 codec, URL url, ChannelHandler handler) {
        this.codec = codec;
        this.url = url;
        this.handler = handler;
        int b = url.getPositiveParameter(BUFFER_KEY, DEFAULT_BUFFER_SIZE);
        // 如果缓存区大小在16字节以内，则设置配置大小，如果不是，则设置8字节的缓冲区大小
        this.bufferSize = b >= MIN_BUFFER_SIZE && b <= MAX_BUFFER_SIZE ? b : DEFAULT_BUFFER_SIZE;
    }

    @Override
    public ProtocolEncoder getEncoder() {
        return encoder;
    }

    @Override
    public ProtocolDecoder getDecoder() {
        return decoder;
    }

    private class InternalEncoder implements ProtocolEncoder {

        @Override
        public void dispose(IoSession session) throws Exception {
        }

        @Override
        public void encode(IoSession session, Object msg, ProtocolEncoderOutput out) throws Exception {
            // 动态分配一个1k的缓冲区
            ChannelBuffer buffer = ChannelBuffers.dynamicBuffer(1024);
            // 获得通道
            MinaChannel channel = MinaChannel.getOrAddChannel(session, url, handler);
            try {
                // 编码
                codec.encode(channel, buffer, msg);
            } finally {
                // 检测是否断开连接，如果断开，则移除
                MinaChannel.removeChannelIfDisconnected(session);
            }
            // 写数据到out中
            out.write(ByteBuffer.wrap(buffer.toByteBuffer()));
            out.flush();
        }
    }

    private class InternalDecoder implements ProtocolDecoder {

        private ChannelBuffer buffer = ChannelBuffers.EMPTY_BUFFER;

        @Override
        public void decode(IoSession session, ByteBuffer in, ProtocolDecoderOutput out) throws Exception {
            int readable = in.limit();
            if (readable <= 0) {
                return;
            }

            ChannelBuffer frame;
            // 如果缓冲区还有可读字节数=
            if (buffer.readable()) {
                // 如果缓冲区是DynamicChannelBuffer类型的
                if (buffer instanceof DynamicChannelBuffer) {
                    // 往buffer中写入数据
                    buffer.writeBytes(in.buf());
                    frame = buffer;
                } else {
                    // 缓冲区大小
                    int size = buffer.readableBytes() + in.remaining();
                    // 动态分配一个缓冲区
                    frame = ChannelBuffers.dynamicBuffer(size > bufferSize ? size : bufferSize);
                    // buffer的数据把写到frame
                    frame.writeBytes(buffer, buffer.readableBytes());
                    // 把流中的数据写到frame
                    frame.writeBytes(in.buf());
                }
            } else {
                // 否则是基于Java NIO的ByteBuffer生成的缓冲区
                frame = ChannelBuffers.wrappedBuffer(in.buf());
            }

            // 获得通道
            Channel channel = MinaChannel.getOrAddChannel(session, url, handler);
            Object msg;
            int savedReadIndex;

            try {
                do {
                    // 获得读索引
                    savedReadIndex = frame.readerIndex();
                    try {
                        // 解码
                        msg = codec.decode(channel, frame);
                    } catch (Exception e) {
                        buffer = ChannelBuffers.EMPTY_BUFFER;
                        throw e;
                    }
                    // 拆包
                    if (msg == Codec2.DecodeResult.NEED_MORE_INPUT) {
                        frame.readerIndex(savedReadIndex);
                        break;
                    } else {
                        if (savedReadIndex == frame.readerIndex()) {
                            buffer = ChannelBuffers.EMPTY_BUFFER;
                            throw new Exception("Decode without read data.");
                        }
                        if (msg != null) {
                            out.write(msg);
                        }
                    }
                } while (frame.readable());
            } finally {
                // 如果frame还有可读数据
                if (frame.readable()) {
                    //丢弃可读数据
                    frame.discardReadBytes();
                    buffer = frame;
                } else {
                    buffer = ChannelBuffers.EMPTY_BUFFER;
                }
                MinaChannel.removeChannelIfDisconnected(session);
            }
        }

        @Override
        public void dispose(IoSession session) throws Exception {
        }

        @Override
        public void finishDecode(IoSession session, ProtocolDecoderOutput out) throws Exception {
        }
    }
}
