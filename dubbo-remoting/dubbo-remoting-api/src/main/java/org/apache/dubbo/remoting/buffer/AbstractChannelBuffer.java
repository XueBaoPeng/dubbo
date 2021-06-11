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

//该类实现了ChannelBuffer接口，是通道缓存的抽象类，它实现了ChannelBuffer所有方法，但是它实现的方法都是需要被重写的方法
// ，具体的实现都是需要子类来实现。现在我们来恶补一下这个通道缓存的原理，当然这个原理跟netty的ByteBuf原理是分不开的。
// AbstractChannelBuffer维护了两个索引，一个用于读取，另一个用于写入当你从通道缓存中读取时，readerIndex将会被递增已经被读取的字节数，
// 同样的当你写入的时候writerIndex也会被递增
public abstract class AbstractChannelBuffer implements ChannelBuffer {

    /**
     * 读索引和写索引的起始位置都为索引位置0。而标记读索引和标记写索引是为了做备份回滚，当对缓冲区进行读写操作时
     * ，可能需要对之前的操作进行回滚，我们就需要将当前的读写索引备份到相应的标记索引中
     */
    //读索引
    private int readerIndex;

    //写索引
    private int writerIndex;

    //标记读索引
    private int markedReaderIndex;

    //标记写索引
    private int markedWriterIndex;

    @Override
    public int readerIndex() {
        return readerIndex;
    }

    @Override
    public void readerIndex(int readerIndex) {
        if (readerIndex < 0 || readerIndex > writerIndex) {
            throw new IndexOutOfBoundsException();
        }
        this.readerIndex = readerIndex;
    }

    @Override
    public int writerIndex() {
        return writerIndex;
    }

    @Override
    public void writerIndex(int writerIndex) {
        if (writerIndex < readerIndex || writerIndex > capacity()) {
            throw new IndexOutOfBoundsException();
        }
        this.writerIndex = writerIndex;
    }

    @Override
    public void setIndex(int readerIndex, int writerIndex) {
        if (readerIndex < 0 || readerIndex > writerIndex || writerIndex > capacity()) {
            throw new IndexOutOfBoundsException();
        }
        this.readerIndex = readerIndex;
        this.writerIndex = writerIndex;
    }

    @Override
    public void clear() {
        readerIndex = writerIndex = 0;
    }

    @Override
    public boolean readable() {
        return readableBytes() > 0;
    }

    @Override
    public boolean writable() {
        return writableBytes() > 0;
    }

    @Override
    public int readableBytes() {
        return writerIndex - readerIndex;
    }

    @Override
    public int writableBytes() {
        return capacity() - writerIndex;
    }

    @Override
    public void markReaderIndex() {
        markedReaderIndex = readerIndex;
    }

    @Override
    public void resetReaderIndex() {
        readerIndex(markedReaderIndex);
    }

    @Override
    public void markWriterIndex() {
        markedWriterIndex = writerIndex;
    }

    @Override
    public void resetWriterIndex() {
        writerIndex = markedWriterIndex;
    }

    @Override
    public void discardReadBytes() {
        if (readerIndex == 0) {
            return;
        }
        setBytes(0, this, readerIndex, writerIndex - readerIndex);
        writerIndex -= readerIndex;
        markedReaderIndex = Math.max(markedReaderIndex - readerIndex, 0);
        markedWriterIndex = Math.max(markedWriterIndex - readerIndex, 0);
        readerIndex = 0;
    }

    @Override
    public void ensureWritableBytes(int writableBytes) {
        if (writableBytes > writableBytes()) {
            throw new IndexOutOfBoundsException();
        }
    }

    @Override
    public void getBytes(int index, byte[] dst) {
        getBytes(index, dst, 0, dst.length);
    }

    @Override
    public void getBytes(int index, ChannelBuffer dst) {
        getBytes(index, dst, dst.writableBytes());
    }

    @Override
    public void getBytes(int index, ChannelBuffer dst, int length) {
        if (length > dst.writableBytes()) {
            throw new IndexOutOfBoundsException();
        }
        getBytes(index, dst, dst.writerIndex(), length);
        dst.writerIndex(dst.writerIndex() + length);
    }

    @Override
    public void setBytes(int index, byte[] src) {
        setBytes(index, src, 0, src.length);
    }

    @Override
    public void setBytes(int index, ChannelBuffer src) {
        setBytes(index, src, src.readableBytes());
    }

    @Override
    public void setBytes(int index, ChannelBuffer src, int length) {
        if (length > src.readableBytes()) {
            throw new IndexOutOfBoundsException();
        }
        setBytes(index, src, src.readerIndex(), length);
        src.readerIndex(src.readerIndex() + length);
    }

    @Override
    public byte readByte() {
        if (readerIndex == writerIndex) {
            throw new IndexOutOfBoundsException();
        }
        return getByte(readerIndex++);
    }

    @Override
    public ChannelBuffer readBytes(int length) {
        checkReadableBytes(length);
        if (length == 0) {
            return ChannelBuffers.EMPTY_BUFFER;
        }
        ChannelBuffer buf = factory().getBuffer(length);
        buf.writeBytes(this, readerIndex, length);
        readerIndex += length;
        return buf;
    }

    @Override
    public void readBytes(byte[] dst, int dstIndex, int length) {
        checkReadableBytes(length);
        getBytes(readerIndex, dst, dstIndex, length);
        readerIndex += length;
    }

    @Override
    public void readBytes(byte[] dst) {
        readBytes(dst, 0, dst.length);
    }

    @Override
    public void readBytes(ChannelBuffer dst) {
        readBytes(dst, dst.writableBytes());
    }

    @Override
    public void readBytes(ChannelBuffer dst, int length) {
        if (length > dst.writableBytes()) {
            throw new IndexOutOfBoundsException();
        }
        readBytes(dst, dst.writerIndex(), length);
        dst.writerIndex(dst.writerIndex() + length);
    }

    @Override
    public void readBytes(ChannelBuffer dst, int dstIndex, int length) {
        checkReadableBytes(length);
        getBytes(readerIndex, dst, dstIndex, length);
        readerIndex += length;
    }

    @Override
    public void readBytes(ByteBuffer dst) {
        int length = dst.remaining();
        checkReadableBytes(length);
        getBytes(readerIndex, dst);
        readerIndex += length;
    }

    @Override
    public void readBytes(OutputStream out, int length) throws IOException {
        checkReadableBytes(length);
        getBytes(readerIndex, out, length);
        readerIndex += length;
    }

    @Override
    public void skipBytes(int length) {
        int newReaderIndex = readerIndex + length;
        if (newReaderIndex > writerIndex) {
            throw new IndexOutOfBoundsException();
        }
        readerIndex = newReaderIndex;
    }

    @Override
    public void writeByte(int value) {
        setByte(writerIndex++, value);
    }

    @Override
    public void writeBytes(byte[] src, int srcIndex, int length) {
        setBytes(writerIndex, src, srcIndex, length);
        writerIndex += length;
    }

    @Override
    public void writeBytes(byte[] src) {
        writeBytes(src, 0, src.length);
    }

    @Override
    public void writeBytes(ChannelBuffer src) {
        writeBytes(src, src.readableBytes());
    }

    @Override
    public void writeBytes(ChannelBuffer src, int length) {
        if (length > src.readableBytes()) {
            throw new IndexOutOfBoundsException();
        }
        writeBytes(src, src.readerIndex(), length);
        src.readerIndex(src.readerIndex() + length);
    }

    @Override
    public void writeBytes(ChannelBuffer src, int srcIndex, int length) {
        setBytes(writerIndex, src, srcIndex, length);
        writerIndex += length;
    }

    @Override
    public void writeBytes(ByteBuffer src) {
        int length = src.remaining();
        setBytes(writerIndex, src);
        writerIndex += length;
    }

    @Override
    public int writeBytes(InputStream in, int length) throws IOException {
        int writtenBytes = setBytes(writerIndex, in, length);
        if (writtenBytes > 0) {
            writerIndex += writtenBytes;
        }
        return writtenBytes;
    }

    @Override
    public ChannelBuffer copy() {
        return copy(readerIndex, readableBytes());
    }

    @Override
    public ByteBuffer toByteBuffer() {
        return toByteBuffer(readerIndex, readableBytes());
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ChannelBuffer
                && ChannelBuffers.equals(this, (ChannelBuffer) o);
    }

    @Override
    public int hashCode() {
        return ChannelBuffers.hasCode(this);
    }

    @Override
    public int compareTo(ChannelBuffer that) {
        return ChannelBuffers.compare(this, that);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + '(' +
                "ridx=" + readerIndex + ", " +
                "widx=" + writerIndex + ", " +
                "cap=" + capacity() +
                ')';
    }

    protected void checkReadableBytes(int minimumReadableBytes) {
        if (readableBytes() < minimumReadableBytes) {
            throw new IndexOutOfBoundsException();
        }
    }
}
