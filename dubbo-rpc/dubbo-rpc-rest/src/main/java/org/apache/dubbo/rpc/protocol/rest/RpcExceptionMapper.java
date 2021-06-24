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

import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.protocol.rest.support.ContentType;

import javax.validation.ConstraintViolation;
import javax.validation.ConstraintViolationException;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;

/**
 * 该类是异常的处理类。
 */
public class RpcExceptionMapper implements ExceptionMapper<RpcException> {

    @Override
    public Response toResponse(RpcException e) {
        // TODO do more sophisticated exception handling and output
        // 如果是约束违反异常
        if (e.getCause() instanceof ConstraintViolationException) {
            return handleConstraintViolationException((ConstraintViolationException) e.getCause());
        }
        // we may want to avoid exposing the dubbo exception details to certain clients
        // TODO for now just do plain text output
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity("Internal server error: " + e.getMessage()).type(ContentType.TEXT_PLAIN_UTF_8).build();
    }
    /**
     * 处理参数不合法的异常
     * @param cve
     * @return
     */
    protected Response handleConstraintViolationException(ConstraintViolationException cve) {
        // 创建约束违反记录
        ViolationReport report = new ViolationReport();
        // 遍历约束违反
        for (ConstraintViolation cv : cve.getConstraintViolations()) {
            // 添加记录
            report.addConstraintViolation(new RestConstraintViolation(
                    cv.getPropertyPath().toString(),
                    cv.getMessage(),
                    cv.getInvalidValue() == null ? "null" : cv.getInvalidValue().toString()));
        }
        // TODO for now just do xml output
        // 只支持xml输出
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(report).type(ContentType.TEXT_XML_UTF_8).build();
    }
}
