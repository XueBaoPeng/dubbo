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
package org.apache.dubbo.common.serialize;

import java.io.IOException;

/**
 * Basic data type output interface.
 * 该接口是数据输出接口，可以看到定义了向 InputStream 中，写入基本类型的数据。
 */
public interface DataOutput {

    /**
     * Write boolean.
     * 输出boolean类型
     * @param v value.
     * @throws IOException
     */
    void writeBool(boolean v) throws IOException;

    /**
     * Write byte.
     * 输出byte类型
     * @param v value.
     * @throws IOException
     */
    void writeByte(byte v) throws IOException;

    /**
     * Write short.
     * 输出short类型
     * @param v value.
     * @throws IOException
     */
    void writeShort(short v) throws IOException;

    /**
     * Write integer.
     * 输出integer类型
     * @param v value.
     * @throws IOException
     */
    void writeInt(int v) throws IOException;

    /**
     * Write long.
     * 输出long类型
     * @param v value.
     * @throws IOException
     */
    void writeLong(long v) throws IOException;

    /**
     * Write float.
     * 输出float类型
     * @param v value.
     * @throws IOException
     */
    void writeFloat(float v) throws IOException;

    /**
     * Write double.
     * 输出double类型
     * @param v value.
     * @throws IOException
     */
    void writeDouble(double v) throws IOException;

    /**
     * Write string.
     *
     * @param v value.
     * @throws IOException
     */
    void writeUTF(String v) throws IOException;

    /**
     * Write byte array.
     * 输出byte数组
     * @param v value.
     * @throws IOException
     */
    void writeBytes(byte[] v) throws IOException;

    /**
     * Write byte array.
     * 输出byte数组中部分数据
     * @param v value.
     * @param off the start offset in the data.
     * @param len the number of bytes that are written.
     * @throws IOException
     */
    void writeBytes(byte[] v, int off, int len) throws IOException;

    /**
     * Flush buffer.
     * 刷新缓冲区
     * @throws IOException
     */
    void flushBuffer() throws IOException;
}