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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

//该类继承了AbstractChannelBuffer类，该类是动态的通道缓存区类，
// 也就是该类是从ChannelBufferFactory工厂中动态的生成缓冲区，默认使用的工厂是HeapChannelBufferFactory。
public class DynamicChannelBuffer extends AbstractChannelBuffer {

    //通道缓存区工厂
    private final ChannelBufferFactory factory;

    //通道缓存区
    private ChannelBuffer buffer;

    public DynamicChannelBuffer(int estimatedLength) {
        // 默认是HeapChannelBufferFactory
        this(estimatedLength, HeapChannelBufferFactory.getInstance());
    }

    public DynamicChannelBuffer(int estimatedLength, ChannelBufferFactory factory) {
        // 如果预计长度小于0 则抛出异常
        if (estimatedLength < 0) {
            throw new IllegalArgumentException("estimatedLength: " + estimatedLength);
        }
        // 如果工厂为空，则抛出空指针异常
        if (factory == null) {
            throw new NullPointerException("factory");
        }
        // 设置工厂
        this.factory = factory;
        // 创建缓存区
        buffer = factory.getBuffer(estimatedLength);
    }

    //该方法是确保数组有可写的容量，该方法是重写了父类的方法，通过传入一个最小写入的字节数，
    // 来对缓冲区进行扩容，可以看到，当现有的缓冲区不够大的时候，会对缓冲区进行加倍对扩容，
    // 直到buffer的大小大于传入的最小可写字节数
    @Override
    public void ensureWritableBytes(int minWritableBytes) {
        // 如果最小写入的字节数不大于可写的字节数，则结束
        if (minWritableBytes <= writableBytes()) {
            return;
        }
        // 新增容量
        int newCapacity;
        // 此缓冲区可包含的字节数等于0。
        if (capacity() == 0) {
            // 新增容量设置为1
            newCapacity = 1;
        } else {
            // 新增容量设置为缓冲区可包含的字节数
            newCapacity = capacity();
        }
        // 最小新增容量 = 当前的写索引+最小写入的字节数
        int minNewCapacity = writerIndex() + minWritableBytes;
        // 当新增容量比最小新增容量小
        while (newCapacity < minNewCapacity) {
            // 新增容量左移1位，也就是加倍
            newCapacity <<= 1;
        }
        // 通过工厂创建该容量大小当缓冲区
        ChannelBuffer newBuffer = factory().getBuffer(newCapacity);
        // 从buffer中读取数据到newBuffer中
        newBuffer.writeBytes(buffer, 0, writerIndex());
        // 替换原来到缓冲区
        buffer = newBuffer;
    }


    @Override
    public int capacity() {
        return buffer.capacity();
    }


    //方法是复制数据，在创建缓冲区的时候，预计长度最小是64，，然后重新设置读索引写索引
    @Override
    public ChannelBuffer copy(int index, int length) {
        // 创建缓冲区，预计长度最小为64，或者更大
        DynamicChannelBuffer copiedBuffer = new DynamicChannelBuffer(Math.max(length, 64), factory());
        // 复制数据
        copiedBuffer.buffer = buffer.copy(index, length);
        // 设置索引，读索引设置为0，写索引设置为copy的数据长度
        copiedBuffer.setIndex(0, length);
        // 返回缓存区
        return copiedBuffer;
    }


    @Override
    public ChannelBufferFactory factory() {
        return factory;
    }


    @Override
    public byte getByte(int index) {
        return buffer.getByte(index);
    }


    @Override
    public void getBytes(int index, byte[] dst, int dstIndex, int length) {
        buffer.getBytes(index, dst, dstIndex, length);
    }


    @Override
    public void getBytes(int index, ByteBuffer dst) {
        buffer.getBytes(index, dst);
    }


    @Override
    public void getBytes(int index, ChannelBuffer dst, int dstIndex, int length) {
        buffer.getBytes(index, dst, dstIndex, length);
    }


    @Override
    public void getBytes(int index, OutputStream dst, int length) throws IOException {
        buffer.getBytes(index, dst, length);
    }


    @Override
    public boolean isDirect() {
        return buffer.isDirect();
    }


    @Override
    public void setByte(int index, int value) {
        buffer.setByte(index, value);
    }


    @Override
    public void setBytes(int index, byte[] src, int srcIndex, int length) {
        buffer.setBytes(index, src, srcIndex, length);
    }


    @Override
    public void setBytes(int index, ByteBuffer src) {
        buffer.setBytes(index, src);
    }


    @Override
    public void setBytes(int index, ChannelBuffer src, int srcIndex, int length) {
        buffer.setBytes(index, src, srcIndex, length);
    }


    @Override
    public int setBytes(int index, InputStream src, int length) throws IOException {
        return buffer.setBytes(index, src, length);
    }


    @Override
    public ByteBuffer toByteBuffer(int index, int length) {
        return buffer.toByteBuffer(index, length);
    }

    @Override
    public void writeByte(int value) {
        ensureWritableBytes(1);
        super.writeByte(value);
    }

    @Override
    public void writeBytes(byte[] src, int srcIndex, int length) {
        ensureWritableBytes(length);
        super.writeBytes(src, srcIndex, length);
    }

    @Override
    public void writeBytes(ChannelBuffer src, int srcIndex, int length) {
        ensureWritableBytes(length);
        super.writeBytes(src, srcIndex, length);
    }

    @Override
    public void writeBytes(ByteBuffer src) {
        ensureWritableBytes(src.remaining());
        super.writeBytes(src);
    }

    @Override
    public int writeBytes(InputStream in, int length) throws IOException {
        ensureWritableBytes(length);
        return super.writeBytes(in, length);
    }


    @Override
    public byte[] array() {
        return buffer.array();
    }


    @Override
    public boolean hasArray() {
        return buffer.hasArray();
    }


    @Override
    public int arrayOffset() {
        return buffer.arrayOffset();
    }
}
