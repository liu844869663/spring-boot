/*
 * Copyright 2012-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot;

import java.lang.reflect.Constructor;
import java.security.AccessControlException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.CachedIntrospectionResults;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.groovy.GroovyBeanDefinitionReader;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanNameGenerator;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.boot.Banner.Mode;
import org.springframework.boot.context.config.ConfigFileApplicationListener;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.web.reactive.context.StandardReactiveWebEnvironment;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.AnnotationConfigUtils;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.GenericTypeResolver;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.support.ConfigurableConversionService;
import org.springframework.core.env.CommandLinePropertySource;
import org.springframework.core.env.CompositePropertySource;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StopWatch;
import org.springframework.util.StringUtils;
import org.springframework.web.context.support.StandardServletEnvironment;

/**
 * Class that can be used to bootstrap and launch a Spring application from a Java main
 * method. By default class will perform the following steps to bootstrap your
 * application:
 *
 * <ul>
 * <li>Create an appropriate {@link ApplicationContext} instance (depending on your
 * classpath)</li>
 * <li>Register a {@link CommandLinePropertySource} to expose command line arguments as
 * Spring properties</li>
 * <li>Refresh the application context, loading all singleton beans</li>
 * <li>Trigger any {@link CommandLineRunner} beans</li>
 * </ul>
 *
 * In most circumstances the static {@link #run(Class, String[])} method can be called
 * directly from your {@literal main} method to bootstrap your application:
 *
 * <pre class="code">
 * &#064;Configuration
 * &#064;EnableAutoConfiguration
 * public class MyApplication  {
 *
 *   // ... Bean definitions
 *
 *   public static void main(String[] args) {
 *     SpringApplication.run(MyApplication.class, args);
 *   }
 * }
 * </pre>
 *
 * <p>
 * For more advanced configuration a {@link SpringApplication} instance can be created and
 * customized before being run:
 *
 * <pre class="code">
 * public static void main(String[] args) {
 *   SpringApplication application = new SpringApplication(MyApplication.class);
 *   // ... customize application settings here
 *   application.run(args)
 * }
 * </pre>
 *
 * {@link SpringApplication}s can read beans from a variety of different sources. It is
 * generally recommended that a single {@code @Configuration} class is used to bootstrap
 * your application, however, you may also set {@link #getSources() sources} from:
 * <ul>
 * <li>The fully qualified class name to be loaded by
 * {@link AnnotatedBeanDefinitionReader}</li>
 * <li>The location of an XML resource to be loaded by {@link XmlBeanDefinitionReader}, or
 * a groovy script to be loaded by {@link GroovyBeanDefinitionReader}</li>
 * <li>The name of a package to be scanned by {@link ClassPathBeanDefinitionScanner}</li>
 * </ul>
 *
 * Configuration properties are also bound to the {@link SpringApplication}. This makes it
 * possible to set {@link SpringApplication} properties dynamically, like additional
 * sources ("spring.main.sources" - a CSV list) the flag to indicate a web environment
 * ("spring.main.web-application-type=none") or the flag to switch off the banner
 * ("spring.main.banner-mode=off").
 *
 * @author Phillip Webb
 * @author Dave Syer
 * @author Andy Wilkinson
 * @author Christian Dupuis
 * @author Stephane Nicoll
 * @author Jeremy Rickard
 * @author Craig Burke
 * @author Michael Simons
 * @author Madhura Bhave
 * @author Brian Clozel
 * @author Ethan Rubinson
 * @since 1.0.0
 * @see #run(Class, String[])
 * @see #run(Class[], String[])
 * @see #SpringApplication(Class...)
 */
public class SpringApplication {

	/**
	 * Spring 应用上下文（非 Web 场景）
	 * The class name of application context that will be used by default for non-web environments.
	 */
	public static final String DEFAULT_CONTEXT_CLASS = "org.springframework.context."
			+ "annotation.AnnotationConfigApplicationContext";

	/**
	 * Spring 应用上下文（Web 场景）
	 * The class name of application context that will be used by default for web environments.
	 */
	public static final String DEFAULT_SERVLET_WEB_CONTEXT_CLASS = "org.springframework.boot."
			+ "web.servlet.context.AnnotationConfigServletWebServerApplicationContext";

	/**
	 * Spring 应用上下文（Reactive 场景）
	 * The class name of application context that will be used by default for reactive web environments.
	 */
	public static final String DEFAULT_REACTIVE_WEB_CONTEXT_CLASS = "org.springframework."
			+ "boot.web.reactive.context.AnnotationConfigReactiveWebServerApplicationContext";

	/**
	 * Default banner location.
	 */
	public static final String BANNER_LOCATION_PROPERTY_VALUE = SpringApplicationBannerPrinter.DEFAULT_BANNER_LOCATION;

	/**
	 * Banner location property key.
	 */
	public static final String BANNER_LOCATION_PROPERTY = SpringApplicationBannerPrinter.BANNER_LOCATION_PROPERTY;

	private static final String SYSTEM_PROPERTY_JAVA_AWT_HEADLESS = "java.awt.headless";

	private static final Log logger = LogFactory.getLog(SpringApplication.class);
	/**
	 * 主 Bean（通常为我们的启动类，优先注册）
	 */
	private Set<Class<?>> primarySources;
	/**
	 * 来源 Bean（优先注册）
	 */
	private Set<String> sources = new LinkedHashSet<>();
	/**
	 * 启动类
	 */
	private Class<?> mainApplicationClass;
	/**
	 * Banner 打印模式
	 */
	private Banner.Mode bannerMode = Banner.Mode.CONSOLE;
	/**
	 * 是否打印应用启动耗时日志
	 */
	private boolean logStartupInfo = true;
	/**
	 * 是否接收命令行中的参数
	 */
	private boolean addCommandLineProperties = true;
	/**
	 * 是否设置 ConversionService 类型转换器
	 */
	private boolean addConversionService = true;
	/**
	 * Banner 对象（用于输出横幅）
	 */
	private Banner banner;
	/**
	 * 资源加载对象
	 */
	private ResourceLoader resourceLoader;
	/**
	 * Bean 名称生成器
	 */
	private BeanNameGenerator beanNameGenerator;
	/**
	 * Spring 应用的环境对象
	 */
	private ConfigurableEnvironment environment;
	/**
	 * Spring 应用上下文的 Class 对象
	 */
	private Class<? extends ConfigurableApplicationContext> applicationContextClass;
	/**
	 * Web 应用的类型（Servlet、Reactive）
	 */
	private WebApplicationType webApplicationType;

	private boolean headless = true;
	/**
	 * 是否注册钩子函数，用于 JVM 关闭时关闭 Spring 应用上下文
	 */
	private boolean registerShutdownHook = true;
	/**
	 * 保存 ApplicationContextInitializer 对象（主要是对 Spring 应用上下文做一些初始化工作）
	 */
	private List<ApplicationContextInitializer<?>> initializers;
	/**
	 * 保存 ApplicationListener 监听器（支持在整个 Spring Boot 的多个时间点进行扩展）
	 */
	private List<ApplicationListener<?>> listeners;
	/**
	 * 默认的配置项
	 */
	private Map<String, Object> defaultProperties;
	/**
	 * 额外的 profile
	 */
	private Set<String> additionalProfiles = new HashSet<>();
	/**
	 * 是否允许覆盖 BeanDefinition
	 */
	private boolean allowBeanDefinitionOverriding;
	/**
	 * 是否为自定义的 Environment 对象
	 */
	private boolean isCustomEnvironment = false;
	/**
	 * 是否支持延迟初始化（需要通过 {@link LazyInitializationExcludeFilter} 过滤）
	 */
	private boolean lazyInitialization = false;

	/**
	 * Create a new {@link SpringApplication} instance. The application context will load
	 * beans from the specified primary sources (see {@link SpringApplication class-level}
	 * documentation for details. The instance can be customized before calling
	 * {@link #run(String...)}.
	 * @param primarySources the primary bean sources
	 * @see #run(Class, String[])
	 * @see #SpringApplication(ResourceLoader, Class...)
	 * @see #setSources(Set)
	 */
	public SpringApplication(Class<?>... primarySources) {
		this(null, primarySources);
	}

	/**
	 * Create a new {@link SpringApplication} instance. The application context will load
	 * beans from the specified primary sources (see {@link SpringApplication class-level}
	 * documentation for details. The instance can be customized before calling
	 * {@link #run(String...)}.
	 * @param resourceLoader the resource loader to use
	 * @param primarySources the primary bean sources
	 * @see #run(Class, String[])
	 * @see #setSources(Set)
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public SpringApplication(ResourceLoader resourceLoader, Class<?>... primarySources) {
		// <1> 设置资源加载器
		this.resourceLoader = resourceLoader;
		Assert.notNull(primarySources, "PrimarySources must not be null");
		// <2> 设置主要的 Class 类对象，通常是我们的启动类
		this.primarySources = new LinkedHashSet<>(Arrays.asList(primarySources));
		// <3> 通过 `classpath` 判断是否存在相应的 Class 类对象，来决定当前 Web 应用的类型（REACTIVE、SERVLET、NONE），默认为 **SERVLET**
		// 不同的类型后续创建的 Environment 类型不同
		this.webApplicationType = WebApplicationType.deduceFromClasspath();
		/**
		 * <4> 初始化所有 `ApplicationContextInitializer` 类型的对象，并保存至 `initializers` 集合中
		 * 通过类加载器从 `META-INF/spring.factories` 文件中获取 ApplicationContextInitializer 类型的类名称，并进行实例化
		 */
		setInitializers((Collection) getSpringFactoriesInstances(ApplicationContextInitializer.class));
		/**
		 * <5> 初始化所有 `ApplicationListener` 类型的对象，并保存至 `listeners` 集合中
		 * 通过类加载器从 `META-INF/spring.factories` 文件中获取 ApplicationListener 类型的类名称，并进行实例化
		 * 例如有一个 {@link ConfigFileApplicationListener}
 		 */
		setListeners((Collection) getSpringFactoriesInstances(ApplicationListener.class));
		// <6> 获取当前被调用的 `main` 方法所属的 Class 类对象，并设置（主要用于打印日志）
		this.mainApplicationClass = deduceMainApplicationClass();
	}

	private Class<?> deduceMainApplicationClass() {
		try {
			// 获取当前的调用栈
			StackTraceElement[] stackTrace = new RuntimeException().getStackTrace();
			// 对调用栈进行遍历，找到 `main` 方法所在的 Class 类对象并返回
			for (StackTraceElement stackTraceElement : stackTrace) {
				if ("main".equals(stackTraceElement.getMethodName())) {
					return Class.forName(stackTraceElement.getClassName());
				}
			}
		}
		catch (ClassNotFoundException ex) {
			// Swallow and continue
		}
		return null;
	}

	/**
	 * Run the Spring application, creating and refreshing a new
	 * {@link ApplicationContext}.
	 * @param args the application arguments (usually passed from a Java main method)
	 * @return a running {@link ApplicationContext}
	 */
	public ConfigurableApplicationContext run(String... args) {
		// <1> 创建 StopWatch 对象并启动，主要用于统计当前方法执行过程的耗时
		StopWatch stopWatch = new StopWatch();
		stopWatch.start();
		ConfigurableApplicationContext context = null;
		Collection<SpringBootExceptionReporter> exceptionReporters = new ArrayList<>();
		// <2> 设置 `java.awt.headless` 属性，和 AWT 相关，暂时忽略
		configureHeadlessProperty();
		// <3> 初始化所有 `SpringApplicationRunListener` 类型的对象，并全部封装到 `SpringApplicationRunListeners` 对象中
		SpringApplicationRunListeners listeners = getRunListeners(args);
		// <4> 启动所有的 `SpringApplicationRunListener` 监听器
		// 例如 `EventPublishingRunListener` 会广播 ApplicationEvent 应用正在启动的事件，
		// 它里面封装了所有的 `ApplicationListener` 对象，那么此时就可以通过它们做一些初始化工作，进行拓展
		listeners.starting();
		try {
			// <5> 创建一个应用参数对象，将 `main(String[] args)` 方法的 `args` 参数封装起来，便于后续使用
			ApplicationArguments applicationArguments = new DefaultApplicationArguments(args);
			// <6> 准备好当前应用 Environment 环境，这里会加载出所有的配置信息，包括 `application.yaml` 和外部的属性配置
			ConfigurableEnvironment environment = prepareEnvironment(listeners, applicationArguments);

			configureIgnoreBeanInfo(environment);
			// <7> 打印 banner 内容
			Banner printedBanner = printBanner(environment);
			// <8> 对 `context`（Spring 上下文）进行实例化
			// 例如 Servlet（默认）会创建一个 AnnotationConfigServletWebServerApplicationContext 实例对象
			context = createApplicationContext();
			// <9> 获取异常报告器，通过类加载器从 `META-INF/spring.factories` 文件中获取 SpringBootExceptionReporter 类型的类名称，并进行实例化
			exceptionReporters = getSpringFactoriesInstances(SpringBootExceptionReporter.class,
					new Class[] { ConfigurableApplicationContext.class }, context);
			// <10> 对 Spring 应用上下文做一些初始化工作，例如执行 ApplicationContextInitializer#initialize(..) 方法
			prepareContext(context, environment, listeners, applicationArguments, printedBanner);
			/**
			 * <11> 刷新 Spring 应用上下文，在这里会完成所有 Spring Bean 的初始化，同时会初始化好 Servlet 容器，例如 Tomcat
			 * 这一步涉及到 Spring IoC 的所有内容，参考[死磕Spring之IoC篇 - Spring 应用上下文 ApplicationContext](https://www.cnblogs.com/lifullmoon/p/14453083.html)
			 * 在 {@link ServletWebServerApplicationContext#onRefresh()} 方法中会创建一个 Servlet 容器（默认为 Tomcat），也就是当前 Spring Boot 应用所运行的 Web 环境
 			 */
			refreshContext(context);
			// <12> 完成刷新 Spring 应用上下文的后置操作，空实现，扩展点
			afterRefresh(context, applicationArguments);
			// <13> 停止 StopWatch，统计整个 Spring Boot 应用的启动耗时，并打印
			stopWatch.stop();
			if (this.logStartupInfo) {
				new StartupInfoLogger(this.mainApplicationClass).logStarted(getApplicationLog(), stopWatch);
			}
			// <14> 对所有的 SpringApplicationRunListener 监听器进行广播，发布 ApplicationStartedEvent 应用已启动事件
			listeners.started(context);
			// <15> 回调 IoC 容器中所有 ApplicationRunner 和 CommandLineRunner 类型的启动器
			callRunners(context, applicationArguments);
		}
		catch (Throwable ex) {
			// 处理异常，同时会将异常发送至上面第 `9` 步获取到的异常报告器
			handleRunFailure(context, ex, exceptionReporters, listeners);
			throw new IllegalStateException(ex);
		}

		try {
			// <16> 对所有的 SpringApplicationRunListener 监听器进行广播，发布 ApplicationReadyEvent 应用已就绪事件
			listeners.running(context);
		}
		catch (Throwable ex) {
			// 处理异常，同时会将异常发送至上面第 `9` 步获取到的异常报告器
			handleRunFailure(context, ex, exceptionReporters, null);
			throw new IllegalStateException(ex);
		}
		return context;
	}

	private ConfigurableEnvironment prepareEnvironment(SpringApplicationRunListeners listeners,
			ApplicationArguments applicationArguments) {
		// Create and configure the environment
		// <1> 根据 Web 应用的类型创建一个 StandardEnvironment 对象 `environment`
		ConfigurableEnvironment environment = getOrCreateEnvironment();
		// <2> 为 `environment` 配置默认属性（如果有）并设置需要激活的 `profile` 们
		configureEnvironment(environment, applicationArguments.getSourceArgs());
		// <3> 将当前 `environment` 的 MutablePropertySources 封装成 SpringConfigurationPropertySources 添加到 MutablePropertySources 首部
		ConfigurationPropertySources.attach(environment);
		/**
		 * <4> 对所有的 SpringApplicationRunListener 广播 ApplicationEvent 应用环境已准备好的事件，这一步比较复杂
		 * 例如 Spring Cloud 的 BootstrapApplicationListener 监听到该事件会创建一个 ApplicationContext 作为当前 Spring 应用上下文的父容器，同时会读取 `bootstrap.yml` 文件的信息
		 * {@link ConfigFileApplicationListener} 监听到该事件然后去解析 `application.yml` 等应用配置文件的配置信息
		 */
		listeners.environmentPrepared(environment);
		// <5> 将 `environment` 绑定到当前 SpringApplication 上
		bindToSpringApplication(environment);
		// <6> 如果不是自定义的 Environment 则需要根据 Web 应用类型转换成对应 Environment 类型
		if (!this.isCustomEnvironment) {
			environment = new EnvironmentConverter(getClassLoader()).convertEnvironmentIfNecessary(environment,
					deduceEnvironmentClass());
		}
		// <7> 再次进行上面第 `3` 步的处理过程，防止上面几步对上面的 PropertySources 有修改
		ConfigurationPropertySources.attach(environment);
		// <8> 返回准备好的 `environment`
		return environment;
	}

	private Class<? extends StandardEnvironment> deduceEnvironmentClass() {
		switch (this.webApplicationType) {
		case SERVLET:
			return StandardServletEnvironment.class;
		case REACTIVE:
			return StandardReactiveWebEnvironment.class;
		default:
			return StandardEnvironment.class;
		}
	}

	private void prepareContext(ConfigurableApplicationContext context, ConfigurableEnvironment environment,
			SpringApplicationRunListeners listeners, ApplicationArguments applicationArguments, Banner printedBanner) {
		// <1> 为 Spring 应用上下文设置 Environment 环境
		context.setEnvironment(environment);
		// <2> 将一些工具 Bean 设置到 Spring 应用上下文中，供使用
		postProcessApplicationContext(context);
		// <3> 通知 ApplicationContextInitializer 对 Spring 应用上下文进行初始化工作
		// 参考 SpringApplication 构造方法
		applyInitializers(context);
		// <4> 对所有 SpringApplicationRunListener 进行广播，发布 ApplicationContextInitializedEvent 初始化事件
		listeners.contextPrepared(context);
		if (this.logStartupInfo) {
			logStartupInfo(context.getParent() == null);
			logStartupProfileInfo(context);
		}
		// Add boot specific singleton beans
		// <5> 向应用上下文注册 `main(String[])` 方法的参数 Bean 和 Banner 对象
		ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();
		beanFactory.registerSingleton("springApplicationArguments", applicationArguments);
		if (printedBanner != null) {
			beanFactory.registerSingleton("springBootBanner", printedBanner);
		}
		if (beanFactory instanceof DefaultListableBeanFactory) {
			((DefaultListableBeanFactory) beanFactory)
					.setAllowBeanDefinitionOverriding(this.allowBeanDefinitionOverriding);
		}
		if (this.lazyInitialization) {
			context.addBeanFactoryPostProcessor(new LazyInitializationBeanFactoryPostProcessor());
		}
		// Load the sources
		// <6> 获取 `primarySources`（例如你的启动类）和 `sources`（例如 Spring Cloud 中的 @BootstrapConfiguration）源对象
		Set<Object> sources = getAllSources();
		// 应用上下文的源对象不能为空
		Assert.notEmpty(sources, "Sources must not be empty");
		// <7> 将上面的源对象加载成 BeanDefinition 并注册
		load(context, sources.toArray(new Object[0]));
		// <8> 对所有的 SpringApplicationRunListener 广播 ApplicationPreparedEvent 应用已准备事件
		// 会把 ApplicationListener 添加至 Spring 应用上下文中
		listeners.contextLoaded(context);
	}

	private void refreshContext(ConfigurableApplicationContext context) {
		if (this.registerShutdownHook) {
			try {
				// 为当前 Spring 应用上下文注册一个钩子函数
				// 在 JVM 关闭时先关闭 Spring 应用上下文
				context.registerShutdownHook();
			}
			catch (AccessControlException ex) {
				// Not allowed in some environments.
			}
		}
		// 调用 AbstractApplicationContext#refresh() 方法，刷新 Spring 上下文
		refresh(context);
	}

	private void configureHeadlessProperty() {
		System.setProperty(SYSTEM_PROPERTY_JAVA_AWT_HEADLESS,
				System.getProperty(SYSTEM_PROPERTY_JAVA_AWT_HEADLESS, Boolean.toString(this.headless)));
	}

	private SpringApplicationRunListeners getRunListeners(String[] args) {
		// 指定实例化对象所使用的构造器的入参类型
		Class<?>[] types = new Class<?>[] { SpringApplication.class, String[].class };
		// 通过类加载器从 `META-INF/spring.factories` 文件中获取 SpringApplicationRunListener 类型的类名称，并进行实例化
		return new SpringApplicationRunListeners(logger,
				getSpringFactoriesInstances(SpringApplicationRunListener.class, types, this, args));
	}

	private <T> Collection<T> getSpringFactoriesInstances(Class<T> type) {
		return getSpringFactoriesInstances(type, new Class<?>[] {});
	}

	private <T> Collection<T> getSpringFactoriesInstances(Class<T> type, Class<?>[] parameterTypes, Object... args) {
		// <1> 获取类加载器
		ClassLoader classLoader = getClassLoader();
		// Use names and ensure unique to protect against duplicates
		// <2> 通过类加载器从 `META-INF/spring.factories` 文件中获取类型为 `type` 的类名称
		Set<String> names = new LinkedHashSet<>(SpringFactoriesLoader.loadFactoryNames(type, classLoader));
		// <3> 为上一步获取到的所有类名称创建对应的实例对象
		List<T> instances = createSpringFactoriesInstances(type, parameterTypes, classLoader, args, names);
		// <4> 通过 `@Order` 注解进行排序
		AnnotationAwareOrderComparator.sort(instances);
		// <5> 返回排序后的 `type` 类型的实例对象
		return instances;
	}

	@SuppressWarnings("unchecked")
	private <T> List<T> createSpringFactoriesInstances(Class<T> type, Class<?>[] parameterTypes,
			ClassLoader classLoader, Object[] args, Set<String> names) {
		List<T> instances = new ArrayList<>(names.size());
		// 遍历所有的类名称，依次创建实例对象，一并返回
		for (String name : names) {
			try {
				// 获取对应的 Class 类对象
				Class<?> instanceClass = ClassUtils.forName(name, classLoader);
				// 对 Class 类对象进行校验，判断类型是否匹配
				Assert.isAssignable(type, instanceClass);
				// 获取指定入参类型（ConfigurableApplicationContext）的构造器
				Constructor<?> constructor = instanceClass.getDeclaredConstructor(parameterTypes);
				// 通过构造器创建一个实例对象
				T instance = (T) BeanUtils.instantiateClass(constructor, args);
				instances.add(instance);
			}
			catch (Throwable ex) {
				throw new IllegalArgumentException("Cannot instantiate " + type + " : " + name, ex);
			}
		}
		return instances;
	}

	private ConfigurableEnvironment getOrCreateEnvironment() {
		if (this.environment != null) {
			return this.environment;
		}
		switch (this.webApplicationType) {
		case SERVLET:
			return new StandardServletEnvironment();
		case REACTIVE:
			return new StandardReactiveWebEnvironment();
		default:
			return new StandardEnvironment();
		}
	}

	/**
	 * Template method delegating to
	 * {@link #configurePropertySources(ConfigurableEnvironment, String[])} and
	 * {@link #configureProfiles(ConfigurableEnvironment, String[])} in that order.
	 * Override this method for complete control over Environment customization, or one of
	 * the above for fine-grained control over property sources or profiles, respectively.
	 * @param environment this application's environment
	 * @param args arguments passed to the {@code run} method
	 * @see #configureProfiles(ConfigurableEnvironment, String[])
	 * @see #configurePropertySources(ConfigurableEnvironment, String[])
	 */
	protected void configureEnvironment(ConfigurableEnvironment environment, String[] args) {
		if (this.addConversionService) {
			// <1> 获取 ConversionService 类型转换器并设置到 Environment 对象中
			ConversionService conversionService = ApplicationConversionService.getSharedInstance();
			environment.setConversionService((ConfigurableConversionService) conversionService);
		}
		// <2> 配置属性源里面的属性
		// 例如有默认属性则将他们添加至最后，命令行中的参数则添加至最前面
		configurePropertySources(environment, args);
		// <3> 设置需要激活的 `profile` 们
		// 例如通过 `-Dspring.profiles.active=dev` 配置需要激活的环境
		configureProfiles(environment, args);
	}

	/**
	 * Add, remove or re-order any {@link PropertySource}s in this application's
	 * environment.
	 * @param environment this application's environment
	 * @param args arguments passed to the {@code run} method
	 * @see #configureEnvironment(ConfigurableEnvironment, String[])
	 */
	protected void configurePropertySources(ConfigurableEnvironment environment, String[] args) {
		MutablePropertySources sources = environment.getPropertySources();
		// 如果默认配置属性不为空则添加至最后
		if (this.defaultProperties != null && !this.defaultProperties.isEmpty()) {
			sources.addLast(new MapPropertySource("defaultProperties", this.defaultProperties));
		}
		// 如果 `main` 方法有入参
		if (this.addCommandLineProperties && args.length > 0) {
			String name = CommandLinePropertySource.COMMAND_LINE_PROPERTY_SOURCE_NAME;
			if (sources.contains(name)) {
				PropertySource<?> source = sources.get(name);
				CompositePropertySource composite = new CompositePropertySource(name);
				composite.addPropertySource(
						new SimpleCommandLinePropertySource("springApplicationCommandLineArgs", args));
				composite.addPropertySource(source);
				sources.replace(name, composite);
			}
			else {
				// 将命令行中的入参添加至最前面
				// 例如 'java -jar xxx.jar --spring.profiles.active=dev'，那么这里就可以获取到
				sources.addFirst(new SimpleCommandLinePropertySource(args));
			}
		}
	}

	/**
	 * Configure which profiles are active (or active by default) for this application
	 * environment. Additional profiles may be activated during configuration file
	 * processing via the {@code spring.profiles.active} property.
	 * @param environment this application's environment
	 * @param args arguments passed to the {@code run} method
	 * @see #configureEnvironment(ConfigurableEnvironment, String[])
	 * @see org.springframework.boot.context.config.ConfigFileApplicationListener
	 */
	protected void configureProfiles(ConfigurableEnvironment environment, String[] args) {
		Set<String> profiles = new LinkedHashSet<>(this.additionalProfiles);
		profiles.addAll(Arrays.asList(environment.getActiveProfiles()));
		environment.setActiveProfiles(StringUtils.toStringArray(profiles));
	}

	private void configureIgnoreBeanInfo(ConfigurableEnvironment environment) {
		if (System.getProperty(CachedIntrospectionResults.IGNORE_BEANINFO_PROPERTY_NAME) == null) {
			Boolean ignore = environment.getProperty("spring.beaninfo.ignore", Boolean.class, Boolean.TRUE);
			System.setProperty(CachedIntrospectionResults.IGNORE_BEANINFO_PROPERTY_NAME, ignore.toString());
		}
	}

	/**
	 * Bind the environment to the {@link SpringApplication}.
	 * @param environment the environment to bind
	 */
	protected void bindToSpringApplication(ConfigurableEnvironment environment) {
		try {
			Binder.get(environment).bind("spring.main", Bindable.ofInstance(this));
		}
		catch (Exception ex) {
			throw new IllegalStateException("Cannot bind to SpringApplication", ex);
		}
	}

	private Banner printBanner(ConfigurableEnvironment environment) {
		if (this.bannerMode == Banner.Mode.OFF) {
			return null;
		}
		ResourceLoader resourceLoader = (this.resourceLoader != null) ? this.resourceLoader
				: new DefaultResourceLoader(getClassLoader());
		SpringApplicationBannerPrinter bannerPrinter = new SpringApplicationBannerPrinter(resourceLoader, this.banner);
		if (this.bannerMode == Mode.LOG) {
			return bannerPrinter.print(environment, this.mainApplicationClass, logger);
		}
		return bannerPrinter.print(environment, this.mainApplicationClass, System.out);
	}

	/**
	 * Strategy method used to create the {@link ApplicationContext}. By default this
	 * method will respect any explicitly set application context or application context
	 * class before falling back to a suitable default.
	 * @return the application context (not yet refreshed)
	 * @see #setApplicationContextClass(Class)
	 */
	protected ConfigurableApplicationContext createApplicationContext() {
		Class<?> contextClass = this.applicationContextClass;
		if (contextClass == null) {
			try {
				switch (this.webApplicationType) {
				case SERVLET:
					// org.springframework.boot.web.servlet.context.AnnotationConfigServletWebServerApplicationContext
					contextClass = Class.forName(DEFAULT_SERVLET_WEB_CONTEXT_CLASS);
					break;
				case REACTIVE:
					contextClass = Class.forName(DEFAULT_REACTIVE_WEB_CONTEXT_CLASS);
					break;
				default:
					contextClass = Class.forName(DEFAULT_CONTEXT_CLASS);
				}
			}
			catch (ClassNotFoundException ex) {
				throw new IllegalStateException(
						"Unable create a default ApplicationContext, please specify an ApplicationContextClass", ex);
			}
		}
		// 实例化这个 Class 对象
		return (ConfigurableApplicationContext) BeanUtils.instantiateClass(contextClass);
	}

	/**
	 * Apply any relevant post processing the {@link ApplicationContext}. Subclasses can
	 * apply additional processing as required.
	 * @param context the application context
	 */
	protected void postProcessApplicationContext(ConfigurableApplicationContext context) {
		if (this.beanNameGenerator != null) {
			// 注册 Bean 名称生成器
			context.getBeanFactory().registerSingleton(AnnotationConfigUtils.CONFIGURATION_BEAN_NAME_GENERATOR,
					this.beanNameGenerator);
		}
		if (this.resourceLoader != null) {
			if (context instanceof GenericApplicationContext) {
				// 设置资源加载器
				((GenericApplicationContext) context).setResourceLoader(this.resourceLoader);
			}
			if (context instanceof DefaultResourceLoader) {
				((DefaultResourceLoader) context).setClassLoader(this.resourceLoader.getClassLoader());
			}
		}
		if (this.addConversionService) {
			// 设置类型转换器
			context.getBeanFactory().setConversionService(ApplicationConversionService.getSharedInstance());
		}
	}

	/**
	 * Apply any {@link ApplicationContextInitializer}s to the context before it is
	 * refreshed.
	 * @param context the configured ApplicationContext (not refreshed yet)
	 * @see ConfigurableApplicationContext#refresh()
	 */
	@SuppressWarnings({ "rawtypes", "unchecked" })
	protected void applyInitializers(ConfigurableApplicationContext context) {
		for (ApplicationContextInitializer initializer : getInitializers()) {
			Class<?> requiredType = GenericTypeResolver.resolveTypeArgument(initializer.getClass(),
					ApplicationContextInitializer.class);
			Assert.isInstanceOf(requiredType, context, "Unable to call initializer.");
			// 执行 Spring 应用上下文初始器
			// 例如 ContextIdApplicationContextInitializer 会向 Spring 应用上下文注册一个 ContextId 对象
			initializer.initialize(context);
		}
	}

	/**
	 * Called to log startup information, subclasses may override to add additional
	 * logging.
	 * @param isRoot true if this application is the root of a context hierarchy
	 */
	protected void logStartupInfo(boolean isRoot) {
		if (isRoot) {
			new StartupInfoLogger(this.mainApplicationClass).logStarting(getApplicationLog());
		}
	}

	/**
	 * Called to log active profile information.
	 * @param context the application context
	 */
	protected void logStartupProfileInfo(ConfigurableApplicationContext context) {
		Log log = getApplicationLog();
		if (log.isInfoEnabled()) {
			String[] activeProfiles = context.getEnvironment().getActiveProfiles();
			if (ObjectUtils.isEmpty(activeProfiles)) {
				String[] defaultProfiles = context.getEnvironment().getDefaultProfiles();
				log.info("No active profile set, falling back to default profiles: "
						+ StringUtils.arrayToCommaDelimitedString(defaultProfiles));
			}
			else {
				log.info("The following profiles are active: "
						+ StringUtils.arrayToCommaDelimitedString(activeProfiles));
			}
		}
	}

	/**
	 * Returns the {@link Log} for the application. By default will be deduced.
	 * @return the application log
	 */
	protected Log getApplicationLog() {
		if (this.mainApplicationClass == null) {
			return logger;
		}
		return LogFactory.getLog(this.mainApplicationClass);
	}

	/**
	 * Load beans into the application context.
	 * @param context the context to load beans into
	 * @param sources the sources to load
	 */
	protected void load(ApplicationContext context, Object[] sources) {
		if (logger.isDebugEnabled()) {
			logger.debug("Loading source " + StringUtils.arrayToCommaDelimitedString(sources));
		}
		// <1> 获取 BeanDefinition 注册中心
		// <2> 创建一个 BeanDefinitionLoader 对象 `loader`，用于加载 `sources` 几个 Bean
		BeanDefinitionLoader loader = createBeanDefinitionLoader(getBeanDefinitionRegistry(context), sources);
		// <3> 设置 `loader` 的 Bean 名称生成器，资源加载器，Environment 环境对象
		if (this.beanNameGenerator != null) {
			loader.setBeanNameGenerator(this.beanNameGenerator);
		}
		if (this.resourceLoader != null) {
			loader.setResourceLoader(this.resourceLoader);
		}
		if (this.environment != null) {
			loader.setEnvironment(this.environment);
		}
		// <4> 进行加载，将 `sources`（需要有 `@Component` 注解）解析出对应的 BeanDefinition 并注册
		loader.load();
	}

	/**
	 * The ResourceLoader that will be used in the ApplicationContext.
	 * @return the resourceLoader the resource loader that will be used in the
	 * ApplicationContext (or null if the default)
	 */
	public ResourceLoader getResourceLoader() {
		return this.resourceLoader;
	}

	/**
	 * Either the ClassLoader that will be used in the ApplicationContext (if
	 * {@link #setResourceLoader(ResourceLoader) resourceLoader} is set, or the context
	 * class loader (if not null), or the loader of the Spring {@link ClassUtils} class.
	 * @return a ClassLoader (never null)
	 */
	public ClassLoader getClassLoader() {
		if (this.resourceLoader != null) {
			return this.resourceLoader.getClassLoader();
		}
		return ClassUtils.getDefaultClassLoader();
	}

	/**
	 * Get the bean definition registry.
	 * @param context the application context
	 * @return the BeanDefinitionRegistry if it can be determined
	 */
	private BeanDefinitionRegistry getBeanDefinitionRegistry(ApplicationContext context) {
		if (context instanceof BeanDefinitionRegistry) {
			return (BeanDefinitionRegistry) context;
		}
		if (context instanceof AbstractApplicationContext) {
			return (BeanDefinitionRegistry) ((AbstractApplicationContext) context).getBeanFactory();
		}
		throw new IllegalStateException("Could not locate BeanDefinitionRegistry");
	}

	/**
	 * Factory method used to create the {@link BeanDefinitionLoader}.
	 * @param registry the bean definition registry
	 * @param sources the sources to load
	 * @return the {@link BeanDefinitionLoader} that will be used to load beans
	 */
	protected BeanDefinitionLoader createBeanDefinitionLoader(BeanDefinitionRegistry registry, Object[] sources) {
		return new BeanDefinitionLoader(registry, sources);
	}

	/**
	 * Refresh the underlying {@link ApplicationContext}.
	 * @param applicationContext the application context to refresh
	 */
	protected void refresh(ApplicationContext applicationContext) {
		Assert.isInstanceOf(AbstractApplicationContext.class, applicationContext);
		((AbstractApplicationContext) applicationContext).refresh();
	}

	/**
	 * Called after the context has been refreshed.
	 * @param context the application context
	 * @param args the application arguments
	 */
	protected void afterRefresh(ConfigurableApplicationContext context, ApplicationArguments args) {
	}

	private void callRunners(ApplicationContext context, ApplicationArguments args) {
		List<Object> runners = new ArrayList<>();
		runners.addAll(context.getBeansOfType(ApplicationRunner.class).values());
		runners.addAll(context.getBeansOfType(CommandLineRunner.class).values());
		AnnotationAwareOrderComparator.sort(runners);
		for (Object runner : new LinkedHashSet<>(runners)) {
			if (runner instanceof ApplicationRunner) {
				callRunner((ApplicationRunner) runner, args);
			}
			if (runner instanceof CommandLineRunner) {
				callRunner((CommandLineRunner) runner, args);
			}
		}
	}

	private void callRunner(ApplicationRunner runner, ApplicationArguments args) {
		try {
			(runner).run(args);
		}
		catch (Exception ex) {
			throw new IllegalStateException("Failed to execute ApplicationRunner", ex);
		}
	}

	private void callRunner(CommandLineRunner runner, ApplicationArguments args) {
		try {
			(runner).run(args.getSourceArgs());
		}
		catch (Exception ex) {
			throw new IllegalStateException("Failed to execute CommandLineRunner", ex);
		}
	}

	private void handleRunFailure(ConfigurableApplicationContext context, Throwable exception,
			Collection<SpringBootExceptionReporter> exceptionReporters, SpringApplicationRunListeners listeners) {
		try {
			try {
				handleExitCode(context, exception);
				if (listeners != null) {
					listeners.failed(context, exception);
				}
			}
			finally {
				reportFailure(exceptionReporters, exception);
				if (context != null) {
					context.close();
				}
			}
		}
		catch (Exception ex) {
			logger.warn("Unable to close ApplicationContext", ex);
		}
		ReflectionUtils.rethrowRuntimeException(exception);
	}

	private void reportFailure(Collection<SpringBootExceptionReporter> exceptionReporters, Throwable failure) {
		try {
			for (SpringBootExceptionReporter reporter : exceptionReporters) {
				if (reporter.reportException(failure)) {
					registerLoggedException(failure);
					return;
				}
			}
		}
		catch (Throwable ex) {
			// Continue with normal handling of the original failure
		}
		if (logger.isErrorEnabled()) {
			logger.error("Application run failed", failure);
			registerLoggedException(failure);
		}
	}

	/**
	 * Register that the given exception has been logged. By default, if the running in
	 * the main thread, this method will suppress additional printing of the stacktrace.
	 * @param exception the exception that was logged
	 */
	protected void registerLoggedException(Throwable exception) {
		SpringBootExceptionHandler handler = getSpringBootExceptionHandler();
		if (handler != null) {
			handler.registerLoggedException(exception);
		}
	}

	private void handleExitCode(ConfigurableApplicationContext context, Throwable exception) {
		int exitCode = getExitCodeFromException(context, exception);
		if (exitCode != 0) {
			if (context != null) {
				context.publishEvent(new ExitCodeEvent(context, exitCode));
			}
			SpringBootExceptionHandler handler = getSpringBootExceptionHandler();
			if (handler != null) {
				handler.registerExitCode(exitCode);
			}
		}
	}

	private int getExitCodeFromException(ConfigurableApplicationContext context, Throwable exception) {
		int exitCode = getExitCodeFromMappedException(context, exception);
		if (exitCode == 0) {
			exitCode = getExitCodeFromExitCodeGeneratorException(exception);
		}
		return exitCode;
	}

	private int getExitCodeFromMappedException(ConfigurableApplicationContext context, Throwable exception) {
		if (context == null || !context.isActive()) {
			return 0;
		}
		ExitCodeGenerators generators = new ExitCodeGenerators();
		Collection<ExitCodeExceptionMapper> beans = context.getBeansOfType(ExitCodeExceptionMapper.class).values();
		generators.addAll(exception, beans);
		return generators.getExitCode();
	}

	private int getExitCodeFromExitCodeGeneratorException(Throwable exception) {
		if (exception == null) {
			return 0;
		}
		if (exception instanceof ExitCodeGenerator) {
			return ((ExitCodeGenerator) exception).getExitCode();
		}
		return getExitCodeFromExitCodeGeneratorException(exception.getCause());
	}

	SpringBootExceptionHandler getSpringBootExceptionHandler() {
		if (isMainThread(Thread.currentThread())) {
			return SpringBootExceptionHandler.forCurrentThread();
		}
		return null;
	}

	private boolean isMainThread(Thread currentThread) {
		return ("main".equals(currentThread.getName()) || "restartedMain".equals(currentThread.getName()))
				&& "main".equals(currentThread.getThreadGroup().getName());
	}

	/**
	 * Returns the main application class that has been deduced or explicitly configured.
	 * @return the main application class or {@code null}
	 */
	public Class<?> getMainApplicationClass() {
		return this.mainApplicationClass;
	}

	/**
	 * Set a specific main application class that will be used as a log source and to
	 * obtain version information. By default the main application class will be deduced.
	 * Can be set to {@code null} if there is no explicit application class.
	 * @param mainApplicationClass the mainApplicationClass to set or {@code null}
	 */
	public void setMainApplicationClass(Class<?> mainApplicationClass) {
		this.mainApplicationClass = mainApplicationClass;
	}

	/**
	 * Returns the type of web application that is being run.
	 * @return the type of web application
	 * @since 2.0.0
	 */
	public WebApplicationType getWebApplicationType() {
		return this.webApplicationType;
	}

	/**
	 * Sets the type of web application to be run. If not explicitly set the type of web
	 * application will be deduced based on the classpath.
	 * @param webApplicationType the web application type
	 * @since 2.0.0
	 */
	public void setWebApplicationType(WebApplicationType webApplicationType) {
		Assert.notNull(webApplicationType, "WebApplicationType must not be null");
		this.webApplicationType = webApplicationType;
	}

	/**
	 * Sets if bean definition overriding, by registering a definition with the same name
	 * as an existing definition, should be allowed. Defaults to {@code false}.
	 * @param allowBeanDefinitionOverriding if overriding is allowed
	 * @since 2.1.0
	 * @see DefaultListableBeanFactory#setAllowBeanDefinitionOverriding(boolean)
	 */
	public void setAllowBeanDefinitionOverriding(boolean allowBeanDefinitionOverriding) {
		this.allowBeanDefinitionOverriding = allowBeanDefinitionOverriding;
	}

	/**
	 * Sets if beans should be initialized lazily. Defaults to {@code false}.
	 * @param lazyInitialization if initialization should be lazy
	 * @since 2.2
	 * @see BeanDefinition#setLazyInit(boolean)
	 */
	public void setLazyInitialization(boolean lazyInitialization) {
		this.lazyInitialization = lazyInitialization;
	}

	/**
	 * Sets if the application is headless and should not instantiate AWT. Defaults to
	 * {@code true} to prevent java icons appearing.
	 * @param headless if the application is headless
	 */
	public void setHeadless(boolean headless) {
		this.headless = headless;
	}

	/**
	 * Sets if the created {@link ApplicationContext} should have a shutdown hook
	 * registered. Defaults to {@code true} to ensure that JVM shutdowns are handled
	 * gracefully.
	 * @param registerShutdownHook if the shutdown hook should be registered
	 */
	public void setRegisterShutdownHook(boolean registerShutdownHook) {
		this.registerShutdownHook = registerShutdownHook;
	}

	/**
	 * Sets the {@link Banner} instance which will be used to print the banner when no
	 * static banner file is provided.
	 * @param banner the Banner instance to use
	 */
	public void setBanner(Banner banner) {
		this.banner = banner;
	}

	/**
	 * Sets the mode used to display the banner when the application runs. Defaults to
	 * {@code Banner.Mode.CONSOLE}.
	 * @param bannerMode the mode used to display the banner
	 */
	public void setBannerMode(Banner.Mode bannerMode) {
		this.bannerMode = bannerMode;
	}

	/**
	 * Sets if the application information should be logged when the application starts.
	 * Defaults to {@code true}.
	 * @param logStartupInfo if startup info should be logged.
	 */
	public void setLogStartupInfo(boolean logStartupInfo) {
		this.logStartupInfo = logStartupInfo;
	}

	/**
	 * Sets if a {@link CommandLinePropertySource} should be added to the application
	 * context in order to expose arguments. Defaults to {@code true}.
	 * @param addCommandLineProperties if command line arguments should be exposed
	 */
	public void setAddCommandLineProperties(boolean addCommandLineProperties) {
		this.addCommandLineProperties = addCommandLineProperties;
	}

	/**
	 * Sets if the {@link ApplicationConversionService} should be added to the application
	 * context's {@link Environment}.
	 * @param addConversionService if the application conversion service should be added
	 * @since 2.1.0
	 */
	public void setAddConversionService(boolean addConversionService) {
		this.addConversionService = addConversionService;
	}

	/**
	 * Set default environment properties which will be used in addition to those in the
	 * existing {@link Environment}.
	 * @param defaultProperties the additional properties to set
	 */
	public void setDefaultProperties(Map<String, Object> defaultProperties) {
		this.defaultProperties = defaultProperties;
	}

	/**
	 * Convenient alternative to {@link #setDefaultProperties(Map)}.
	 * @param defaultProperties some {@link Properties}
	 */
	public void setDefaultProperties(Properties defaultProperties) {
		this.defaultProperties = new HashMap<>();
		for (Object key : Collections.list(defaultProperties.propertyNames())) {
			this.defaultProperties.put((String) key, defaultProperties.get(key));
		}
	}

	/**
	 * Set additional profile values to use (on top of those set in system or command line
	 * properties).
	 * @param profiles the additional profiles to set
	 */
	public void setAdditionalProfiles(String... profiles) {
		this.additionalProfiles = new LinkedHashSet<>(Arrays.asList(profiles));
	}

	/**
	 * Sets the bean name generator that should be used when generating bean names.
	 * @param beanNameGenerator the bean name generator
	 */
	public void setBeanNameGenerator(BeanNameGenerator beanNameGenerator) {
		this.beanNameGenerator = beanNameGenerator;
	}

	/**
	 * Sets the underlying environment that should be used with the created application
	 * context.
	 * @param environment the environment
	 */
	public void setEnvironment(ConfigurableEnvironment environment) {
		this.isCustomEnvironment = true;
		this.environment = environment;
	}

	/**
	 * Add additional items to the primary sources that will be added to an
	 * ApplicationContext when {@link #run(String...)} is called.
	 * <p>
	 * The sources here are added to those that were set in the constructor. Most users
	 * should consider using {@link #getSources()}/{@link #setSources(Set)} rather than
	 * calling this method.
	 * @param additionalPrimarySources the additional primary sources to add
	 * @see #SpringApplication(Class...)
	 * @see #getSources()
	 * @see #setSources(Set)
	 * @see #getAllSources()
	 */
	public void addPrimarySources(Collection<Class<?>> additionalPrimarySources) {
		this.primarySources.addAll(additionalPrimarySources);
	}

	/**
	 * Returns a mutable set of the sources that will be added to an ApplicationContext
	 * when {@link #run(String...)} is called.
	 * <p>
	 * Sources set here will be used in addition to any primary sources set in the
	 * constructor.
	 * @return the application sources.
	 * @see #SpringApplication(Class...)
	 * @see #getAllSources()
	 */
	public Set<String> getSources() {
		return this.sources;
	}

	/**
	 * Set additional sources that will be used to create an ApplicationContext. A source
	 * can be: a class name, package name, or an XML resource location.
	 * <p>
	 * Sources set here will be used in addition to any primary sources set in the
	 * constructor.
	 * @param sources the application sources to set
	 * @see #SpringApplication(Class...)
	 * @see #getAllSources()
	 */
	public void setSources(Set<String> sources) {
		Assert.notNull(sources, "Sources must not be null");
		this.sources = new LinkedHashSet<>(sources);
	}

	/**
	 * Return an immutable set of all the sources that will be added to an
	 * ApplicationContext when {@link #run(String...)} is called. This method combines any
	 * primary sources specified in the constructor with any additional ones that have
	 * been {@link #setSources(Set) explicitly set}.
	 * @return an immutable set of all sources
	 */
	public Set<Object> getAllSources() {
		Set<Object> allSources = new LinkedHashSet<>();
		if (!CollectionUtils.isEmpty(this.primarySources)) {
			allSources.addAll(this.primarySources);
		}
		if (!CollectionUtils.isEmpty(this.sources)) {
			allSources.addAll(this.sources);
		}
		return Collections.unmodifiableSet(allSources);
	}

	/**
	 * Sets the {@link ResourceLoader} that should be used when loading resources.
	 * @param resourceLoader the resource loader
	 */
	public void setResourceLoader(ResourceLoader resourceLoader) {
		Assert.notNull(resourceLoader, "ResourceLoader must not be null");
		this.resourceLoader = resourceLoader;
	}

	/**
	 * Sets the type of Spring {@link ApplicationContext} that will be created. If not
	 * specified defaults to {@link #DEFAULT_SERVLET_WEB_CONTEXT_CLASS} for web based
	 * applications or {@link AnnotationConfigApplicationContext} for non web based
	 * applications.
	 * @param applicationContextClass the context class to set
	 */
	public void setApplicationContextClass(Class<? extends ConfigurableApplicationContext> applicationContextClass) {
		this.applicationContextClass = applicationContextClass;
		this.webApplicationType = WebApplicationType.deduceFromApplicationContext(applicationContextClass);
	}

	/**
	 * Sets the {@link ApplicationContextInitializer} that will be applied to the Spring
	 * {@link ApplicationContext}.
	 * @param initializers the initializers to set
	 */
	public void setInitializers(Collection<? extends ApplicationContextInitializer<?>> initializers) {
		this.initializers = new ArrayList<>(initializers);
	}

	/**
	 * Add {@link ApplicationContextInitializer}s to be applied to the Spring
	 * {@link ApplicationContext}.
	 * @param initializers the initializers to add
	 */
	public void addInitializers(ApplicationContextInitializer<?>... initializers) {
		this.initializers.addAll(Arrays.asList(initializers));
	}

	/**
	 * Returns read-only ordered Set of the {@link ApplicationContextInitializer}s that
	 * will be applied to the Spring {@link ApplicationContext}.
	 * @return the initializers
	 */
	public Set<ApplicationContextInitializer<?>> getInitializers() {
		return asUnmodifiableOrderedSet(this.initializers);
	}

	/**
	 * Sets the {@link ApplicationListener}s that will be applied to the SpringApplication
	 * and registered with the {@link ApplicationContext}.
	 * @param listeners the listeners to set
	 */
	public void setListeners(Collection<? extends ApplicationListener<?>> listeners) {
		this.listeners = new ArrayList<>(listeners);
	}

	/**
	 * Add {@link ApplicationListener}s to be applied to the SpringApplication and
	 * registered with the {@link ApplicationContext}.
	 * @param listeners the listeners to add
	 */
	public void addListeners(ApplicationListener<?>... listeners) {
		this.listeners.addAll(Arrays.asList(listeners));
	}

	/**
	 * Returns read-only ordered Set of the {@link ApplicationListener}s that will be
	 * applied to the SpringApplication and registered with the {@link ApplicationContext}
	 * .
	 * @return the listeners
	 */
	public Set<ApplicationListener<?>> getListeners() {
		return asUnmodifiableOrderedSet(this.listeners);
	}

	/**
	 * Static helper that can be used to run a {@link SpringApplication} from the
	 * specified source using default settings.
	 * @param primarySource the primary source to load
	 * @param args the application arguments (usually passed from a Java main method)
	 * @return the running {@link ApplicationContext}
	 */
	public static ConfigurableApplicationContext run(Class<?> primarySource, String... args) {
		return run(new Class<?>[] { primarySource }, args);
	}

	/**
	 * Static helper that can be used to run a {@link SpringApplication} from the
	 * specified sources using default settings and user supplied arguments.
	 * @param primarySources the primary sources to load
	 * @param args the application arguments (usually passed from a Java main method)
	 * @return the running {@link ApplicationContext}
	 */
	public static ConfigurableApplicationContext run(Class<?>[] primarySources, String[] args) {
		return new SpringApplication(primarySources).run(args);
	}

	/**
	 * A basic main that can be used to launch an application. This method is useful when
	 * application sources are defined via a {@literal --spring.main.sources} command line
	 * argument.
	 * <p>
	 * Most developers will want to define their own main method and call the
	 * {@link #run(Class, String...) run} method instead.
	 * @param args command line arguments
	 * @throws Exception if the application cannot be started
	 * @see SpringApplication#run(Class[], String[])
	 * @see SpringApplication#run(Class, String...)
	 */
	public static void main(String[] args) throws Exception {
		SpringApplication.run(new Class<?>[0], args);
	}

	/**
	 * Static helper that can be used to exit a {@link SpringApplication} and obtain a
	 * code indicating success (0) or otherwise. Does not throw exceptions but should
	 * print stack traces of any encountered. Applies the specified
	 * {@link ExitCodeGenerator} in addition to any Spring beans that implement
	 * {@link ExitCodeGenerator}. In the case of multiple exit codes the highest value
	 * will be used (or if all values are negative, the lowest value will be used)
	 * @param context the context to close if possible
	 * @param exitCodeGenerators exist code generators
	 * @return the outcome (0 if successful)
	 */
	public static int exit(ApplicationContext context, ExitCodeGenerator... exitCodeGenerators) {
		Assert.notNull(context, "Context must not be null");
		int exitCode = 0;
		try {
			try {
				ExitCodeGenerators generators = new ExitCodeGenerators();
				Collection<ExitCodeGenerator> beans = context.getBeansOfType(ExitCodeGenerator.class).values();
				generators.addAll(exitCodeGenerators);
				generators.addAll(beans);
				exitCode = generators.getExitCode();
				if (exitCode != 0) {
					context.publishEvent(new ExitCodeEvent(context, exitCode));
				}
			}
			finally {
				close(context);
			}
		}
		catch (Exception ex) {
			ex.printStackTrace();
			exitCode = (exitCode != 0) ? exitCode : 1;
		}
		return exitCode;
	}

	private static void close(ApplicationContext context) {
		if (context instanceof ConfigurableApplicationContext) {
			ConfigurableApplicationContext closable = (ConfigurableApplicationContext) context;
			closable.close();
		}
	}

	private static <E> Set<E> asUnmodifiableOrderedSet(Collection<E> elements) {
		List<E> list = new ArrayList<>(elements);
		list.sort(AnnotationAwareOrderComparator.INSTANCE);
		return new LinkedHashSet<>(list);
	}

}
