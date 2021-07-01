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
import org.apache.dubbo.common.URLBuilder;
import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.bytecode.Wrapper;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ClassUtils;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.NamedThreadFactory;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.common.utils.UrlUtils;
import org.apache.dubbo.config.annotation.Service;
import org.apache.dubbo.config.bootstrap.DubboBootstrap;
import org.apache.dubbo.config.event.ServiceConfigExportedEvent;
import org.apache.dubbo.config.event.ServiceConfigUnexportedEvent;
import org.apache.dubbo.config.invoker.DelegateProviderMetaDataInvoker;
import org.apache.dubbo.config.support.Parameter;
import org.apache.dubbo.config.utils.ConfigValidationUtils;
import org.apache.dubbo.event.Event;
import org.apache.dubbo.event.EventDispatcher;
import org.apache.dubbo.metadata.ServiceNameMapping;
import org.apache.dubbo.registry.client.metadata.MetadataUtils;
import org.apache.dubbo.rpc.Exporter;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.ProxyFactory;
import org.apache.dubbo.rpc.cluster.ConfiguratorFactory;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.model.ServiceDescriptor;
import org.apache.dubbo.rpc.model.ServiceRepository;
import org.apache.dubbo.rpc.service.GenericService;
import org.apache.dubbo.rpc.support.ProtocolUtils;

import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.apache.dubbo.common.constants.CommonConstants.ANYHOST_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.ANY_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.DUBBO;
import static org.apache.dubbo.common.constants.CommonConstants.DUBBO_IP_TO_BIND;
import static org.apache.dubbo.common.constants.CommonConstants.LOCALHOST_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.MAPPING_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.METADATA_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.METHODS_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.MONITOR_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PROVIDER_SIDE;
import static org.apache.dubbo.common.constants.CommonConstants.REGISTER_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.REMOTE_METADATA_STORAGE_TYPE;
import static org.apache.dubbo.common.constants.CommonConstants.REVISION_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.SIDE_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.DYNAMIC_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.REGISTRY_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.REGISTRY_TYPE_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.SERVICE_REGISTRY_PROTOCOL;
import static org.apache.dubbo.common.constants.RegistryConstants.SERVICE_REGISTRY_TYPE;
import static org.apache.dubbo.common.utils.NetUtils.getAvailablePort;
import static org.apache.dubbo.common.utils.NetUtils.getLocalHost;
import static org.apache.dubbo.common.utils.NetUtils.isInvalidLocalHost;
import static org.apache.dubbo.common.utils.NetUtils.isInvalidPort;
import static org.apache.dubbo.config.Constants.DUBBO_IP_TO_REGISTRY;
import static org.apache.dubbo.config.Constants.DUBBO_PORT_TO_BIND;
import static org.apache.dubbo.config.Constants.DUBBO_PORT_TO_REGISTRY;
import static org.apache.dubbo.config.Constants.MULTICAST;
import static org.apache.dubbo.config.Constants.MULTIPLE;
import static org.apache.dubbo.config.Constants.SCOPE_NONE;
import static org.apache.dubbo.remoting.Constants.BIND_IP_KEY;
import static org.apache.dubbo.remoting.Constants.BIND_PORT_KEY;
import static org.apache.dubbo.rpc.Constants.GENERIC_KEY;
import static org.apache.dubbo.rpc.Constants.LOCAL_PROTOCOL;
import static org.apache.dubbo.rpc.Constants.PROXY_KEY;
import static org.apache.dubbo.rpc.Constants.SCOPE_KEY;
import static org.apache.dubbo.rpc.Constants.SCOPE_LOCAL;
import static org.apache.dubbo.rpc.Constants.SCOPE_REMOTE;
import static org.apache.dubbo.rpc.Constants.TOKEN_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.EXPORT_KEY;

public class ServiceConfig<T> extends ServiceConfigBase<T> {

    public static final Logger logger = LoggerFactory.getLogger(ServiceConfig.class);

    /**
     * A random port cache, the different protocols who has no port specified have different random port
     */
    private static final Map<String, Integer> RANDOM_PORT_MAP = new HashMap<String, Integer>();

    /**
     * A delayed exposure service timer
     */
    private static final ScheduledExecutorService DELAY_EXPORT_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("DubboServiceDelayExporter", true));

    private String serviceName;

    private static final Protocol PROTOCOL = ExtensionLoader.getExtensionLoader(Protocol.class).getAdaptiveExtension();

    /**
     * A {@link ProxyFactory} implementation that will generate a exported service proxy,the JavassistProxyFactory is its
     * default implementation
     */
    private static final ProxyFactory PROXY_FACTORY = ExtensionLoader.getExtensionLoader(ProxyFactory.class).getAdaptiveExtension();

    /**
     * Whether the provider has been exported
     */
    private transient volatile boolean exported;

    /**
     * The flag whether a service has unexported ,if the method unexported is invoked, the value is true
     */
    private transient volatile boolean unexported;

    private DubboBootstrap bootstrap;

    /**
     * The exported services
     */
    private final List<Exporter<?>> exporters = new ArrayList<Exporter<?>>();

    private static final String CONFIG_INITIALIZER_PROTOCOL = "configInitializer://";

    private static final String TRUE_VALUE = "true";

    private static final String FALSE_VALUE = "false";

    private static final String STUB_SUFFIX = "Stub";

    private static final String LOCAL_SUFFIX = "Local";

    private static final String CONFIG_POST_PROCESSOR_PROTOCOL = "configPostProcessor://";

    private static final String RETRY_SUFFIX = ".retry";

    private static final String RETRIES_SUFFIX = ".retries";

    private static final String ZERO_VALUE = "0";

    public ServiceConfig() {
    }

    public ServiceConfig(Service service) {
        super(service);
    }

    @Parameter(excluded = true)
    @Override
    public boolean isExported() {
        return exported;
    }

    @Parameter(excluded = true)
    @Override
    public boolean isUnexported() {
        return unexported;
    }

    @Override
    public void unexport() {
        if (!exported) {
            return;
        }
        if (unexported) {
            return;
        }
        if (!exporters.isEmpty()) {
            for (Exporter<?> exporter : exporters) {
                try {
                    exporter.unexport();
                } catch (Throwable t) {
                    logger.warn("Unexpected error occurred when unexport " + exporter, t);
                }
            }
            exporters.clear();
        }
        unexported = true;

        // dispatch a ServiceConfigUnExportedEvent since 2.7.4
        dispatch(new ServiceConfigUnexportedEvent(this));
    }

    @Override
    public synchronized void export() {
        if (bootstrap == null) {
            bootstrap = DubboBootstrap.getInstance();
            // compatible with api call.
            if (null != this.getRegistry()) {
                bootstrap.registries(this.getRegistries());
            }
            bootstrap.initialize();
        }
        //检查并且更新配置
        checkAndUpdateSubConfigs();

        initServiceMetadata(provider);
        serviceMetadata.setServiceType(getInterfaceClass());
        serviceMetadata.setTarget(getRef());
        serviceMetadata.generateServiceKey();
        // 如果不应该暴露，则直接结束
        if (!shouldExport()) {
            return;
        }
        // 如果使用延迟加载，则延迟delay时间后暴露服务
        if (shouldDelay()) {
            DELAY_EXPORT_EXECUTOR.schedule(this::doExport, getDelay(), TimeUnit.MILLISECONDS);
        } else {
            // 暴露服务
            doExport();
        }

        exported();
    }

    public void exported() {
        List<URL> exportedURLs = this.getExportedUrls();
        exportedURLs.forEach(url -> {
            // dubbo2.7.x does not register serviceNameMapping information with the registry by default.
            // Only when the user manually turns on the service introspection, can he register with the registration center.
            boolean isServiceDiscovery = UrlUtils.isServiceDiscoveryRegistryType(url);
            if (isServiceDiscovery) {
                Map<String, String> parameters = getApplication().getParameters();
                ServiceNameMapping.getExtension(parameters != null ? parameters.get(MAPPING_KEY) : null).map(url);
            }
        });
        // dispatch a ServiceConfigExportedEvent since 2.7.4
        dispatch(new ServiceConfigExportedEvent(this));
    }

    /**
     * 检查并且更新配置
     * 可以看到，该方法中是对各类配置的校验，并且更新部分配置。其中检查的细节我就不展开，因为服务暴露整个过程才是本文重点。
     */
    private void checkAndUpdateSubConfigs() {
        // Use default configs defined explicitly with global scope
        // 用于检测 provider、application 等核心配置类对象是否为空，
        // 若为空，则尝试从其他配置类对象中获取相应的实例。
        completeCompoundConfigs();
        // 检测 provider 是否为空，为空则新建一个，并通过系统变量为其初始化
        checkDefault();
        checkProtocol();
        // init some null configuration.
        List<ConfigInitializer> configInitializers = ExtensionLoader.getExtensionLoader(ConfigInitializer.class)
                .getActivateExtension(URL.valueOf(CONFIG_INITIALIZER_PROTOCOL), (String[]) null);
        configInitializers.forEach(e -> e.initServiceConfig(this));

        // if protocol is not injvm checkRegistry
        if (!isOnlyInJvm()) {
            // 检查注册中心是否为空
            checkRegistry();
        }
        this.refresh();

        // 服务接口名不能为空，否则抛出异常
        if (StringUtils.isEmpty(interfaceName)) {
            throw new IllegalStateException("<dubbo:service interface=\"\" /> interface not allow null!");
        }
        // 检测 ref 是否为泛化服务类型
        if (ref instanceof GenericService) {
            interfaceClass = GenericService.class;
            if (StringUtils.isEmpty(generic)) {
                // 设置generic = true
                generic = TRUE_VALUE;
            }
        } else {
            try {
                // 获得接口类型
                interfaceClass = Class.forName(interfaceName, true, Thread.currentThread()
                        .getContextClassLoader());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            // 对 interfaceClass，以及 <dubbo:method> 标签中的必要字段进行检查
            checkInterfaceAndMethods(interfaceClass, getMethods());
            // 对 ref 合法性进行检测
            checkRef();
            generic = FALSE_VALUE;
        }
        // stub local一样都是配置本地存根
        if (local != null) {
            if (TRUE_VALUE.equals(local)) {
                local = interfaceName + LOCAL_SUFFIX;
            }
            Class<?> localClass;
            try {
                localClass = ClassUtils.forNameWithThreadContextClassLoader(local);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            if (!interfaceClass.isAssignableFrom(localClass)) {
                throw new IllegalStateException(
                        "The local implementation class " + localClass.getName() + " not implement interface " + interfaceName);
            }
        }
        if (stub != null) {
            if (TRUE_VALUE.equals(stub)) {
                stub = interfaceName + STUB_SUFFIX;
            }
            Class<?> stubClass;
            try {
                stubClass = ClassUtils.forNameWithThreadContextClassLoader(stub);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            if (!interfaceClass.isAssignableFrom(stubClass)) {
                throw new IllegalStateException(
                        "The stub implementation class " + stubClass.getName() + " not implement interface " + interfaceName);
            }
        }
        // 本地存根合法性校验
        checkStubAndLocal(interfaceClass);
        // mock合法性校验
        ConfigValidationUtils.checkMock(interfaceClass, this);
        ConfigValidationUtils.validateServiceConfig(this);
        postProcessConfig();
    }


    /**
     * 该方法就是对于服务是否暴露在一次校验，然后会执行ServiceConfig的doExportUrls()方法，对于多协议多注册中心暴露服务进行支持。
     */
    protected synchronized void doExport() {
        // 如果调用不暴露的方法，则unexported值为true
        if (unexported) {
            throw new IllegalStateException("The service " + interfaceClass.getName() + " has already unexported!");
        }
        // 如果服务已经暴露了，则直接结束
        if (exported) {
            return;
        }
        // 设置已经暴露
        exported = true;

        // 如果path为空，则赋值接口名称
        if (StringUtils.isEmpty(path)) {
            path = interfaceName;
        }
        // 多协议多注册中心暴露服务
        doExportUrls();
        bootstrap.setReady(true);
    }

    /**
     * loadRegistries()方法是加载注册中心链接。
     * 服务的唯一性是通过path、group、version一起确定的。
     * doExportUrlsFor1Protocol()方法开始组装URL。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void doExportUrls() {
        ServiceRepository repository = ApplicationModel.getServiceRepository();
        ServiceDescriptor serviceDescriptor = repository.registerService(getInterfaceClass());
        repository.registerProvider(
                getUniqueServiceName(),
                ref,
                serviceDescriptor,
                this,
                serviceMetadata
        );

        // 加载注册中心链接
        List<URL> registryURLs = ConfigValidationUtils.loadRegistries(this, true);

        int protocolConfigNum = protocols.size();
        // 遍历 protocols，并在每个协议下暴露服务
        for (ProtocolConfig protocolConfig : protocols) {
            // 以path、group、version来作为服务唯一性确定的key
            String pathKey = URL.buildKey(getContextPath(protocolConfig)
                    .map(p -> p + "/" + path)
                    .orElse(path), group, version);
            // In case user specified path, register service one more time to map it to path.
            repository.registerService(pathKey, interfaceClass);
            // 组装 URL
            doExportUrlsFor1Protocol(protocolConfig, registryURLs, protocolConfigNum);
        }
    }

    /**
     * 先看分割线上面部分，就是组装URL的全过程，我觉得大致可以分为一下步骤：
     *
     * 它把metrics、application、module、provider、protocol等所有配置都放入map中，
     * 针对method都配置，先做签名校验，先找到该服务是否有配置的方法存在，然后该方法签名是否有这个参数存在，都核对成功才将method的配置加入map。
     * 将泛化调用、版本号、method或者methods、token等信息加入map
     * 获得服务暴露地址和端口号，利用map内数据组装成URL。
     * @param protocolConfig
     * @param registryURLs
     * @param protocolConfigNum
     */
    private void doExportUrlsFor1Protocol(ProtocolConfig protocolConfig, List<URL> registryURLs, int protocolConfigNum) {
        // 获取协议名
        String name = protocolConfig.getName();
        // 如果为空，则是默认的dubbo
        if (StringUtils.isEmpty(name)) {
            name = DUBBO;
        }

        Map<String, String> map = new HashMap<String, String>();
        // 设置服务提供者册
        map.put(SIDE_KEY, PROVIDER_SIDE);

        // 添加 协议版本、发布版本，时间戳 等信息到 map 中
        ServiceConfig.appendRuntimeParameters(map);
        // 添加metrics、application、module、provider、protocol的所有信息到map
        AbstractConfig.appendParameters(map, getMetrics());
        AbstractConfig.appendParameters(map, getApplication());
        AbstractConfig.appendParameters(map, getModule());
        // remove 'default.' prefix for configs from ProviderConfig
        // appendParameters(map, provider, Constants.DEFAULT_KEY);
        AbstractConfig.appendParameters(map, provider);
        AbstractConfig.appendParameters(map, protocolConfig);
        AbstractConfig.appendParameters(map, this);
        MetadataReportConfig metadataReportConfig = getMetadataReportConfig();
        if (metadataReportConfig != null && metadataReportConfig.isValid()) {
            map.putIfAbsent(METADATA_KEY, REMOTE_METADATA_STORAGE_TYPE);
        }
        // 如果method的配置列表不为空
        if (CollectionUtils.isNotEmpty(getMethods())) {
            // 遍历method配置列表
            for (MethodConfig method : getMethods()) {
                // 把方法名加入map
                AbstractConfig.appendParameters(map, method, method.getName());
                // 添加 MethodConfig 对象的字段信息到 map 中，键 = 方法名.属性名。
                // 比如存储 <dubbo:method name="sayHello" retries="2"> 对应的 MethodConfig，
                // 键 = sayHello.retries，map = {"sayHello.retries": 2, "xxx": "yyy"}
                String retryKey = method.getName() + RETRY_SUFFIX;
                if (map.containsKey(retryKey)) {
                    String retryValue = map.remove(retryKey);
                    // 如果retryValue为false，则不重试，设置值为0
                    if (FALSE_VALUE.equals(retryValue)) {
                        map.put(method.getName() + RETRIES_SUFFIX, ZERO_VALUE);
                    }
                }
                // 获得ArgumentConfig列表
                List<ArgumentConfig> arguments = method.getArguments();
                if (CollectionUtils.isNotEmpty(arguments)) {
                    // 遍历ArgumentConfig列表
                    for (ArgumentConfig argument : arguments) {
                        // convert argument type
                        // // 检测 type 属性是否为空，或者空串
                        if (argument.getType() != null && argument.getType().length() > 0) {
                            // 利用反射获取该服务的所有方法集合
                            Method[] methods = interfaceClass.getMethods();
                            // visit all methods
                            if (methods.length > 0) {
                                // 遍历所有方法
                                for (int i = 0; i < methods.length; i++) {
                                    // 获得方法名
                                    String methodName = methods[i].getName();
                                    // target the method, and get its signature
                                    // 找到目标方法
                                    if (methodName.equals(method.getName())) {
                                        // 通过反射获取目标方法的参数类型数组 argtypes
                                        Class<?>[] argtypes = methods[i].getParameterTypes();
                                        // one callback in the method
                                        // 如果下标为-1
                                        if (argument.getIndex() != -1) {
                                            // 检测 argType 的名称与 ArgumentConfig 中的 type 属性是否一致
                                            if (argtypes[argument.getIndex()].getName().equals(argument.getType())) {
                                                //  添加 ArgumentConfig 字段信息到 map 中
                                                AbstractConfig
                                                        .appendParameters(map, argument, method.getName() + "." + argument.getIndex());
                                            } else {
                                                // 不一致，则抛出异常
                                                throw new IllegalArgumentException(
                                                        "Argument config error : the index attribute and type attribute not match :index :" +
                                                                argument.getIndex() + ", type:" + argument.getType());
                                            }
                                        } else {
                                            // multiple callbacks in the method
                                            // 遍历参数类型数组 argtypes，查找 argument.type 类型的参数
                                            for (int j = 0; j < argtypes.length; j++) {
                                                Class<?> argclazz = argtypes[j];
                                                if (argclazz.getName().equals(argument.getType())) {
                                                    // 如果找到，则添加 ArgumentConfig 字段信息到 map 中
                                                    AbstractConfig.appendParameters(map, argument, method.getName() + "." + j);
                                                    if (argument.getIndex() != -1 && argument.getIndex() != j) {
                                                        throw new IllegalArgumentException(
                                                                "Argument config error : the index attribute and type attribute not match :index :" +
                                                                        argument.getIndex() + ", type:" + argument.getType());
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } else if (argument.getIndex() != -1) {
                            // 用户未配置 type 属性，但配置了 index 属性，且 index != -1，则直接添加到map
                            AbstractConfig.appendParameters(map, argument, method.getName() + "." + argument.getIndex());
                        } else {
                            // 抛出异常
                            throw new IllegalArgumentException(
                                    "Argument config must set index or type attribute.eg: <dubbo:argument index='0' .../> or <dubbo:argument type=xxx .../>");
                        }

                    }
                }
            } // end of methods for
        }
        // 如果是泛化调用，则在map中设置generic和methods
        if (ProtocolUtils.isGeneric(generic)) {
            map.put(GENERIC_KEY, generic);
            map.put(METHODS_KEY, ANY_VALUE);
        } else {
            // 获得版本号
            String revision = Version.getVersion(interfaceClass, version);
            // 放入map
            if (revision != null && revision.length() > 0) {
                map.put(REVISION_KEY, revision);
            }
            // 获得方法集合
            String[] methods = Wrapper.getWrapper(interfaceClass).getMethodNames();
            // 如果为空，则告警
            if (methods.length == 0) {
                logger.warn("No method found in service interface " + interfaceClass.getName());
                // 设置method为*
                map.put(METHODS_KEY, ANY_VALUE);
            } else {
                // 否则加入方法集合
                map.put(METHODS_KEY, StringUtils.join(new HashSet<String>(Arrays.asList(methods)), ","));
            }
        }

        /**
         * Here the token value configured by the provider is used to assign the value to ServiceConfig#token
         */
        // 把token 的值加入到map中
        if (ConfigUtils.isEmpty(token) && provider != null) {
            token = provider.getToken();
        }

        if (!ConfigUtils.isEmpty(token)) {
            if (ConfigUtils.isDefault(token)) {
                map.put(TOKEN_KEY, UUID.randomUUID().toString());
            } else {
                map.put(TOKEN_KEY, token);
            }
        }
        //init serviceMetadata attachments
        serviceMetadata.getAttachments().putAll(map);

        // export service
        // 获得地址
        String host = findConfigedHosts(protocolConfig, registryURLs, map);
        // 获得端口号
        Integer port = findConfigedPorts(protocolConfig, name, map, protocolConfigNum);
        // 生成　URL
        URL url = new URL(name, host, port, getContextPath(protocolConfig).map(p -> p + "/" + path).orElse(path), map);
        // —————————————————————————————————————分割线———————————————————————————————————————

        //暴露到远程的源码直接看doExportUrlsFor1Protocol()方法分割线下半部分。当生成暴露者的时候，服务已经暴露，接下来会细致的分析这暴露内部的过程。
        // 可以发现无论暴露到本地还是远程，都会通过代理工厂创建invoker。这个时候就走到了上述时序图的ProxyFactory
        // You can customize Configurator to append extra parameters
        // 加载 ConfiguratorFactory，并生成 Configurator 实例，判断是否有该协议的实现存在
        if (ExtensionLoader.getExtensionLoader(ConfiguratorFactory.class)
                .hasExtension(url.getProtocol())) {
            // 通过实例配置 url
            url = ExtensionLoader.getExtensionLoader(ConfiguratorFactory.class)
                    .getExtension(url.getProtocol()).getConfigurator(url).configure(url);
        }

        String scope = url.getParameter(SCOPE_KEY);
        //scope = none，不暴露服务
        //scope != remote，暴露到本地
        //scope != local，暴露到远程
        // don't export when none is configured
        // // 如果 scope = none，则什么都不做
        if (!SCOPE_NONE.equalsIgnoreCase(scope)) {

            // export to local if the config is not remote (export to remote only when config is remote)
            // // scope != remote，暴露到本地
            if (!SCOPE_REMOTE.equalsIgnoreCase(scope)) {
                // 暴露到本地
                exportLocal(url);
            }
            // export to remote if the config is not local (export to local only when config is local)
            //  scope != local，导出到远程
            if (!SCOPE_LOCAL.equalsIgnoreCase(scope)) {
                // 如果注册中心链接集合不为空
                if (CollectionUtils.isNotEmpty(registryURLs)) {
                    for (URL registryURL : registryURLs) {
                        if (SERVICE_REGISTRY_PROTOCOL.equals(registryURL.getProtocol())) {
                            url = url.addParameterIfAbsent(REGISTRY_TYPE_KEY, SERVICE_REGISTRY_TYPE);
                        }

                        //if protocol is only injvm ,not register
                        if (LOCAL_PROTOCOL.equalsIgnoreCase(url.getProtocol())) {
                            continue;
                        }
                        // 添加dynamic配置
                        url = url.addParameterIfAbsent(DYNAMIC_KEY, registryURL.getParameter(DYNAMIC_KEY));
                        URL monitorUrl = ConfigValidationUtils.loadMonitor(this, registryURL);
                        if (monitorUrl != null) {
                            // 加载监视器链接
                            url = url.addParameterAndEncoded(MONITOR_KEY, monitorUrl.toFullString());
                        }
                        if (logger.isInfoEnabled()) {
                            if (url.getParameter(REGISTER_KEY, true)) {
                                logger.info("Register dubbo service " + interfaceClass.getName() + " url " + url + " to registry " +
                                        registryURL);
                            } else {
                                logger.info("Export dubbo service " + interfaceClass.getName() + " to url " + url);
                            }
                        }

                        // For providers, this is used to enable custom proxy to generate invoker
                        // 获得代理方式
                        String proxy = url.getParameter(PROXY_KEY);
                        if (StringUtils.isNotEmpty(proxy)) {
                            // 添加代理方式到注册中心到url
                            registryURL = registryURL.addParameter(PROXY_KEY, proxy);
                        }
                        // 为服务提供类(ref)生成 Invoker
                        Invoker<?> invoker = PROXY_FACTORY.getInvoker(ref, (Class) interfaceClass,
                                registryURL.addParameterAndEncoded(EXPORT_KEY, url.toFullString()));
                        DelegateProviderMetaDataInvoker wrapperInvoker = new DelegateProviderMetaDataInvoker(invoker, this);

                        // 暴露服务，并且生成Exporter
                        //暴露到远程的逻辑要比本地复杂的多，它大致可以分为服务暴露和服务注册两个过程。
                        // 先来看看服务暴露。我们知道dubbo有很多协议实现，在doExportUrlsFor1Protocol()方法分割线下半部分中，生成了Invoker后，
                        // 就需要调用protocol 的 export()方法，很多人会认为这里的export()就是配置中指定的协议实现中的方法，但这里是不对的。
                        // 因为暴露到远程后需要进行服务注册，而RegistryProtocol的 export()方法就是实现了服务暴露和服务注册两个过程。
                        // 所以这里的export()调用的是RegistryProtocol的 export()。
                        Exporter<?> exporter = PROTOCOL.export(wrapperInvoker);
                        // 加入到暴露者集合中
                        exporters.add(exporter);
                    }
                } else {
                    if (logger.isInfoEnabled()) {
                        logger.info("Export dubbo service " + interfaceClass.getName() + " to url " + url);
                    }
                    // 不存在注册中心，则仅仅暴露服务，不会记录暴露到地址
                    Invoker<?> invoker = PROXY_FACTORY.getInvoker(ref, (Class) interfaceClass, url);
                    DelegateProviderMetaDataInvoker wrapperInvoker = new DelegateProviderMetaDataInvoker(invoker, this);

                    Exporter<?> exporter = PROTOCOL.export(wrapperInvoker);
                    exporters.add(exporter);
                }

                MetadataUtils.publishServiceDefinition(url);
            }
        }
        this.urls.add(url);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    /**
     * always export injvm
     */
    private void exportLocal(URL url) {
        // 生成本地的url,分别把协议改为injvm，设置host和port
        URL local = URLBuilder.from(url)
                .setProtocol(LOCAL_PROTOCOL)
                .setHost(LOCALHOST_VALUE)
                .setPort(0)
                .build();
        // 通过代理工程创建invoker
        // 再调用export方法进行暴露服务，生成Exporter
        Exporter<?> exporter = PROTOCOL.export(
                PROXY_FACTORY.getInvoker(ref, (Class) interfaceClass, local));
        // 把生成的暴露者加入集合
        exporters.add(exporter);
        logger.info("Export dubbo service " + interfaceClass.getName() + " to local registry url : " + local);
    }

    /**
     * Determine if it is injvm
     *
     * @return
     */
    private boolean isOnlyInJvm() {
        return getProtocols().size() == 1
                && LOCAL_PROTOCOL.equalsIgnoreCase(getProtocols().get(0).getName());
    }


    /**
     * Register & bind IP address for service provider, can be configured separately.
     * Configuration priority: environment variables -> java system properties -> host property in config file ->
     * /etc/hosts -> default network address -> first available network address
     *
     * @param protocolConfig
     * @param registryURLs
     * @param map
     * @return
     */
    private String findConfigedHosts(ProtocolConfig protocolConfig,
                                     List<URL> registryURLs,
                                     Map<String, String> map) {
        boolean anyhost = false;

        String hostToBind = getValueFromConfig(protocolConfig, DUBBO_IP_TO_BIND);
        if (hostToBind != null && hostToBind.length() > 0 && isInvalidLocalHost(hostToBind)) {
            throw new IllegalArgumentException("Specified invalid bind ip from property:" + DUBBO_IP_TO_BIND + ", value:" + hostToBind);
        }

        // if bind ip is not found in environment, keep looking up
        if (StringUtils.isEmpty(hostToBind)) {
            hostToBind = protocolConfig.getHost();
            if (provider != null && StringUtils.isEmpty(hostToBind)) {
                hostToBind = provider.getHost();
            }
            if (isInvalidLocalHost(hostToBind)) {
                anyhost = true;
                try {
                    logger.info("No valid ip found from environment, try to find valid host from DNS.");
                    hostToBind = InetAddress.getLocalHost().getHostAddress();
                } catch (UnknownHostException e) {
                    logger.warn(e.getMessage(), e);
                }
                if (isInvalidLocalHost(hostToBind)) {
                    if (CollectionUtils.isNotEmpty(registryURLs)) {
                        for (URL registryURL : registryURLs) {
                            if (MULTICAST.equalsIgnoreCase(registryURL.getParameter(REGISTRY_KEY))) {
                                // skip multicast registry since we cannot connect to it via Socket
                                continue;
                            }
                            if (MULTIPLE.equalsIgnoreCase(registryURL.getParameter("registry"))) {
                                // skip, multiple-registry address is a fake ip
                                continue;
                            }
                            try (Socket socket = new Socket()) {
                                SocketAddress addr = new InetSocketAddress(registryURL.getHost(), registryURL.getPort());
                                socket.connect(addr, 1000);
                                hostToBind = socket.getLocalAddress().getHostAddress();
                                break;
                            } catch (Exception e) {
                                logger.warn(e.getMessage(), e);
                            }
                        }
                    }
                    if (isInvalidLocalHost(hostToBind)) {
                        hostToBind = getLocalHost();
                    }
                }
            }
        }

        map.put(BIND_IP_KEY, hostToBind);

        // registry ip is not used for bind ip by default
        String hostToRegistry = getValueFromConfig(protocolConfig, DUBBO_IP_TO_REGISTRY);
        if (hostToRegistry != null && hostToRegistry.length() > 0 && isInvalidLocalHost(hostToRegistry)) {
            throw new IllegalArgumentException(
                    "Specified invalid registry ip from property:" + DUBBO_IP_TO_REGISTRY + ", value:" + hostToRegistry);
        } else if (StringUtils.isEmpty(hostToRegistry)) {
            // bind ip is used as registry ip by default
            hostToRegistry = hostToBind;
        }

        map.put(ANYHOST_KEY, String.valueOf(anyhost));

        return hostToRegistry;
    }


    /**
     * Register port and bind port for the provider, can be configured separately
     * Configuration priority: environment variable -> java system properties -> port property in protocol config file
     * -> protocol default port
     *
     * @param protocolConfig
     * @param name
     * @param protocolConfigNum
     * @return
     */
    private Integer findConfigedPorts(ProtocolConfig protocolConfig,
                                      String name,
                                      Map<String, String> map, int protocolConfigNum) {
        Integer portToBind = null;

        // parse bind port from environment
        String port = getValueFromConfig(protocolConfig, DUBBO_PORT_TO_BIND);
        portToBind = parsePort(port);

        // if there's no bind port found from environment, keep looking up.
        if (portToBind == null) {
            portToBind = protocolConfig.getPort();
            if (provider != null && (portToBind == null || portToBind == 0)) {
                portToBind = provider.getPort();
            }
            final int defaultPort = ExtensionLoader.getExtensionLoader(Protocol.class).getExtension(name).getDefaultPort();
            if (portToBind == null || portToBind == 0) {
                portToBind = defaultPort;
            }
            if (portToBind <= 0) {
                portToBind = getRandomPort(name);
                if (portToBind == null || portToBind < 0) {
                    portToBind = getAvailablePort(defaultPort);
                    putRandomPort(name, portToBind);
                }
            }
        }

        // registry port, not used as bind port by default
        String key = DUBBO_PORT_TO_REGISTRY;
        if (protocolConfigNum > 1) {
            key = getProtocolConfigId(protocolConfig).toUpperCase() + "_" + key;
        }
        String portToRegistryStr = getValueFromConfig(protocolConfig, key);
        Integer portToRegistry = parsePort(portToRegistryStr);
        if (portToRegistry == null) {
            portToRegistry = portToBind;
        }

        // save bind port, used as url's key later
        map.put(BIND_PORT_KEY, String.valueOf(portToRegistry));

        return portToRegistry;
    }


    private String getProtocolConfigId(ProtocolConfig config) {
        return Optional.ofNullable(config.getId()).orElse(DUBBO);
    }

    private Integer parsePort(String configPort) {
        Integer port = null;
        if (configPort != null && configPort.length() > 0) {
            try {
                Integer intPort = Integer.parseInt(configPort);
                if (isInvalidPort(intPort)) {
                    throw new IllegalArgumentException("Specified invalid port from env value:" + configPort);
                }
                port = intPort;
            } catch (Exception e) {
                throw new IllegalArgumentException("Specified invalid port from env value:" + configPort);
            }
        }
        return port;
    }

    private String getValueFromConfig(ProtocolConfig protocolConfig, String key) {
        String protocolPrefix = protocolConfig.getName().toUpperCase() + "_";
        String value = ConfigUtils.getSystemProperty(protocolPrefix + key);
        if (StringUtils.isEmpty(value)) {
            value = ConfigUtils.getSystemProperty(key);
        }
        return value;
    }

    private Integer getRandomPort(String protocol) {
        protocol = protocol.toLowerCase();
        return RANDOM_PORT_MAP.getOrDefault(protocol, Integer.MIN_VALUE);
    }

    private void putRandomPort(String protocol, Integer port) {
        protocol = protocol.toLowerCase();
        if (!RANDOM_PORT_MAP.containsKey(protocol)) {
            RANDOM_PORT_MAP.put(protocol, port);
            logger.warn("Use random available port(" + port + ") for protocol " + protocol);
        }
    }

    private void postProcessConfig() {
        List<ConfigPostProcessor> configPostProcessors = ExtensionLoader.getExtensionLoader(ConfigPostProcessor.class)
                .getActivateExtension(URL.valueOf(CONFIG_POST_PROCESSOR_PROTOCOL), (String[]) null);
        configPostProcessors.forEach(component -> component.postProcessServiceConfig(this));
    }

    /**
     * Dispatch an {@link Event event}
     *
     * @param event an {@link Event event}
     * @since 2.7.5
     */
    private void dispatch(Event event) {
        EventDispatcher.getDefaultExtension().dispatch(event);
    }

    public DubboBootstrap getBootstrap() {
        return bootstrap;
    }

    public void setBootstrap(DubboBootstrap bootstrap) {
        this.bootstrap = bootstrap;
    }

    public String getServiceName() {

        if (!StringUtils.isBlank(serviceName)) {
            return serviceName;
        }
        String generateVersion = version;
        String generateGroup = group;

        if (StringUtils.isBlank(version) && provider != null) {
            generateVersion = provider.getVersion();
        }

        if (StringUtils.isBlank(group) && provider != null) {
            generateGroup = provider.getGroup();
        }
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("ServiceBean:");

        if (!StringUtils.isBlank(generateGroup)) {
            stringBuilder.append(generateGroup);
        }

        stringBuilder.append("/").append(interfaceName);

        if (!StringUtils.isBlank(generateVersion)) {
            stringBuilder.append(":").append(generateVersion);
        }

        serviceName = stringBuilder.toString();
        return serviceName;
    }
}
