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
package org.apache.dubbo.rpc.cluster.router.script;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.router.AbstractRouter;

import javax.script.Bindings;
import javax.script.Compilable;
import javax.script.CompiledScript;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.security.AccessControlContext;
import java.security.AccessController;
import java.security.CodeSource;
import java.security.Permissions;
import java.security.PrivilegedAction;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static org.apache.dubbo.rpc.cluster.Constants.DEFAULT_SCRIPT_TYPE_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.FORCE_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.PRIORITY_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.RULE_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.RUNTIME_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.TYPE_KEY;

/**
 * ScriptRouter
 * 该类是基于脚本的路由实现类
 */
public class ScriptRouter extends AbstractRouter {

    public static final String NAME = "SCRIPT_ROUTER";
    private static final int SCRIPT_ROUTER_DEFAULT_PRIORITY = 0;
    private static final Logger logger = LoggerFactory.getLogger(ScriptRouter.class);
    /**
     * 脚本类型 与 ScriptEngine 的映射缓存
     */
    private static final Map<String, ScriptEngine> ENGINES = new ConcurrentHashMap<>();
    /**
     * 脚本
     */
    private final ScriptEngine engine;
    /**
     * 路由规则
     */
    private final String rule;

    private CompiledScript function;

    private AccessControlContext accessControlContext;

    {
        //Just give permission of reflect to access member.
        Permissions perms = new Permissions();
        perms.add(new RuntimePermission("accessDeclaredMembers"));
        // Cast to Certificate[] required because of ambiguity:
        ProtectionDomain domain = new ProtectionDomain(
                new CodeSource(null, (Certificate[]) null), perms);
        accessControlContext = new AccessControlContext(
                new ProtectionDomain[]{domain});
    }

    public ScriptRouter(URL url) {
        this.url = url;
        this.priority = url.getParameter(PRIORITY_KEY, SCRIPT_ROUTER_DEFAULT_PRIORITY);
        // 创建脚本
        engine = getEngine(url);
        rule = getRule(url);
        try {
            Compilable compilable = (Compilable) engine;
            // 编译脚本
            function = compilable.compile(rule);
        } catch (ScriptException e) {
            logger.error("route error, rule has been ignored. rule: " + rule +
                    ", url: " + RpcContext.getContext().getUrl(), e);
        }
    }

    /**
     * get rule from url parameters.
     */
    private String getRule(URL url) {
        String vRule = url.getParameterAndDecoded(RULE_KEY);
        if (StringUtils.isEmpty(vRule)) {
            throw new IllegalStateException("route rule can not be empty.");
        }
        return vRule;
    }

    /**
     * create ScriptEngine instance by type from url parameters, then cache it
     */
    private ScriptEngine getEngine(URL url) {
        String type = url.getParameter(TYPE_KEY, DEFAULT_SCRIPT_TYPE_KEY);

        return ENGINES.computeIfAbsent(type, t -> {
            ScriptEngine scriptEngine = new ScriptEngineManager().getEngineByName(type);
            if (scriptEngine == null) {
                throw new IllegalStateException("unsupported route engine type: " + type);
            }
            return scriptEngine;
        });
    }

    /**
     * 该方法是根据路由规则选择invoker的实现逻辑。
     * @param invokers   invoker list
     * @param url        refer url
     * @param invocation invocation
     * @param <T>
     * @return
     * @throws RpcException
     */
    @Override
    public <T> List<Invoker<T>> route(List<Invoker<T>> invokers, URL url, Invocation invocation) throws RpcException {
        if (engine == null || function == null) {
            return invokers;
        }
        // 创建脚本
        Bindings bindings = createBindings(invokers, invocation);
        return getRoutedInvokers(AccessController.doPrivileged(new PrivilegedAction() {
            @Override
            public Object run() {
                try {
                    // 执行脚本
                    return function.eval(bindings);
                } catch (ScriptException e) {
                    // 发生异常，忽略路由规则，返回全 `invokers` 集合
                    logger.error("route error, rule has been ignored. rule: " + rule + ", method:" +
                            invocation.getMethodName() + ", url: " + RpcContext.getContext().getUrl(), e);
                    return invokers;
                }
            }
        }, accessControlContext));

    }

    /**
     * get routed invokers from result of script rule evaluation
     */
    @SuppressWarnings("unchecked")
    protected <T> List<Invoker<T>> getRoutedInvokers(Object obj) {
        // 根据结果类型，转换成 (List<Invoker<T>> 类型返回
        if (obj instanceof Invoker[]) {
            return Arrays.asList((Invoker<T>[]) obj);
        } else if (obj instanceof Object[]) {
            return Arrays.stream((Object[]) obj).map(item -> (Invoker<T>) item).collect(Collectors.toList());
        } else {
            return (List<Invoker<T>>) obj;
        }
    }

    /**
     * create bindings for script engine
     */
    private <T> Bindings createBindings(List<Invoker<T>> invokers, Invocation invocation) {
        Bindings bindings = engine.createBindings();
        // create a new List of invokers
        // 设置invokers、invocation、context
        bindings.put("invokers", new ArrayList<>(invokers));
        bindings.put("invocation", invocation);
        bindings.put("context", RpcContext.getContext());
        return bindings;
    }

    @Override
    public boolean isRuntime() {
        return this.url.getParameter(RUNTIME_KEY, false);
    }

    @Override
    public boolean isForce() {
        return url.getParameter(FORCE_KEY, false);
    }

}
