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

package org.apache.dubbo.remoting.buffer;

import java.nio.ByteBuffer;
//该类实现了ChannelBufferFactory接口，是直接缓冲区工厂，用来创建直接缓冲区。
public class DirectChannelBufferFactory implements ChannelBufferFactory {

    //单例
    private static final DirectChannelBufferFactory INSTANCE = new DirectChannelBufferFactory();

    public DirectChannelBufferFactory() {
        super();
    }

    public static ChannelBufferFactory getInstance() {
        return INSTANCE;
    }

    @Override
    public ChannelBuffer getBuffer(int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("capacity: " + capacity);
        }
        if (capacity == 0) {
            return ChannelBuffers.EMPTY_BUFFER;
        }
        // 生成直接缓冲区
        return ChannelBuffers.directBuffer(capacity);
    }

    @Override
    public ChannelBuffer getBuffer(byte[] array, int offset, int length) {
        if (array == null) {
            throw new NullPointerException("array");
        }
        if (offset < 0) {
            throw new IndexOutOfBoundsException("offset: " + offset);
        }
        if (length == 0) {
            return ChannelBuffers.EMPTY_BUFFER;
        }
        if (offset + length > array.length) {
            throw new IndexOutOfBoundsException("length: " + length);
        }

        ChannelBuffer buf = getBuffer(length);
        buf.writeBytes(array, offset, length);
        return buf;
    }

    @Override
    public ChannelBuffer getBuffer(ByteBuffer nioBuffer) {
        // 如果nioBuffer不是只读，并且它是直接缓冲区
        if (!nioBuffer.isReadOnly() && nioBuffer.isDirect()) {
            // 创建一个缓冲区
            return ChannelBuffers.wrappedBuffer(nioBuffer);
        }
        // 创建一个nioBuffer剩余容量的缓冲区
        ChannelBuffer buf = getBuffer(nioBuffer.remaining());
        // 记录下nioBuffer的位置
        int pos = nioBuffer.position();
        // 写入数据到buf
        buf.writeBytes(nioBuffer);
        // 把nioBuffer的位置重置到pos
        nioBuffer.position(pos);
        return buf;
    }

}
