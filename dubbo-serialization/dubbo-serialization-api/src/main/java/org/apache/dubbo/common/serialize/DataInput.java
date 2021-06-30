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
 * Basic data type input interface.
 * 该接口是数据输入接口，可以看到定义了从 InputStream 中各类数据类型的读取方法。
 */
public interface DataInput {

    /**
     * Read boolean.
     * 读取布尔类型
     * @return boolean.
     * @throws IOException
     */
    boolean readBool() throws IOException;

    /**
     * Read byte.
     * 读取字节
     * @return byte value.
     * @throws IOException
     */
    byte readByte() throws IOException;

    /**
     * Read short integer.
     * 读取short类型
     * @return short.
     * @throws IOException
     */
    short readShort() throws IOException;

    /**
     * Read integer.
     * 读取integer类型
     * @return integer.
     * @throws IOException
     */
    int readInt() throws IOException;

    /**
     * Read long.
     * 读取long类型
     * @return long.
     * @throws IOException
     */
    long readLong() throws IOException;

    /**
     * Read float.
     * 读取float类型
     * @return float.
     * @throws IOException
     */
    float readFloat() throws IOException;

    /**
     * Read double.
     * 读取double类型
     * @return double.
     * @throws IOException
     */
    double readDouble() throws IOException;

    /**
     * Read UTF-8 string.
     * 读取UTF-8 string
     * @return string.
     * @throws IOException
     */
    String readUTF() throws IOException;

    /**
     * Read byte array.
     * 读取byte数组
     * @return byte array.
     * @throws IOException
     */
    byte[] readBytes() throws IOException;
}