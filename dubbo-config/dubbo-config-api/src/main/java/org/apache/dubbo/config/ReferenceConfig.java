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
package org.apache.dubbo.config;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.bytecode.Wrapper;
import org.apache.dubbo.common.constants.RegistryConstants;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.common.utils.UrlUtils;
import org.apache.dubbo.config.annotation.Reference;
import org.apache.dubbo.config.bootstrap.DubboBootstrap;
import org.apache.dubbo.config.event.ReferenceConfigDestroyedEvent;
import org.apache.dubbo.config.event.ReferenceConfigInitializedEvent;
import org.apache.dubbo.config.support.Parameter;
import org.apache.dubbo.config.utils.ConfigValidationUtils;
import org.apache.dubbo.event.Event;
import org.apache.dubbo.event.EventDispatcher;
import org.apache.dubbo.registry.client.metadata.MetadataUtils;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.ProxyFactory;
import org.apache.dubbo.rpc.cluster.Cluster;
import org.apache.dubbo.rpc.cluster.directory.StaticDirectory;
import org.apache.dubbo.rpc.cluster.support.ClusterUtils;
import org.apache.dubbo.rpc.cluster.support.registry.ZoneAwareCluster;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.model.AsyncMethodInfo;
import org.apache.dubbo.rpc.model.ConsumerModel;
import org.apache.dubbo.rpc.model.ServiceDescriptor;
import org.apache.dubbo.rpc.model.ServiceRepository;
import org.apache.dubbo.rpc.protocol.injvm.InjvmProtocol;
import org.apache.dubbo.rpc.service.GenericService;
import org.apache.dubbo.rpc.support.ProtocolUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.apache.dubbo.common.constants.CommonConstants.ANY_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.CLUSTER_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.COMMA_SEPARATOR;
import static org.apache.dubbo.common.constants.CommonConstants.COMMA_SEPARATOR_CHAR;
import static org.apache.dubbo.common.constants.CommonConstants.CONSUMER_SIDE;
import static org.apache.dubbo.common.constants.CommonConstants.INTERFACE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.LOCALHOST_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.METADATA_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.METHODS_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.MONITOR_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PROXY_CLASS_REF;
import static org.apache.dubbo.common.constants.CommonConstants.REMOTE_METADATA_STORAGE_TYPE;
import static org.apache.dubbo.common.constants.CommonConstants.REVISION_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.SEMICOLON_SPLIT_PATTERN;
import static org.apache.dubbo.common.constants.CommonConstants.SIDE_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.SUBSCRIBED_SERVICE_NAMES_KEY;
import static org.apache.dubbo.common.utils.NetUtils.isInvalidLocalHost;
import static org.apache.dubbo.common.utils.StringUtils.splitToSet;
import static org.apache.dubbo.config.Constants.DUBBO_IP_TO_REGISTRY;
import static org.apache.dubbo.registry.Constants.CONSUMER_PROTOCOL;
import static org.apache.dubbo.registry.Constants.REGISTER_IP_KEY;
import static org.apache.dubbo.rpc.Constants.LOCAL_PROTOCOL;
import static org.apache.dubbo.rpc.cluster.Constants.REFER_KEY;

/**
 * Please avoid using this class for any new application,
 * use {@link ReferenceConfigBase} instead.
 */
public class ReferenceConfig<T> extends ReferenceConfigBase<T> {

    public static final Logger logger = LoggerFactory.getLogger(ReferenceConfig.class);

    /**
     * The {@link Protocol} implementation with adaptive functionality,it will be different in different scenarios.
     * A particular {@link Protocol} implementation is determined by the protocol attribute in the {@link URL}.
     * For example:
     *
     * <li>when the url is registry://224.5.6.7:1234/org.apache.dubbo.registry.RegistryService?application=dubbo-sample,
     * then the protocol is <b>RegistryProtocol</b></li>
     *
     * <li>when the url is dubbo://224.5.6.7:1234/org.apache.dubbo.config.api.DemoService?application=dubbo-sample, then
     * the protocol is <b>DubboProtocol</b></li>
     * <p>
     * Actually，when the {@link ExtensionLoader} init the {@link Protocol} instants,it will automatically wraps two
     * layers, and eventually will get a <b>ProtocolFilterWrapper</b> or <b>ProtocolListenerWrapper</b>
     */
    private static final Protocol REF_PROTOCOL = ExtensionLoader.getExtensionLoader(Protocol.class).getAdaptiveExtension();

    /**
     * The {@link Cluster}'s implementation with adaptive functionality, and actually it will get a {@link Cluster}'s
     * specific implementation who is wrapped with <b>MockClusterInvoker</b>
     */
    private static final Cluster CLUSTER = ExtensionLoader.getExtensionLoader(Cluster.class).getAdaptiveExtension();

    /**
     * A {@link ProxyFactory} implementation that will generate a reference service's proxy,the JavassistProxyFactory is
     * its default implementation
     */
    private static final ProxyFactory PROXY_FACTORY = ExtensionLoader.getExtensionLoader(ProxyFactory.class).getAdaptiveExtension();

    /**
     * The interface proxy reference
     */
    private transient volatile T ref;

    /**
     * The invoker of the reference service
     */
    private transient volatile Invoker<?> invoker;

    /**
     * The flag whether the ReferenceConfig has been initialized
     */
    private transient volatile boolean initialized;

    /**
     * whether this ReferenceConfig has been destroyed
     */
    private transient volatile boolean destroyed;

    private final ServiceRepository repository;

    private DubboBootstrap bootstrap;

    /**
     * The service names that the Dubbo interface subscribed.
     *
     * @since 2.7.8
     */
    private String services;

    public ReferenceConfig() {
        super();
        this.repository = ApplicationModel.getServiceRepository();
    }

    public ReferenceConfig(Reference reference) {
        super(reference);
        this.repository = ApplicationModel.getServiceRepository();
    }

    /**
     * Get a string presenting the service names that the Dubbo interface subscribed.
     * If it is a multiple-values, the content will be a comma-delimited String.
     *
     * @return non-null
     * @see RegistryConstants#SUBSCRIBED_SERVICE_NAMES_KEY
     * @since 2.7.8
     */
    @Deprecated
    @Parameter(key = SUBSCRIBED_SERVICE_NAMES_KEY)
    public String getServices() {
        return services;
    }

    /**
     * It's an alias method for {@link #getServices()}, but the more convenient.
     *
     * @return the String {@link List} presenting the Dubbo interface subscribed
     * @since 2.7.8
     */
    @Deprecated
    @Parameter(excluded = true)
    public Set<String> getSubscribedServices() {
        return splitToSet(getServices(), COMMA_SEPARATOR_CHAR);
    }

    /**
     * Set the service names that the Dubbo interface subscribed.
     *
     * @param services If it is a multiple-values, the content will be a comma-delimited String.
     * @since 2.7.8
     */
    public void setServices(String services) {
        this.services = services;
    }

    public synchronized T get() {
        // 如果被销毁，则抛出异常
        if (destroyed) {
            throw new IllegalStateException("The invoker of ReferenceConfig(" + url + ") has already destroyed!");
        }
        // 检测 代理对象ref 是否为空，为空则通过 init 方法创建
        if (ref == null) {
            // 用于处理配置，以及调用 createProxy 生成代理类
            init();
        }
        return ref;
    }

    @Override
    public synchronized void destroy() {
        if (ref == null) {
            return;
        }
        if (destroyed) {
            return;
        }
        destroyed = true;
        try {
            invoker.destroy();
        } catch (Throwable t) {
            logger.warn("Unexpected error occurred when destroy invoker of ReferenceConfig(" + url + ").", t);
        }
        invoker = null;
        ref = null;

        // dispatch a ReferenceConfigDestroyedEvent since 2.7.4
        dispatch(new ReferenceConfigDestroyedEvent(this));
    }

    /**
     * 该方法大致分为以下几个步骤：
     *
     * 检测本地存根和mock合法性。
     * 添加协议版本、发布版本，时间戳、metrics、application、module、consumer、protocol等的所有信息到 map 中
     * 单独处理方法配置，设置重试次数配置以及设置该方法对异步配置信息。
     * 添加消费者ip地址到map
     * 创建代理对象
     * 生成ConsumerModel存入到 ApplicationModel 中
     */
    public synchronized void init() {
        // 如果已经初始化过，则结束
        if (initialized) {
            return;
        }


        if (bootstrap == null) {
            bootstrap = DubboBootstrap.getInstance();
            // compatible with api call.
            if (null != this.getRegistries()) {
                bootstrap.registries(this.getRegistries());
            }
            bootstrap.initialize();
        }
        // 检查并且更新配置
        checkAndUpdateSubConfigs();

        // 本地存根合法性校验
        checkStubAndLocal(interfaceClass);
        // mock合法性校验
        ConfigValidationUtils.checkMock(interfaceClass, this);
        // 用来存放配置
        Map<String, String> map = new HashMap<String, String>();
        // 存放这是消费者侧
        map.put(SIDE_KEY, CONSUMER_SIDE);
        // 添加 协议版本、发布版本，时间戳 等信息到 map 中
        ReferenceConfigBase.appendRuntimeParameters(map);
        // 如果是泛化调用
        if (!ProtocolUtils.isGeneric(generic)) {
            // 获得版本号
            String revision = Version.getVersion(interfaceClass, version);
            if (revision != null && revision.length() > 0) {
                // 设置版本号
                map.put(REVISION_KEY, revision);
            }

            // 获得所有方法
            String[] methods = Wrapper.getWrapper(interfaceClass).getMethodNames();
            if (methods.length == 0) {
                logger.warn("No method found in service interface " + interfaceClass.getName());
                map.put(METHODS_KEY, ANY_VALUE);
            } else {
                // 把所有方法签名拼接起来放入map
                map.put(METHODS_KEY, StringUtils.join(new HashSet<String>(Arrays.asList(methods)), COMMA_SEPARATOR));
            }
        }
        // 加入服务接口名称
        map.put(INTERFACE_KEY, interfaceName);
        // 添加metrics、application、module、consumer、protocol的所有信息到map
        AbstractConfig.appendParameters(map, getMetrics());
        AbstractConfig.appendParameters(map, getApplication());
        AbstractConfig.appendParameters(map, getModule());
        // remove 'default.' prefix for configs from ConsumerConfig
        // appendParameters(map, consumer, Constants.DEFAULT_KEY);
        AbstractConfig.appendParameters(map, consumer);
        AbstractConfig.appendParameters(map, this);
        MetadataReportConfig metadataReportConfig = getMetadataReportConfig();
        if (metadataReportConfig != null && metadataReportConfig.isValid()) {
            map.putIfAbsent(METADATA_KEY, REMOTE_METADATA_STORAGE_TYPE);
        }
        Map<String, AsyncMethodInfo> attributes = null;
        if (CollectionUtils.isNotEmpty(getMethods())) {
            attributes = new HashMap<>();
            // 遍历方法配置
            for (MethodConfig methodConfig : getMethods()) {
                AbstractConfig.appendParameters(map, methodConfig, methodConfig.getName());
                String retryKey = methodConfig.getName() + ".retry";
                if (map.containsKey(retryKey)) {
                    String retryValue = map.remove(retryKey);
                    // 如果配置为false，也就是不重试，则设置重试次数为0次
                    if ("false".equals(retryValue)) {
                        map.put(methodConfig.getName() + ".retries", "0");
                    }
                }
                AsyncMethodInfo asyncMethodInfo = AbstractConfig.convertMethodConfig2AsyncInfo(methodConfig);
                if (asyncMethodInfo != null) {
//                    consumerModel.getMethodModel(methodConfig.getName()).addAttribute(ASYNC_KEY, asyncMethodInfo);
                    attributes.put(methodConfig.getName(), asyncMethodInfo);
                }
            }
        }
        // 获取服务消费者 ip 地址
        String hostToRegistry = ConfigUtils.getSystemProperty(DUBBO_IP_TO_REGISTRY);
        // 如果为空，则获取本地ip
        if (StringUtils.isEmpty(hostToRegistry)) {
            hostToRegistry = NetUtils.getLocalHost();
        } else if (isInvalidLocalHost(hostToRegistry)) {
            throw new IllegalArgumentException(
                    "Specified invalid registry ip from property:" + DUBBO_IP_TO_REGISTRY + ", value:" + hostToRegistry);
        }
        // 设置消费者ip
        map.put(REGISTER_IP_KEY, hostToRegistry);

        serviceMetadata.getAttachments().putAll(map);

        // 创建代理对象
        ref = createProxy(map);

        serviceMetadata.setTarget(ref);
        serviceMetadata.addAttribute(PROXY_CLASS_REF, ref);
        // 生产服务key
        ConsumerModel consumerModel = repository.lookupReferredService(serviceMetadata.getServiceKey());
        consumerModel.setProxyObject(ref);
        consumerModel.init(attributes);

        // 设置初始化标志为true
        initialized = true;

        checkInvokerAvailable();

        // dispatch a ReferenceConfigInitializedEvent since 2.7.4
        dispatch(new ReferenceConfigInitializedEvent(this, invoker));
    }

    /**
     * 如果是本地调用，则直接使用InjvmProtocol 的 refer 方法生成 Invoker 实例。
     * 如果不是本地调用，但是是选择直连的方式来进行调用，则分割配置的多个url。如果协议是配置是registry，则表明用户想使用指定的注册中心，
     * 配置url后将url并且保存到urls里面，否则就合并url，并且保存到urls。
     * 如果是通过注册中心来进行调用，则先校验所有的注册中心，然后加载注册中心的url，遍历每个url，加入监控中心url配置，最后把每个url保存到urls。
     * 针对urls集合的数量，如果是单注册中心，直接引用RegistryProtocol 的 refer 构建 Invoker 实例，如果是多注册中心，则对每个url都生成Invoker，
     * 利用集群进行多个Invoker合并。
     * 最终输出一个invoker。
     * @param map
     * @return
     */
    @SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
    private T createProxy(Map<String, String> map) {
        // 根据配置检查是否为本地调用
        if (shouldJvmRefer(map)) {
            // 生成url，protocol使用的是injvm
            URL url = new URL(LOCAL_PROTOCOL, LOCALHOST_VALUE, 0, interfaceClass.getName()).addParameters(map);
            // 利用InjvmProtocol 的 refer 方法生成 InjvmInvoker 实例
            invoker = REF_PROTOCOL.refer(interfaceClass, url);
            if (logger.isInfoEnabled()) {
                logger.info("Using injvm service " + interfaceClass.getName());
            }
        } else {
            urls.clear();
            // 如果url不为空，则用户可能想进行直连来调用
            if (url != null && url.length() > 0) { // user specified URL, could be peer-to-peer address, or register center's address.
                // 当需要配置多个 url 时，可用分号进行分割，这里会进行切分
                String[] us = SEMICOLON_SPLIT_PATTERN.split(url);
                // 遍历所有的url
                if (us != null && us.length > 0) {
                    for (String u : us) {
                        URL url = URL.valueOf(u);
                        if (StringUtils.isEmpty(url.getPath())) {
                            // 设置接口全限定名为 url 路径
                            url = url.setPath(interfaceName);
                        }
                        // 检测 url 协议是否为 registry，若是，表明用户想使用指定的注册中心
                        if (UrlUtils.isRegistry(url)) {
                            // 将 map 转换为查询字符串，并作为 refer 参数的值添加到 url 中
                            urls.add(url.addParameterAndEncoded(REFER_KEY, StringUtils.toQueryString(map)));
                        } else {
                            // 合并 url，移除服务提供者的一些配置（这些配置来源于用户配置的 url 属性），
                            // 比如线程池相关配置。并保留服务提供者的部分配置，比如版本，group，时间戳等
                            // 最后将合并后的配置设置为 url 查询字符串中。
                            urls.add(ClusterUtils.mergeUrl(url, map));
                        }
                    }
                }
            } else { // assemble URL from register center's configuration
                // if protocols not injvm checkRegistry
                if (!LOCAL_PROTOCOL.equalsIgnoreCase(getProtocol())) {
                    // 校验注册中心
                    checkRegistry();
                    // 加载注册中心的url
                    List<URL> us = ConfigValidationUtils.loadRegistries(this, false);
                    if (CollectionUtils.isNotEmpty(us)) {
                        // 遍历所有的注册中心
                        for (URL u : us) {
                            // 生成监控url
                            URL monitorUrl = ConfigValidationUtils.loadMonitor(this, u);
                            if (monitorUrl != null) {
                                // 加入监控中心url的配置
                                map.put(MONITOR_KEY, URL.encode(monitorUrl.toFullString()));
                            }
                            // 添加 refer 参数到 url 中，并将 url 添加到 urls 中
                            urls.add(u.addParameterAndEncoded(REFER_KEY, StringUtils.toQueryString(map)));
                        }
                    }
                    // 如果urls为空，则抛出异常
                    if (urls.isEmpty()) {
                        throw new IllegalStateException(
                                "No such any registry to reference " + interfaceName + " on the consumer " + NetUtils.getLocalHost() +
                                        " use dubbo version " + Version.getVersion() +
                                        ", please config <dubbo:registry address=\"...\" /> to your spring config.");
                    }
                }
            }

            // 如果只有一个注册中心，则直接调用refer方法
            if (urls.size() == 1) {
                // 调用 RegistryProtocol 的 refer 构建 Invoker 实例
                invoker = REF_PROTOCOL.refer(interfaceClass, urls.get(0));
            } else {
                List<Invoker<?>> invokers = new ArrayList<Invoker<?>>();
                URL registryURL = null;
                // 遍历所有的注册中心url
                for (URL url : urls) {
                    // 通过 refprotocol 调用 refer 构建 Invoker，
                    // refprotocol 会在运行时根据 url 协议头加载指定的 Protocol 实例，并调用实例的 refer 方法
                    // 把生成的Invoker加入到集合中
                    Invoker<?> referInvoker = REF_PROTOCOL.refer(interfaceClass, url);
                    if (shouldCheck()) {
                        if (referInvoker.isAvailable()) {
                            invokers.add(referInvoker);
                        } else {
                            referInvoker.destroy();
                        }
                    } else {
                        invokers.add(referInvoker);
                    }

                    // 如果是注册中心的协议
                    if (UrlUtils.isRegistry(url)) {
                        // 则设置registryURL
                        registryURL = url; // use last registry url
                    }
                }

                // 如果需要核对该服务是否可用，并且该服务不可用
                if (shouldCheck() && invokers.size() == 0) {
                    // 抛出异常
                    throw new IllegalStateException("Failed to check the status of the service "
                            + interfaceName
                            + ". No provider available for the service "
                            + (group == null ? "" : group + "/")
                            + interfaceName +
                            (version == null ? "" : ":" + version)
                            + " from the multi registry cluster"
                            + " use dubbo version " + Version.getVersion());
                }

                // 优先用注册中心的url
                if (registryURL != null) { // registry url is available
                    // for multi-subscription scenario, use 'zone-aware' policy by default
                    // 只有当注册中心当链接可用当时候，采用RegistryAwareCluster
                    String cluster = registryURL.getParameter(CLUSTER_KEY, ZoneAwareCluster.NAME);
                    // The invoker wrap sequence would be: ZoneAwareClusterInvoker(StaticDirectory) -> FailoverClusterInvoker(RegistryDirectory, routing happens here) -> Invoker
                    invoker = Cluster.getCluster(cluster, false).join(new StaticDirectory(registryURL, invokers));
                } else { // not a registry url, must be direct invoke.
                    String cluster = CollectionUtils.isNotEmpty(invokers)
                            ?
                            (invokers.get(0).getUrl() != null ? invokers.get(0).getUrl().getParameter(CLUSTER_KEY, ZoneAwareCluster.NAME) :
                                    Cluster.DEFAULT)
                            : Cluster.DEFAULT;
                    // 直接进行合并
                    invoker = Cluster.getCluster(cluster).join(new StaticDirectory(invokers));
                }
            }
        }

        if (logger.isInfoEnabled()) {
            logger.info("Refer dubbo service " + interfaceClass.getName() + " from url " + invoker.getUrl());
        }

        URL consumerURL = new URL(CONSUMER_PROTOCOL, map.remove(REGISTER_IP_KEY), 0, map.get(INTERFACE_KEY), map);
        // 把消费者配置加入到元数据中心中
        MetadataUtils.publishServiceDefinition(consumerURL);

        // create service proxy
        // 创建服务代理
        return (T) PROXY_FACTORY.getProxy(invoker, ProtocolUtils.isGeneric(generic));
    }

    private void checkInvokerAvailable() throws IllegalStateException {
        if (shouldCheck() && !invoker.isAvailable()) {
            invoker.destroy();
            throw new IllegalStateException("Failed to check the status of the service "
                    + interfaceName
                    + ". No provider available for the service "
                    + (group == null ? "" : group + "/")
                    + interfaceName +
                    (version == null ? "" : ":" + version)
                    + " from the url "
                    + invoker.getUrl()
                    + " to the consumer "
                    + NetUtils.getLocalHost() + " use dubbo version " + Version.getVersion());
        }
    }

    /**
     * This method should be called right after the creation of this class's instance, before any property in other config modules is used.
     * Check each config modules are created properly and override their properties if necessary.
     */
    public void checkAndUpdateSubConfigs() {
        if (StringUtils.isEmpty(interfaceName)) {
            throw new IllegalStateException("<dubbo:reference interface=\"\" /> interface not allow null!");
        }
        completeCompoundConfigs(consumer);
        // get consumer's global configuration
        checkDefault();

        // init some null configuration.
        List<ConfigInitializer> configInitializers = ExtensionLoader.getExtensionLoader(ConfigInitializer.class)
                .getActivateExtension(URL.valueOf("configInitializer://"), (String[]) null);
        configInitializers.forEach(e -> e.initReferConfig(this));

        this.refresh();
        if (getGeneric() == null && getConsumer() != null) {
            setGeneric(getConsumer().getGeneric());
        }
        if (ProtocolUtils.isGeneric(generic)) {
            interfaceClass = GenericService.class;
        } else {
            try {
                interfaceClass = Class.forName(interfaceName, true, Thread.currentThread()
                        .getContextClassLoader());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            checkInterfaceAndMethods(interfaceClass, getMethods());
        }

        initServiceMetadata(consumer);
        serviceMetadata.setServiceType(getActualInterface());
        // TODO, uncomment this line once service key is unified
        serviceMetadata.setServiceKey(URL.buildKey(interfaceName, group, version));

        ServiceRepository repository = ApplicationModel.getServiceRepository();
        ServiceDescriptor serviceDescriptor = repository.registerService(interfaceClass);
        repository.registerConsumer(
                serviceMetadata.getServiceKey(),
                serviceDescriptor,
                this,
                null,
                serviceMetadata);

        resolveFile();
        ConfigValidationUtils.validateReferenceConfig(this);
        postProcessConfig();
    }


    /**
     * Figure out should refer the service in the same JVM from configurations. The default behavior is true
     * 1. if injvm is specified, then use it
     * 2. then if a url is specified, then assume it's a remote call
     * 3. otherwise, check scope parameter
     * 4. if scope is not specified but the target service is provided in the same JVM, then prefer to make the local
     * call, which is the default behavior
     */
    protected boolean shouldJvmRefer(Map<String, String> map) {
        URL tmpUrl = new URL("temp", "localhost", 0, map);
        boolean isJvmRefer;
        if (isInjvm() == null) {
            // if a url is specified, don't do local reference
            if (url != null && url.length() > 0) {
                isJvmRefer = false;
            } else {
                // by default, reference local service if there is
                isJvmRefer = InjvmProtocol.getInjvmProtocol().isInjvmRefer(tmpUrl);
            }
        } else {
            isJvmRefer = isInjvm();
        }
        return isJvmRefer;
    }

    /**
     * Dispatch an {@link Event event}
     *
     * @param event an {@link Event event}
     * @since 2.7.5
     */
    protected void dispatch(Event event) {
        EventDispatcher.getDefaultExtension().dispatch(event);
    }

    public DubboBootstrap getBootstrap() {
        return bootstrap;
    }

    public void setBootstrap(DubboBootstrap bootstrap) {
        this.bootstrap = bootstrap;
    }

    private void postProcessConfig() {
        List<ConfigPostProcessor> configPostProcessors = ExtensionLoader.getExtensionLoader(ConfigPostProcessor.class)
                .getActivateExtension(URL.valueOf("configPostProcessor://"), (String[]) null);
        configPostProcessors.forEach(component -> component.postProcessReferConfig(this));
    }

    // just for test
    Invoker<?> getInvoker() {
        return invoker;
    }
}
