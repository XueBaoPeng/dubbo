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

//该类是缓冲区的工具类，提供创建、比较 ChannelBuffer 等公用方法。我在这里举两个方法来讲：
public final class ChannelBuffers {

    public static final ChannelBuffer EMPTY_BUFFER = new HeapChannelBuffer(0);

    private ChannelBuffers() {
    }

    public static ChannelBuffer dynamicBuffer() {
        return dynamicBuffer(256);
    }

    public static ChannelBuffer dynamicBuffer(int capacity) {
        return new DynamicChannelBuffer(capacity);
    }

    public static ChannelBuffer dynamicBuffer(int capacity,
                                              ChannelBufferFactory factory) {
        return new DynamicChannelBuffer(capacity, factory);
    }

    public static ChannelBuffer buffer(int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("capacity can not be negative");
        }
        if (capacity == 0) {
            return EMPTY_BUFFER;
        }
        return new HeapChannelBuffer(capacity);
    }

    public static ChannelBuffer wrappedBuffer(byte[] array, int offset, int length) {
        if (array == null) {
            throw new NullPointerException("array == null");
        }
        byte[] dest = new byte[length];
        System.arraycopy(array, offset, dest, 0, length);
        return wrappedBuffer(dest);
    }

    public static ChannelBuffer wrappedBuffer(byte[] array) {
        if (array == null) {
            throw new NullPointerException("array == null");
        }
        if (array.length == 0) {
            return EMPTY_BUFFER;
        }
        return new HeapChannelBuffer(array);
    }

    public static ChannelBuffer wrappedBuffer(ByteBuffer buffer) {
        // 如果缓冲区没有剩余容量
        if (!buffer.hasRemaining()) {
            return EMPTY_BUFFER;
        }
        // 如果是字节数组生成的缓冲区
        if (buffer.hasArray()) {
            // 使用buffer的字节数组生成一个新的缓冲区
            return wrappedBuffer(buffer.array(), buffer.arrayOffset() + buffer.position(), buffer.remaining());
        } else {
            // 基于ByteBuffer创建一个缓冲区（利用buffer的剩余容量创建）
            return new ByteBufferBackedChannelBuffer(buffer);
        }
    }

    public static ChannelBuffer directBuffer(int capacity) {
        if (capacity == 0) {
            return EMPTY_BUFFER;
        }

        ChannelBuffer buffer = new ByteBufferBackedChannelBuffer(
                ByteBuffer.allocateDirect(capacity));
        buffer.clear();
        return buffer;
    }

    public static boolean equals(ChannelBuffer bufferA, ChannelBuffer bufferB) {
        // 获得bufferA的可读数据
        final int aLen = bufferA.readableBytes();
        // 如果两个缓冲区的可读数据大小不一样，则不是同一个
        if (aLen != bufferB.readableBytes()) {
            return false;
        }

        final int byteCount = aLen & 7;
        // 获得两个比较的缓冲区的读索引
        int aIndex = bufferA.readerIndex();
        int bIndex = bufferB.readerIndex();
        // 最多比较缓冲区中的7个数据
        for (int i = byteCount; i > 0; i--) {
            // 一旦有一个数据不一样，则不是同一个
            if (bufferA.getByte(aIndex) != bufferB.getByte(bIndex)) {
                return false;
            }
            aIndex++;
            bIndex++;
        }

        return true;
    }

    public static int hasCode(ChannelBuffer buffer){
        final int aLen = buffer.readableBytes();
        final int byteCount = aLen & 7;

        int hashCode = 1;
        int arrayIndex = buffer.readerIndex();

        for (int i = byteCount; i > 0; i--) {
            hashCode = 31 * hashCode + buffer.getByte(arrayIndex++);
        }

        if (hashCode == 0) {
            hashCode = 1;
        }

        return hashCode;
    }

    public static int compare(ChannelBuffer bufferA, ChannelBuffer bufferB) {
        final int aLen = bufferA.readableBytes();
        final int bLen = bufferB.readableBytes();
        final int minLength = Math.min(aLen, bLen);

        int aIndex = bufferA.readerIndex();
        int bIndex = bufferB.readerIndex();

        for (int i = minLength; i > 0; i--) {
            byte va = bufferA.getByte(aIndex);
            byte vb = bufferB.getByte(bIndex);
            if (va > vb) {
                return 1;
            } else if (va < vb) {
                return -1;
            }
            aIndex++;
            bIndex++;
        }

        return aLen - bLen;
    }

}
