/*
 * Copyright 2012-2020 the original author or authors.
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

package org.springframework.boot.context.config;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import org.apache.commons.logging.Log;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.event.ApplicationPreparedEvent;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.*;
import org.springframework.boot.logging.DeferredLog;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ConfigurationClassPostProcessor;
import org.springframework.context.event.SmartApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.Profiles;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.ResourceUtils;
import org.springframework.util.StringUtils;

/**
 * {@link EnvironmentPostProcessor} that configures the context environment by loading
 * properties from well known file locations. By default properties will be loaded from
 * 'application.properties' and/or 'application.yml' files in the following locations:
 * <ul>
 * <li>file:./config/</li>
 * <li>file:./</li>
 * <li>classpath:config/</li>
 * <li>classpath:</li>
 * </ul>
 * The list is ordered by precedence (properties defined in locations higher in the list
 * override those defined in lower locations).
 * <p>
 * Alternative search locations and names can be specified using
 * {@link #setSearchLocations(String)} and {@link #setSearchNames(String)}.
 * <p>
 * Additional files will also be loaded based on active profiles. For example if a 'web'
 * profile is active 'application-web.properties' and 'application-web.yml' will be
 * considered.
 * <p>
 * The 'spring.config.name' property can be used to specify an alternative name to load
 * and the 'spring.config.location' property can be used to specify alternative search
 * locations or specific files.
 * <p>
 *
 * @author Dave Syer
 * @author Phillip Webb
 * @author Stephane Nicoll
 * @author Andy Wilkinson
 * @author Eddú Meléndez
 * @author Madhura Bhave
 * @since 1.0.0
 */
public class ConfigFileApplicationListener implements EnvironmentPostProcessor, SmartApplicationListener, Ordered {

	/** 默认值的 PropertySource 在 Environment 中的 key */
	private static final String DEFAULT_PROPERTIES = "defaultProperties";

	// Note the order is from least to most specific (last one wins)
	/** 支持的配置文件的路径 */
	private static final String DEFAULT_SEARCH_LOCATIONS = "classpath:/,classpath:/config/,file:./,file:./config/";

	/** 配置文件名称(不包含后缀) */
	private static final String DEFAULT_NAMES = "application";

	private static final Set<String> NO_SEARCH_NAMES = Collections.singleton(null);

	private static final Bindable<String[]> STRING_ARRAY = Bindable.of(String[].class);

	private static final Bindable<List<String>> STRING_LIST = Bindable.listOf(String.class);

	/** 需要过滤的配置项 */
	private static final Set<String> LOAD_FILTERED_PROPERTY;

	static {
		Set<String> filteredProperties = new HashSet<>();
		filteredProperties.add("spring.profiles.active");
		filteredProperties.add("spring.profiles.include");
		LOAD_FILTERED_PROPERTY = Collections.unmodifiableSet(filteredProperties);
	}

	/**
	 * The "active profiles" property name.
	 * 可通过该属性指定配置需要激活的环境配置
	 */
	public static final String ACTIVE_PROFILES_PROPERTY = "spring.profiles.active";

	/**
	 * The "includes profiles" property name.
	 */
	public static final String INCLUDE_PROFILES_PROPERTY = "spring.profiles.include";

	/**
	 * The "config name" property name.
	 * 可通过该属性指定配置文件的名称
	 */
	public static final String CONFIG_NAME_PROPERTY = "spring.config.name";

	/**
	 * The "config location" property name.
	 */
	public static final String CONFIG_LOCATION_PROPERTY = "spring.config.location";

	/**
	 * The "config additional location" property name.
	 */
	public static final String CONFIG_ADDITIONAL_LOCATION_PROPERTY = "spring.config.additional-location";

	/**
	 * The default order for the processor.
	 */
	public static final int DEFAULT_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

	private final DeferredLog logger = new DeferredLog();

	private String searchLocations;

	private String names;

	private int order = DEFAULT_ORDER;

	@Override
	public boolean supportsEventType(Class<? extends ApplicationEvent> eventType) {
		return ApplicationEnvironmentPreparedEvent.class.isAssignableFrom(eventType)
				|| ApplicationPreparedEvent.class.isAssignableFrom(eventType);
	}

	@Override
	public void onApplicationEvent(ApplicationEvent event) {
		if (event instanceof ApplicationEnvironmentPreparedEvent) {
			onApplicationEnvironmentPreparedEvent((ApplicationEnvironmentPreparedEvent) event);
		}
		if (event instanceof ApplicationPreparedEvent) {
			onApplicationPreparedEvent(event);
		}
	}

	private void onApplicationEnvironmentPreparedEvent(ApplicationEnvironmentPreparedEvent event) {
		// <1> 通过类加载器从 `META-INF/spring.factories` 文件中获取 EnvironmentPostProcessor 类型的类名称，并进行实例化
		List<EnvironmentPostProcessor> postProcessors = loadPostProcessors();
		// <2> 当前对象也是 EnvironmentPostProcessor 实现类，添加进去
		postProcessors.add(this);
		// <3> 将这些 EnvironmentPostProcessor 进行排序
		AnnotationAwareOrderComparator.sort(postProcessors);
		// <4> 遍历这些 EnvironmentPostProcessor 依次对 Environment 进行处理
		for (EnvironmentPostProcessor postProcessor : postProcessors) {
			// <4.1> 依次对当前 Environment 进行处理，上面第 `2` 步添加了当前对象，我们直接看到当前类的这个方法
			postProcessor.postProcessEnvironment(event.getEnvironment(), event.getSpringApplication());
		}
	}

	List<EnvironmentPostProcessor> loadPostProcessors() {
		return SpringFactoriesLoader.loadFactories(EnvironmentPostProcessor.class, getClass().getClassLoader());
	}

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		// 为 Spring 应用的 Environment 环境对象添加属性（包括 `application.yml`）
		addPropertySources(environment, application.getResourceLoader());
	}

	private void onApplicationPreparedEvent(ApplicationEvent event) {
		this.logger.switchTo(ConfigFileApplicationListener.class);
		addPostProcessors(((ApplicationPreparedEvent) event).getApplicationContext());
	}

	/**
	 * Add config file property sources to the specified environment.
	 * @param environment the environment to add source to
	 * @param resourceLoader the resource loader
	 * @see #addPostProcessors(ConfigurableApplicationContext)
	 */
	protected void addPropertySources(ConfigurableEnvironment environment, ResourceLoader resourceLoader) {
		// <1> 往 Spring 应用的 Environment 环境对象添加随机值的 RandomValuePropertySource 属性源
		// 这样就可直接通过 `@Value(random.uuid)` 随机获取一个 UUID
		RandomValuePropertySource.addToEnvironment(environment);
		// <2> 创建一个 Loader 对象，设置占位符处理器，资源加载器，PropertySourceLoader 配置文件加载器
		// <3> 加载配置信息，并放入 Environment 环境对象中
		// 整个处理过程有点绕，嵌套有点深，你可以理解为会将你的 Spring Boot 或者 Spring Cloud 的配置文件加载到 Environment 中，并激活对应的环境
		new Loader(environment, resourceLoader).load();
	}

	/**
	 * Add appropriate post-processors to post-configure the property-sources.
	 * @param context the context to configure
	 */
	protected void addPostProcessors(ConfigurableApplicationContext context) {
		context.addBeanFactoryPostProcessor(new PropertySourceOrderingPostProcessor(context));
	}

	public void setOrder(int order) {
		this.order = order;
	}

	@Override
	public int getOrder() {
		return this.order;
	}

	/**
	 * Set the search locations that will be considered as a comma-separated list. Each
	 * search location should be a directory path (ending in "/") and it will be prefixed
	 * by the file names constructed from {@link #setSearchNames(String) search names} and
	 * profiles (if any) plus file extensions supported by the properties loaders.
	 * Locations are considered in the order specified, with later items taking precedence
	 * (like a map merge).
	 * @param locations the search locations
	 */
	public void setSearchLocations(String locations) {
		Assert.hasLength(locations, "Locations must not be empty");
		this.searchLocations = locations;
	}

	/**
	 * Sets the names of the files that should be loaded (excluding file extension) as a
	 * comma-separated list.
	 * @param names the names to load
	 */
	public void setSearchNames(String names) {
		Assert.hasLength(names, "Names must not be empty");
		this.names = names;
	}

	/**
	 * {@link BeanFactoryPostProcessor} to re-order our property sources below any
	 * {@code @PropertySource} items added by the {@link ConfigurationClassPostProcessor}.
	 */
	private static class PropertySourceOrderingPostProcessor implements BeanFactoryPostProcessor, Ordered {

		private ConfigurableApplicationContext context;

		PropertySourceOrderingPostProcessor(ConfigurableApplicationContext context) {
			this.context = context;
		}

		@Override
		public int getOrder() {
			return Ordered.HIGHEST_PRECEDENCE;
		}

		@Override
		public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
			reorderSources(this.context.getEnvironment());
		}

		private void reorderSources(ConfigurableEnvironment environment) {
			PropertySource<?> defaultProperties = environment.getPropertySources().remove(DEFAULT_PROPERTIES);
			if (defaultProperties != null) {
				environment.getPropertySources().addLast(defaultProperties);
			}
		}

	}

	/**
	 * Loads candidate property sources and configures the active profiles.
	 */
	private class Loader {

		private final Log logger = ConfigFileApplicationListener.this.logger;

		/**
		 * 环境对象
		 */
		private final ConfigurableEnvironment environment;
		/**
		 * 占位符处理器
		 */
		private final PropertySourcesPlaceholdersResolver placeholdersResolver;
		/**
		 * 资源加载器
		 */
		private final ResourceLoader resourceLoader;
		/**
		 * 属性的资源加载器
		 */
		private final List<PropertySourceLoader> propertySourceLoaders;
		/**
		 * 待加载的 Profile 队列
		 */
		private Deque<Profile> profiles;
		/**
		 * 已加载的 Profile 队列
		 */
		private List<Profile> processedProfiles;
		/**
		 * 是否有激活的 Profile
		 */
		private boolean activatedProfiles;
		/**
		 * 保存每个 Profile 对应的属性信息
		 */
		private Map<Profile, MutablePropertySources> loaded;

		private Map<DocumentsCacheKey, List<Document>> loadDocumentsCache = new HashMap<>();

		Loader(ConfigurableEnvironment environment, ResourceLoader resourceLoader) {
			this.environment = environment;
			// 占位符处理器
			this.placeholdersResolver = new PropertySourcesPlaceholdersResolver(this.environment);
			// 设置默认的资源加载器
			this.resourceLoader = (resourceLoader != null) ? resourceLoader : new DefaultResourceLoader();
			/**
			 * 通过 ClassLoader 从所有的 `META-INF/spring.factories` 文件中加载出 PropertySourceLoader
			 * Spring Boot 配置了两个属性资源加载器：
			 * {@link PropertiesPropertySourceLoader} 加载 `properties` 和 `xml` 文件
			 * {@link YamlPropertySourceLoader} 加载 `yml` 和 `yaml` 文件
			 */
			this.propertySourceLoaders = SpringFactoriesLoader.loadFactories(PropertySourceLoader.class,
					getClass().getClassLoader());
		}

		void load() {
			// 借助 FilteredPropertySource 执行入参中的这个 Consumer 函数
			// 目的就是获取 `defaultProperties` 默认值的 PropertySource，通常我们没有设置，所以为空对象
			FilteredPropertySource.apply(this.environment, DEFAULT_PROPERTIES, LOAD_FILTERED_PROPERTY,
					(defaultProperties) -> {
						this.profiles = new LinkedList<>();
						this.processedProfiles = new LinkedList<>();
						this.activatedProfiles = false;
						this.loaded = new LinkedHashMap<>();
						// <1> 初始化 Profile 对象，也就是我们需要加载的 Spring 配置，例如配置的 JVM 变量：`dev`、`sit`、`uat`、`prod`
						// 1. `java -jar xxx.jar --spring.profiles.active=dev` or `java -jar -Dspring.profiles.active=dev xxx.jar`，那么这里的 `profiles` 就会有一个 `null` 和一个 `dev`
						// 2. `java -jar xxx.jar`，那么这里的 `profiles` 就会有一个 `null` 和一个 `default`
						initializeProfiles();
						// <2> 依次加载 `profiles` 对应的配置信息
						// 这里先解析 `null` 对应的配置信息，也就是公共配置
						// 针对上面第 `2` 种情况，如果公共配置指定了 `spring.profiles.active`，那么添加至 `profiles` 中，并移除 `default` 默认 Profile
						// 所以后续和上面第 `1` 种情况一样的处理
						while (!this.profiles.isEmpty()) {
							// <2.1> 将接下来的准备加载的 Profile 从队列中移除
							Profile profile = this.profiles.poll();
							// <2.2> 如果不为 `null` 且不是默认的 Profile，这个方法名不试试取错了？？
							if (isDefaultProfile(profile)) {
								// 则将其添加至 Environment 的 `activeProfiles`（有效的配置）中，已存在不会添加
								addProfileToEnvironment(profile.getName());
							}
							/**
							 * <2.3> 尝试加载配置文件，并解析出配置信息，会根据 Profile 归类，最终保存至 {@link this#loaded} 集合
							 * 例如会去加载 `classpath:/application.yml` 或者 `classpath:/application-dev.yml` 文件，并解析
							 * 如果 `profile` 为 `null`，则会解析出 `classpath:/application.yml` 中的公共配置
							 * 因为这里是第一次去加载，所以不需要检查 `profile` 对应的配置信息是否存在
 							 */
							load(profile, this::getPositiveProfileFilter,
									addToLoaded(MutablePropertySources::addLast, false));
							// <2.4> 将已加载的 Profile 保存
							this.processedProfiles.add(profile);
						}
						/**
						 * <3> 如果没有指定 `profile`，那么这里尝试解析所有需要的环境的配置信息，也会根据 Profile 归类，最终保存至 {@link this#loaded} 集合
						 * 例如会去加载 `classpath:/application.yml` 文件并解析出各个 Profile 的配置信息
						 * 因为上面可能尝试加载过，所以这里需要检查 `profile` 对应的配置信息是否存在，已存在则不再添加
						 * 至于这一步的用途暂时还没搞懂~
						 */
						load(null, this::getNegativeProfileFilter, addToLoaded(MutablePropertySources::addFirst, true));
						/** <4> 将上面加载出来的所有配置信息从 {@link this#loaded} 集合添加至 Environment 中 */
						addLoadedPropertySources();
						// <5> 设置哪些 Profile 环境的配置需要被激活
						applyActiveProfiles(defaultProperties);
					});
		}

		/**
		 * Initialize profile information from both the {@link Environment} active
		 * profiles and any {@code spring.profiles.active}/{@code spring.profiles.include}
		 * properties that are already set.
		 */
		private void initializeProfiles() {
			// The default profile for these purposes is represented as null. We add it
			// first so that it is processed first and has lowest priority.
			// <1> 先添加一个空的 Profile
			this.profiles.add(null);
			// <2> 从 Environment 中获取 `spring.profiles.active` 配置
			// 此时还没有加载配置文件，所以这里获取到的就是你启动 `jar` 包时设置的 JVM 变量，例如 `-Dspring.profiles.active`
			// 或者启动 `jar` 包时添加的启动参数，例如 `--spring.profiles.active=dev`
			Set<Profile> activatedViaProperty = getProfilesFromProperty(ACTIVE_PROFILES_PROPERTY);
			// <3> 从 Environment 中获取 `spring.profiles.include` 配置
			Set<Profile> includedViaProperty = getProfilesFromProperty(INCLUDE_PROFILES_PROPERTY);
			// <4> 从 Environment 配置的需要激活的 Profile 们，不在上面两个范围内则属于其他
			List<Profile> otherActiveProfiles = getOtherActiveProfiles(activatedViaProperty, includedViaProperty);
			// <5> 将上面找到的所有 Profile 都添加至 `profiles` 中（通常我们只在上面的第 `2` 步可能有返回结果）
			this.profiles.addAll(otherActiveProfiles);
			// Any pre-existing active profiles set via property sources (e.g.
			// System properties) take precedence over those added in config files.
			this.profiles.addAll(includedViaProperty);
			// 这里主要设置 `activatedProfiles`，表示已有需要激活的 Profile 环境
			addActiveProfiles(activatedViaProperty);
			// <6> 如果只有一个 Profile，也就是第 `1` 步添加的一个空对象，那么这里再创建一个默认的
			if (this.profiles.size() == 1) { // only has null profile
				for (String defaultProfileName : this.environment.getDefaultProfiles()) {
					Profile defaultProfile = new Profile(defaultProfileName, true);
					this.profiles.add(defaultProfile);
				}
			}
		}

		private Set<Profile> getProfilesFromProperty(String profilesProperty) {
			if (!this.environment.containsProperty(profilesProperty)) {
				return Collections.emptySet();
			}
			Binder binder = Binder.get(this.environment);
			Set<Profile> profiles = getProfiles(binder, profilesProperty);
			return new LinkedHashSet<>(profiles);
		}

		private List<Profile> getOtherActiveProfiles(Set<Profile> activatedViaProperty,
				Set<Profile> includedViaProperty) {
			return Arrays.stream(this.environment.getActiveProfiles()).map(Profile::new).filter(
					(profile) -> !activatedViaProperty.contains(profile) && !includedViaProperty.contains(profile))
					.collect(Collectors.toList());
		}

		void addActiveProfiles(Set<Profile> profiles) {
			if (profiles.isEmpty()) {
				return;
			}
			if (this.activatedProfiles) {
				if (this.logger.isDebugEnabled()) {
					this.logger.debug("Profiles already activated, '" + profiles + "' will not be applied");
				}
				return;
			}
			this.profiles.addAll(profiles);
			if (this.logger.isDebugEnabled()) {
				this.logger.debug("Activated activeProfiles " + StringUtils.collectionToCommaDelimitedString(profiles));
			}
			this.activatedProfiles = true;
			removeUnprocessedDefaultProfiles();
		}

		private void removeUnprocessedDefaultProfiles() {
			this.profiles.removeIf((profile) -> (profile != null && profile.isDefaultProfile()));
		}

		private DocumentFilter getPositiveProfileFilter(Profile profile) {
			return (Document document) -> {
				// 如果没有指定 Profile，那么 Document 中的 `profiles` 也得为空
				// 也就是不能有 `spring.profiles` 配置，就是公共配置咯
				if (profile == null) {
					return ObjectUtils.isEmpty(document.getProfiles());
				}
				// 如果指定了 Profile，那么 Document 中的 `profiles` 需要包含这个 Profile
				// 同时，Environment 中也要接受这个 Document 中的 `profiles`
				return ObjectUtils.containsElement(document.getProfiles(), profile.getName())
						&& this.environment.acceptsProfiles(Profiles.of(document.getProfiles()));
			};
		}

		private DocumentFilter getNegativeProfileFilter(Profile profile) {
			return (Document document) -> (profile == null // Profile 为空
					&& !ObjectUtils.isEmpty(document.getProfiles()) // Document 中的 `profile` 不为空
					&& this.environment.acceptsProfiles(Profiles.of(document.getProfiles()))); // Environment 接受这个 Document 中的 `profiles`
		}

		private DocumentConsumer addToLoaded(BiConsumer<MutablePropertySources, PropertySource<?>> addMethod,
				boolean checkForExisting) {
			return (profile, document) -> {
				// 如果需要检查是否存在，存在的话直接返回
				if (checkForExisting) {
					for (MutablePropertySources merged : this.loaded.values()) {
						if (merged.contains(document.getPropertySource().getName())) {
							return;
						}
					}
				}
				// 获取 `loaded` 中该 Profile 对应的 MutablePropertySources 对象
				MutablePropertySources merged = this.loaded.computeIfAbsent(profile,
						(k) -> new MutablePropertySources());
				// 往这个 MutablePropertySources 对象中添加 Document 对应的 PropertySource
				addMethod.accept(merged, document.getPropertySource());
			};
		}

		private void load(Profile profile, DocumentFilterFactory filterFactory, DocumentConsumer consumer) {
			// <1> 先获取 `classpath:/`、`classpath:/config/`、`file:./`、`file:./config/` 四个路径
			// <2> 然后依次遍历，从该路径下找到对应的配置文件，找到了则通过 `consumer` 进行解析，并添加至 `loaded` 中
			getSearchLocations().forEach((location) -> {
				// <2.1> 判断是否是文件夹，这里好像都是
				boolean isFolder = location.endsWith("/");
				// <2.2> 是文件夹的话找到应用配置文件的名称，可以通过 `spring.config.name` 配置进行设置
				// Spring Cloud 中默认为 `bootstrap`，Spring Boot 中默认为 `application`
				Set<String> names = isFolder ? getSearchNames() : NO_SEARCH_NAMES;
				// <2.3> 那么这里开始解析 `application` 配置文件了
				names.forEach((name) -> load(location, name, profile, filterFactory, consumer));
			});
		}

		private void load(String location, String name, Profile profile, DocumentFilterFactory filterFactory,
				DocumentConsumer consumer) {
			// <1> 如果没有应用的配置文件名称，则尝试根据 `location` 进行解析，暂时忽略
			if (!StringUtils.hasText(name)) {
				for (PropertySourceLoader loader : this.propertySourceLoaders) {
					if (canLoadFileExtension(loader, location)) {
						load(loader, location, profile, filterFactory.getDocumentFilter(profile), consumer);
						return;
					}
				}
				throw new IllegalStateException("File extension of config file location '" + location
						+ "' is not known to any PropertySourceLoader. If the location is meant to reference "
						+ "a directory, it must end in '/'");
			}
			Set<String> processed = new HashSet<>();
			/**
			 * <2> 遍历 PropertySourceLoader 对配置文件进行加载，这里有以下两个：
			 * {@link PropertiesPropertySourceLoader} 加载 `properties` 和 `xml` 文件
			 * {@link YamlPropertySourceLoader} 加载 `yml` 和 `yaml` 文件
 			 */
			for (PropertySourceLoader loader : this.propertySourceLoaders) {
				// 先获取 `loader` 的后缀，也就是说这里会总共会遍历 4 次，分别处理不同后缀的文件
				// 加上前面 4 种 `location`（文件夹），这里会进行 16 次加载
				for (String fileExtension : loader.getFileExtensions()) {
					// 避免重复加载
					if (processed.add(fileExtension)) {
						// 例如尝试加载 `classpath:/application.yml` 文件
						loadForFileExtension(loader, location + name, "." + fileExtension, profile, filterFactory,
								consumer);
					}
				}
			}
		}

		private boolean canLoadFileExtension(PropertySourceLoader loader, String name) {
			return Arrays.stream(loader.getFileExtensions())
					.anyMatch((fileExtension) -> StringUtils.endsWithIgnoreCase(name, fileExtension));
		}

		private void loadForFileExtension(PropertySourceLoader loader, String prefix, String fileExtension,
				Profile profile, DocumentFilterFactory filterFactory, DocumentConsumer consumer) {
			// <1> 创建一个默认的 DocumentFilter 过滤器 `defaultFilter`
			DocumentFilter defaultFilter = filterFactory.getDocumentFilter(null);
			// <2> 创建一个指定 Profile 的 DocumentFilter 过滤器 `profileFilter`
			DocumentFilter profileFilter = filterFactory.getDocumentFilter(profile);
			// <3> 如果传入了 `profile`，那么尝试加载 `application-${profile}.yml`对应的配置文件
			if (profile != null) {
				// Try profile-specific file & profile section in profile file (gh-340)
				// <3.1> 获取 `profile` 对应的名称，例如 `application-dev.yml`
				String profileSpecificFile = prefix + "-" + profile + fileExtension;
				// <3.2> 尝试对该文件进行加载，公共配置
				load(loader, profileSpecificFile, profile, defaultFilter, consumer);
				// <3.3> 尝试对该文件进行加载，环境对应的配置
				load(loader, profileSpecificFile, profile, profileFilter, consumer);
				// Try profile specific sections in files we've already processed
				// <3.4> 也尝试从该文件中加载已经加载过的环境所对应的配置
				for (Profile processedProfile : this.processedProfiles) {
					if (processedProfile != null) {
						String previouslyLoaded = prefix + "-" + processedProfile + fileExtension;
						load(loader, previouslyLoaded, profile, profileFilter, consumer);
					}
				}
			}
			// Also try the profile-specific section (if any) of the normal file
			// <4> 正常逻辑，这里尝试加载 `application.yml` 文件中对应 Profile 环境的配置
			// 当然，如果 Profile 为空也就加载公共配置
			load(loader, prefix + fileExtension, profile, profileFilter, consumer);
		}

		private void load(PropertySourceLoader loader, String location, Profile profile, DocumentFilter filter,
				DocumentConsumer consumer) {
			try {
				// <1> 通过资源加载器获取这个文件资源
				Resource resource = this.resourceLoader.getResource(location);
				// <2> 如果文件资源不存在，那直接返回了
				if (resource == null || !resource.exists()) {
					if (this.logger.isTraceEnabled()) {
						StringBuilder description = getDescription("Skipped missing config ", location, resource,
								profile);
						this.logger.trace(description);
					}
					return;
				}
				// <3> 否则，如果文件资源的后缀为空，跳过，直接返回
				if (!StringUtils.hasText(StringUtils.getFilenameExtension(resource.getFilename()))) {
					if (this.logger.isTraceEnabled()) {
						StringBuilder description = getDescription("Skipped empty config extension ", location,
								resource, profile);
						this.logger.trace(description);
					}
					return;
				}
				String name = "applicationConfig: [" + location + "]";
				// <4> 使用 PropertySourceLoader 加载器加载出该文件资源中的所有属性，并将其封装成 Document 对象
				// Document 对象中包含了配置文件的 `spring.profiles` 和 `spring.profiles.active` 属性
				// 一个文件不是对应一个 Document，因为在一个 `yml` 文件可以通过 `---` 来配置多个环境的配置，这里也就会有多个 Document
				List<Document> documents = loadDocuments(loader, name, resource);
				// <5> 如果没有解析出 Document，表明该文件资源无效，跳过，直接返回
				if (CollectionUtils.isEmpty(documents)) {
					if (this.logger.isTraceEnabled()) {
						StringBuilder description = getDescription("Skipped unloaded config ", location, resource,
								profile);
						this.logger.trace(description);
					}
					return;
				}
				List<Document> loaded = new ArrayList<>();
				// <6> 通过 DocumentFilter 对 `document` 进行过滤，过滤出想要的 Profile 对应的 Document
				// 例如入参的 Profile 为 `dev` 那么这里只要 `dev` 对应 Document
				// 如果 Profile 为空，那么找到没有 `spring.profiles` 配置 Document，也就是我们的公共配置
				for (Document document : documents) {
					if (filter.match(document)) {
						// 如果前面还没有激活的 Profile
						// 那么这里尝试将 Document 中的 `spring.profiles.active` 添加至 `profiles` 中，同时删除 `default` 默认的 Profile
						addActiveProfiles(document.getActiveProfiles());
						addIncludedProfiles(document.getIncludeProfiles());
						loaded.add(document);
					}
				}
				// <7> 将需要的 Document 们进行倒序，因为配置在后面优先级越高，所以需要反转一下
				Collections.reverse(loaded);
				// <8> 如果有需要的 Document
				if (!loaded.isEmpty()) {
					/**
					 * 借助 Lambda 表达式调用 {@link #addToLoaded} 方法
					 * 将这些 Document 转换成 MutablePropertySources 保存至 {@link this#loaded} 集合中
					 */
					loaded.forEach((document) -> consumer.accept(profile, document));
					if (this.logger.isDebugEnabled()) {
						StringBuilder description = getDescription("Loaded config file ", location, resource, profile);
						this.logger.debug(description);
					}
				}
			}
			catch (Exception ex) {
				throw new IllegalStateException("Failed to load property source from location '" + location + "'", ex);
			}
		}

		private void addIncludedProfiles(Set<Profile> includeProfiles) {
			LinkedList<Profile> existingProfiles = new LinkedList<>(this.profiles);
			this.profiles.clear();
			this.profiles.addAll(includeProfiles);
			this.profiles.removeAll(this.processedProfiles);
			this.profiles.addAll(existingProfiles);
		}

		private List<Document> loadDocuments(PropertySourceLoader loader, String name, Resource resource)
				throws IOException {
			DocumentsCacheKey cacheKey = new DocumentsCacheKey(loader, resource);
			// 尝试从缓存中获取
			List<Document> documents = this.loadDocumentsCache.get(cacheKey);
			if (documents == null) {
				// 使用 PropertySourceLoader 加载器进行加载
				List<PropertySource<?>> loaded = loader.load(name, resource);
				// 将 PropertySource 转换成 Document
				documents = asDocuments(loaded);
				// 放入缓存
				this.loadDocumentsCache.put(cacheKey, documents);
			}
			return documents;
		}

		private List<Document> asDocuments(List<PropertySource<?>> loaded) {
			if (loaded == null) {
				return Collections.emptyList();
			}
			// 遍历 PropertySource 们，依次解析他们出他们里面的几个配置，然后全部封装到 Document 对象中
			// 返回返回这些 Document 对象们
			return loaded.stream().map((propertySource) -> {
				Binder binder = new Binder(ConfigurationPropertySources.from(propertySource),
						this.placeholdersResolver);
				return new Document(propertySource, // 源 PropertySource 对象
						// 找到 `spring.profiles` 属性值
						binder.bind("spring.profiles", STRING_ARRAY).orElse(null),
						// 找到 `spring.profiles.active` 属性值
						getProfiles(binder, ACTIVE_PROFILES_PROPERTY),
						// 找到 `spring.profiles.include` 属性值
						getProfiles(binder, INCLUDE_PROFILES_PROPERTY));
			}).collect(Collectors.toList());
		}

		private StringBuilder getDescription(String prefix, String location, Resource resource, Profile profile) {
			StringBuilder result = new StringBuilder(prefix);
			try {
				if (resource != null) {
					String uri = resource.getURI().toASCIIString();
					result.append("'");
					result.append(uri);
					result.append("' (");
					result.append(location);
					result.append(")");
				}
			}
			catch (IOException ex) {
				result.append(location);
			}
			if (profile != null) {
				result.append(" for profile ");
				result.append(profile);
			}
			return result;
		}

		private Set<Profile> getProfiles(Binder binder, String name) {
			return binder.bind(name, STRING_ARRAY).map(this::asProfileSet).orElse(Collections.emptySet());
		}

		private Set<Profile> asProfileSet(String[] profileNames) {
			List<Profile> profiles = new ArrayList<>();
			for (String profileName : profileNames) {
				profiles.add(new Profile(profileName));
			}
			return new LinkedHashSet<>(profiles);
		}

		private void addProfileToEnvironment(String profile) {
			for (String activeProfile : this.environment.getActiveProfiles()) {
				if (activeProfile.equals(profile)) {
					return;
				}
			}
			this.environment.addActiveProfile(profile);
		}

		private Set<String> getSearchLocations() {
			Set<String> locations = getSearchLocations(CONFIG_ADDITIONAL_LOCATION_PROPERTY);
			if (this.environment.containsProperty(CONFIG_LOCATION_PROPERTY)) {
				locations.addAll(getSearchLocations(CONFIG_LOCATION_PROPERTY));
			}
			else {
				// 这里会得到 `classpath:/`、`classpath:/config/`、`file:./`、`file:./config/` 四个路径
				locations.addAll(asResolvedSet(ConfigFileApplicationListener.this.searchLocations, DEFAULT_SEARCH_LOCATIONS));
			}
			return locations;
		}

		private Set<String> getSearchLocations(String propertyName) {
			Set<String> locations = new LinkedHashSet<>();
			if (this.environment.containsProperty(propertyName)) {
				for (String path : asResolvedSet(this.environment.getProperty(propertyName), null)) {
					if (!path.contains("$")) {
						path = StringUtils.cleanPath(path);
						Assert.state(!path.startsWith(ResourcePatternResolver.CLASSPATH_ALL_URL_PREFIX),
								"Classpath wildard patterns cannot be used as a search location");
						if (!ResourceUtils.isUrl(path)) {
							path = ResourceUtils.FILE_URL_PREFIX + path;
						}
					}
					locations.add(path);
				}
			}
			return locations;
		}

		private Set<String> getSearchNames() {
			// 如果通过 `spring.config.name` 指定了配置文件名称
			if (this.environment.containsProperty(CONFIG_NAME_PROPERTY)) {
				String property = this.environment.getProperty(CONFIG_NAME_PROPERTY);
				// 进行占位符处理，并返回设置的配置文件名称
				return asResolvedSet(property, null);
			}
			// 如果指定了 `names` 配置文件的名称，则对其进行处理（占位符）
			// 没有指定的话则去 `application` 默认名称
			return asResolvedSet(ConfigFileApplicationListener.this.names, DEFAULT_NAMES);
		}

		private Set<String> asResolvedSet(String value, String fallback) {
			List<String> list = Arrays.asList(StringUtils.trimArrayElements(StringUtils.commaDelimitedListToStringArray(
					(value != null) ? this.environment.resolvePlaceholders(value) : fallback)));
			Collections.reverse(list);
			return new LinkedHashSet<>(list);
		}

		private void addLoadedPropertySources() {
			// 获取当前 Spring 应用的 Environment 环境中的配置信息
			MutablePropertySources destination = this.environment.getPropertySources();
			// 将上面已加载的每个 Profile 对应的属性信息放入一个 List 集合中 `loaded`
			List<MutablePropertySources> loaded = new ArrayList<>(this.loaded.values());
			// 将 `loaded` 进行翻转，因为写在后面的环境优先级更好
			Collections.reverse(loaded);
			String lastAdded = null;
			Set<String> added = new HashSet<>();
			// 遍历 `loaded`，将每个 Profile 对应的属性信息按序添加到 Environment 环境中
			for (MutablePropertySources sources : loaded) {
				for (PropertySource<?> source : sources) {
					if (added.add(source.getName())) {
						// 放入上一个 PropertySource 的后面，优先默认配置
						addLoadedPropertySource(destination, lastAdded, source);
						lastAdded = source.getName();
					}
				}
			}
		}

		private void addLoadedPropertySource(MutablePropertySources destination, String lastAdded,
				PropertySource<?> source) {
			if (lastAdded == null) {
				if (destination.contains(DEFAULT_PROPERTIES)) {
					destination.addBefore(DEFAULT_PROPERTIES, source);
				}
				else {
					destination.addLast(source);
				}
			}
			else {
				destination.addAfter(lastAdded, source);
			}
		}

		private void applyActiveProfiles(PropertySource<?> defaultProperties) {
			List<String> activeProfiles = new ArrayList<>();
			// 如果默认的配置信息不为空，通常为 `null`
			if (defaultProperties != null) {
				Binder binder = new Binder(ConfigurationPropertySources.from(defaultProperties),
						new PropertySourcesPlaceholdersResolver(this.environment));
				activeProfiles.addAll(getDefaultProfiles(binder, "spring.profiles.include"));
				if (!this.activatedProfiles) {
					activeProfiles.addAll(getDefaultProfiles(binder, "spring.profiles.active"));
				}
			}
			// 遍历已加载的 Profile 对象，如果它不为 `null` 且不是默认的，那么添加到需要 `activeProfiles` 激活的队列中
			this.processedProfiles.stream().filter(this::isDefaultProfile).map(Profile::getName)
					.forEach(activeProfiles::add);
			// 设置 Environment 需要激活的环境名称
			this.environment.setActiveProfiles(activeProfiles.toArray(new String[0]));
		}

		private boolean isDefaultProfile(Profile profile) {
			return profile != null && !profile.isDefaultProfile();
		}

		private List<String> getDefaultProfiles(Binder binder, String property) {
			return binder.bind(property, STRING_LIST).orElse(Collections.emptyList());
		}

	}

	/**
	 * A Spring Profile that can be loaded.
	 */
	private static class Profile {

		private final String name;

		private final boolean defaultProfile;

		Profile(String name) {
			this(name, false);
		}

		Profile(String name, boolean defaultProfile) {
			Assert.notNull(name, "Name must not be null");
			this.name = name;
			this.defaultProfile = defaultProfile;
		}

		String getName() {
			return this.name;
		}

		boolean isDefaultProfile() {
			return this.defaultProfile;
		}

		@Override
		public boolean equals(Object obj) {
			if (obj == this) {
				return true;
			}
			if (obj == null || obj.getClass() != getClass()) {
				return false;
			}
			return ((Profile) obj).name.equals(this.name);
		}

		@Override
		public int hashCode() {
			return this.name.hashCode();
		}

		@Override
		public String toString() {
			return this.name;
		}

	}

	/**
	 * Cache key used to save loading the same document multiple times.
	 */
	private static class DocumentsCacheKey {

		private final PropertySourceLoader loader;

		private final Resource resource;

		DocumentsCacheKey(PropertySourceLoader loader, Resource resource) {
			this.loader = loader;
			this.resource = resource;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (obj == null || getClass() != obj.getClass()) {
				return false;
			}
			DocumentsCacheKey other = (DocumentsCacheKey) obj;
			return this.loader.equals(other.loader) && this.resource.equals(other.resource);
		}

		@Override
		public int hashCode() {
			return this.loader.hashCode() * 31 + this.resource.hashCode();
		}

	}

	/**
	 * A single document loaded by a {@link PropertySourceLoader}.
	 */
	private static class Document {

		private final PropertySource<?> propertySource;

		private String[] profiles;

		private final Set<Profile> activeProfiles;

		private final Set<Profile> includeProfiles;

		Document(PropertySource<?> propertySource, String[] profiles, Set<Profile> activeProfiles,
				Set<Profile> includeProfiles) {
			this.propertySource = propertySource;
			this.profiles = profiles;
			this.activeProfiles = activeProfiles;
			this.includeProfiles = includeProfiles;
		}

		PropertySource<?> getPropertySource() {
			return this.propertySource;
		}

		String[] getProfiles() {
			return this.profiles;
		}

		Set<Profile> getActiveProfiles() {
			return this.activeProfiles;
		}

		Set<Profile> getIncludeProfiles() {
			return this.includeProfiles;
		}

		@Override
		public String toString() {
			return this.propertySource.toString();
		}

	}

	/**
	 * Factory used to create a {@link DocumentFilter}.
	 */
	@FunctionalInterface
	private interface DocumentFilterFactory {

		/**
		 * Create a filter for the given profile.
		 * @param profile the profile or {@code null}
		 * @return the filter
		 */
		DocumentFilter getDocumentFilter(Profile profile);

	}

	/**
	 * Filter used to restrict when a {@link Document} is loaded.
	 */
	@FunctionalInterface
	private interface DocumentFilter {

		boolean match(Document document);

	}

	/**
	 * Consumer used to handle a loaded {@link Document}.
	 */
	@FunctionalInterface
	private interface DocumentConsumer {

		void accept(Profile profile, Document document);

	}

}
