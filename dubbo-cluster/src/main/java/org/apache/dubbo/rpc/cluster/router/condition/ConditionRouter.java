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
package org.apache.dubbo.rpc.cluster.router.condition;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.common.utils.UrlUtils;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.Constants;
import org.apache.dubbo.rpc.cluster.router.AbstractRouter;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.apache.dubbo.common.constants.CommonConstants.ENABLED_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.HOST_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.METHODS_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.METHOD_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.ADDRESS_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.FORCE_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.PRIORITY_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.RULE_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.RUNTIME_KEY;

/**
 * ConditionRouter
 * It supports the conditional routing configured by "override://", in 2.6.x,
 * refer to https://dubbo.apache.org/en/docs/v2.7/user/examples/routing-rule/ .
 * For 2.7.x and later, please refer to {@link org.apache.dubbo.rpc.cluster.router.condition.config.ServiceRouter}
 * and {@link org.apache.dubbo.rpc.cluster.router.condition.config.AppRouter}
 * refer to https://dubbo.apache.org/zh/docs/v2.7/user/examples/routing-rule/ .
 * 该类是基于条件表达式的路由实现类。关于给予条件表达式的路由规则，可以查看官方文档：
 */
public class ConditionRouter extends AbstractRouter {
    public static final String NAME = "condition";

    private static final Logger logger = LoggerFactory.getLogger(ConditionRouter.class);
    /**
     * 分组正则匹配
     */
    protected static final Pattern ROUTE_PATTERN = Pattern.compile("([&!=,]*)\\s*([^&!=,\\s]+)");
    protected static Pattern ARGUMENTS_PATTERN = Pattern.compile("arguments\\[([0-9]+)\\]");
    /**
     * 消费者匹配条件集合，通过解析【条件表达式 rule 的 `=>` 之前半部分】
     */
    protected Map<String, MatchPair> whenCondition;
    /**
     * 提供者地址列表的过滤条件，通过解析【条件表达式 rule 的 `=>` 之后半部分】
     */
    protected Map<String, MatchPair> thenCondition;

    private boolean enabled;

    public ConditionRouter(String rule, boolean force, boolean enabled) {
        this.force = force;
        this.enabled = enabled;
        if (enabled) {
            this.init(rule);
        }
    }

    public ConditionRouter(URL url) {
        this.url = url;
        // 获得优先级配置
        this.priority = url.getParameter(PRIORITY_KEY, 0);
        // 获得是否强制执行配置
        this.force = url.getParameter(FORCE_KEY, false);
        this.enabled = url.getParameter(ENABLED_KEY, true);
        if (enabled) {
            init(url.getParameterAndDecoded(RULE_KEY));
        }
    }

    public void init(String rule) {
        try {
            // 获得规则
            if (rule == null || rule.trim().length() == 0) {
                throw new IllegalArgumentException("Illegal route rule!");
            }
            rule = rule.replace("consumer.", "").replace("provider.", "");
            int i = rule.indexOf("=>");
            String whenRule = i < 0 ? null : rule.substring(0, i).trim();
            String thenRule = i < 0 ? rule.trim() : rule.substring(i + 2).trim();
            // 分割消费者和提供者规则
            Map<String, MatchPair> when = StringUtils.isBlank(whenRule) || "true".equals(whenRule) ? new HashMap<String, MatchPair>() : parseRule(whenRule);
            Map<String, MatchPair> then = StringUtils.isBlank(thenRule) || "false".equals(thenRule) ? null : parseRule(thenRule);
            // NOTE: It should be determined on the business level whether the `When condition` can be empty or not.
            this.whenCondition = when;
            this.thenCondition = then;
        } catch (ParseException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    /**
     * 该方法是根据规则解析路由配置内容。具体的可以参照官网的配置规则来解读这里每一个分割取值作为条件的过程。
     * @param rule
     * @return
     * @throws ParseException
     */
    private static Map<String, MatchPair> parseRule(String rule)
            throws ParseException {
        Map<String, MatchPair> condition = new HashMap<String, MatchPair>();
        // 如果规则为空，则直接返回空
        if (StringUtils.isBlank(rule)) {
            return condition;
        }
        // Key-Value pair, stores both match and mismatch conditions
        MatchPair pair = null;
        // Multiple values
        Set<String> values = null;
        // 正则表达式匹配
        final Matcher matcher = ROUTE_PATTERN.matcher(rule);
        while (matcher.find()) { // Try to match one by one
            // 一个一个匹配
            String separator = matcher.group(1);
            String content = matcher.group(2);
            // Start part of the condition expression.
            // 开始条件表达式
            if (StringUtils.isEmpty(separator)) {
                pair = new MatchPair();
                // 保存条件
                condition.put(content, pair);
            }
            // The KV part of the condition expression
            else if ("&".equals(separator)) {
                // 把参数的条件表达式放入condition
                if (condition.get(content) == null) {
                    pair = new MatchPair();
                    condition.put(content, pair);
                } else {
                    pair = condition.get(content);
                }
            }
            // The Value in the KV part.
            // 把值放入values
            else if ("=".equals(separator)) {
                if (pair == null) {
                    throw new ParseException("Illegal route rule \""
                            + rule + "\", The error char '" + separator
                            + "' at index " + matcher.start() + " before \""
                            + content + "\".", matcher.start());
                }

                values = pair.matches;
                values.add(content);
            }
            // The Value in the KV part.
            // 把不等于的条件限制也放入values
            else if ("!=".equals(separator)) {
                if (pair == null) {
                    throw new ParseException("Illegal route rule \""
                            + rule + "\", The error char '" + separator
                            + "' at index " + matcher.start() + " before \""
                            + content + "\".", matcher.start());
                }

                values = pair.mismatches;
                values.add(content);
            }
            // The Value in the KV part, if Value have more than one items.
            else if (",".equals(separator)) { // Should be separated by ','
                // 如果以.分隔的也放入values
                if (values == null || values.isEmpty()) {
                    throw new ParseException("Illegal route rule \""
                            + rule + "\", The error char '" + separator
                            + "' at index " + matcher.start() + " before \""
                            + content + "\".", matcher.start());
                }
                values.add(content);
            } else {
                throw new ParseException("Illegal route rule \"" + rule
                        + "\", The error char '" + separator + "' at index "
                        + matcher.start() + " before \"" + content + "\".", matcher.start());
            }
        }
        return condition;
    }

    /**
     * 该方法是进行路由规则的匹配，分别对消费者和提供者进行匹配。
     * @param invokers   invoker list
     * @param url        refer url
     * @param invocation invocation
     * @param <T>
     * @return
     * @throws RpcException
     */
    @Override
    public <T> List<Invoker<T>> route(List<Invoker<T>> invokers, URL url, Invocation invocation)
            throws RpcException {
        // 为空，直接返回空 Invoker 集合
        if (!enabled) {
            return invokers;
        }

        if (CollectionUtils.isEmpty(invokers)) {
            return invokers;
        }
        try {
            // 如果不匹配 `whenCondition` ，直接返回 `invokers` 集合，因为不需要走 `whenThen` 的匹配
            if (!matchWhen(url, invocation)) {
                return invokers;
            }
            List<Invoker<T>> result = new ArrayList<Invoker<T>>();
            // 如果thenCondition为空，则直接返回空
            if (thenCondition == null) {
                logger.warn("The current consumer in the service blacklist. consumer: " + NetUtils.getLocalHost() + ", service: " + url.getServiceKey());
                return result;
            }
            // 遍历invokers
            for (Invoker<T> invoker : invokers) {
                // 如果thenCondition匹配，则加入result
                if (matchThen(invoker.getUrl(), url)) {
                    result.add(invoker);
                }
            }
            if (!result.isEmpty()) {
                return result;
            } else if (force) {
                logger.warn("The route result is empty and force execute. consumer: " + NetUtils.getLocalHost() + ", service: " + url.getServiceKey() + ", router: " + url.getParameterAndDecoded(RULE_KEY));
                return result;
            }
        } catch (Throwable t) {
            logger.error("Failed to execute condition router rule: " + getUrl() + ", invokers: " + invokers + ", cause: " + t.getMessage(), t);
        }
        return invokers;
    }

    @Override
    public boolean isRuntime() {
        // We always return true for previously defined Router, that is, old Router doesn't support cache anymore.
//        return true;
        return this.url.getParameter(RUNTIME_KEY, false);
    }

    @Override
    public URL getUrl() {
        return url;
    }

    boolean matchWhen(URL url, Invocation invocation) {
        return CollectionUtils.isEmptyMap(whenCondition) || matchCondition(whenCondition, url, null, invocation);
    }

    private boolean matchThen(URL url, URL param) {
        return CollectionUtils.isNotEmptyMap(thenCondition) && matchCondition(thenCondition, url, param, null);
    }

    /**
     * 该方法是匹配条件的主要逻辑。
     * @param condition
     * @param url
     * @param param
     * @param invocation
     * @return
     */
    private boolean matchCondition(Map<String, MatchPair> condition, URL url, URL param, Invocation invocation) {
        Map<String, String> sample = url.toMap();
        // 是否匹配
        boolean result = false;
        // 遍历条件
        for (Map.Entry<String, MatchPair> matchPair : condition.entrySet()) {
            String key = matchPair.getKey();

            if (key.startsWith(Constants.ARGUMENTS)) {
                if (!matchArguments(matchPair, invocation)) {
                    return false;
                } else {
                    result = true;
                    continue;
                }
            }

            String sampleValue;
            //get real invoked method name from invocation
            // 获得方法名
            if (invocation != null && (METHOD_KEY.equals(key) || METHODS_KEY.equals(key))) {
                sampleValue = invocation.getMethodName();
            } else if (ADDRESS_KEY.equals(key)) {
                sampleValue = url.getAddress();
            } else if (HOST_KEY.equals(key)) {
                sampleValue = url.getHost();
            } else {
                sampleValue = sample.get(key);
                if (sampleValue == null) {
                    sampleValue = sample.get(key);
                }
            }
            if (sampleValue != null) {
                // 如果不匹配条件值，返回false
                if (!matchPair.getValue().isMatch(sampleValue, param)) {
                    return false;
                } else {
                    // 匹配则返回true
                    result = true;
                }
            } else {
                //not pass the condition
                // 如果匹配的集合不为空
                if (!matchPair.getValue().matches.isEmpty()) {
                    // 返回false
                    return false;
                } else {
                    // 返回true
                    result = true;
                }
            }
        }
        return result;
    }

    /**
     * analysis the arguments in the rule.
     * Examples would be like this:
     * "arguments[0]=1", whenCondition is that the first argument is equal to '1'.
     * "arguments[1]=a", whenCondition is that the second argument is equal to 'a'.
     * @param matchPair
     * @param invocation
     * @return
     */
    public boolean matchArguments(Map.Entry<String, MatchPair> matchPair, Invocation invocation) {
        try {
            // split the rule
            String key = matchPair.getKey();
            String[] expressArray = key.split("\\.");
            String argumentExpress = expressArray[0];
            final Matcher matcher = ARGUMENTS_PATTERN.matcher(argumentExpress);
            if (!matcher.find()) {
                return false;
            }

            //extract the argument index
            int index = Integer.parseInt(matcher.group(1));
            if (index < 0 || index > invocation.getArguments().length) {
                return false;
            }

            //extract the argument value
            Object object = invocation.getArguments()[index];

            if (matchPair.getValue().isMatch(String.valueOf(object), null)) {
                return true;
            }
        } catch (Exception e) {
            logger.warn("Arguments match failed, matchPair[]" + matchPair + "] invocation[" + invocation + "]", e);
        }

        return false;
    }

    /**
     * 该类是内部类，封装了匹配的值，每个属性条件。并且提供了判断是否匹配的方法。
     */
    protected static final class MatchPair {
        /**
         * 匹配的值的集合
         */
        final Set<String> matches = new HashSet<String>();
        /**
         * 不匹配的值的集合
         */
        final Set<String> mismatches = new HashSet<String>();
        /**
         * 判断value是否匹配matches或者mismatches
         * @param value
         * @param param
         * @return
         */
        private boolean isMatch(String value, URL param) {
            // 只匹配 matches
            if (!matches.isEmpty() && mismatches.isEmpty()) {
                for (String match : matches) {
                    if (UrlUtils.isMatchGlobPattern(match, value, param)) {
                        // 匹配上了返回true
                        return true;
                    }
                }
                // 没匹配上则为false
                return false;
            }
            // 只匹配 mismatches
            if (!mismatches.isEmpty() && matches.isEmpty()) {
                for (String mismatch : mismatches) {
                    if (UrlUtils.isMatchGlobPattern(mismatch, value, param)) {
                        // 如果匹配上了，则返回false
                        return false;
                    }
                }
                // 没匹配上，则为true
                return true;
            }
            // 匹配 matches和mismatches
            if (!matches.isEmpty() && !mismatches.isEmpty()) {
                //when both mismatches and matches contain the same value, then using mismatches first
                for (String mismatch : mismatches) {
                    if (UrlUtils.isMatchGlobPattern(mismatch, value, param)) {
                        // 匹配上则为false
                        return false;
                    }
                }
                for (String match : matches) {
                    if (UrlUtils.isMatchGlobPattern(match, value, param)) {
                        // 匹配上则为true
                        return true;
                    }
                }
                return false;
            }
            return false;
        }
    }
}
