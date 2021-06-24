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
package org.apache.dubbo.rpc.protocol.rest;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlRootElement;
import java.io.Serializable;

/**
 * 该类是当约束违反的实体类，封装了以下三个属性，具体使用可以看下面的介绍。
 */
@XmlRootElement(name = "constraintViolation")
@XmlAccessorType(XmlAccessType.FIELD)
public class RestConstraintViolation implements Serializable {

    private static final long serialVersionUID = -23497234978L;

    /**
     * 地址
     */
    private String path;
    /**
     * 消息
     */
    private String message;
    /**
     * 值
     */
    private String value;

    public RestConstraintViolation(String path, String message, String value) {
        this.path = path;
        this.message = message;
        this.value = value;
    }

    public RestConstraintViolation() {
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }
}
