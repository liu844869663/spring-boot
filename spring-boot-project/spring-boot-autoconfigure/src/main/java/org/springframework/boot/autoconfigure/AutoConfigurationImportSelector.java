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

package org.springframework.boot.autoconfigure;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.*;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigureprocessor.AutoConfigureAnnotationProcessor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DeferredImportSelector;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * {@link DeferredImportSelector} to handle {@link EnableAutoConfiguration
 * auto-configuration}. This class can also be subclassed if a custom variant of
 * {@link EnableAutoConfiguration @EnableAutoConfiguration} is needed.
 *
 * @author Phillip Webb
 * @author Andy Wilkinson
 * @author Stephane Nicoll
 * @author Madhura Bhave
 * @since 1.3.0
 * @see EnableAutoConfiguration
 */
public class AutoConfigurationImportSelector implements DeferredImportSelector, BeanClassLoaderAware,
		ResourceLoaderAware, BeanFactoryAware, EnvironmentAware, Ordered {

	private static final AutoConfigurationEntry EMPTY_ENTRY = new AutoConfigurationEntry();

	private static final String[] NO_IMPORTS = {};

	private static final Log logger = LogFactory.getLog(AutoConfigurationImportSelector.class);

	private static final String PROPERTY_NAME_AUTOCONFIGURE_EXCLUDE = "spring.autoconfigure.exclude";

	/**
	 * 底层 IoC 容器
	 */
	private ConfigurableListableBeanFactory beanFactory;
	/**
	 * 应用环境
	 */
	private Environment environment;
	/**
	 * 类加载器
	 */
	private ClassLoader beanClassLoader;
	/**
	 * 资源加载器
	 */
	private ResourceLoader resourceLoader;

	/**
	 * 返回需要注入的 Bean 的类名称，那么 Spring 后续就会根据条件加载这些的 Bean
	 * 至于这个方法在哪被调用的，返回的类名称为什么会被加载到 IoC 容器中，
	 * 可以参考我的(死磕Spring之IoC篇 - @Bean 等注解的实现原理)[https://www.cnblogs.com/lifullmoon/p/14461712.html]
	 *
	 * @param annotationMetadata 注解的元数据
	 * @return Bean 的类名称
	 */
	@Override
	public String[] selectImports(AnnotationMetadata annotationMetadata) {
		// <1> 如果通过 `spring.boot.enableautoconfiguration` 配置关闭了自动配置功能
		if (!isEnabled(annotationMetadata)) {
			// 返回一个空数组
			return NO_IMPORTS;
		}
		/**
		 * <2> 解析 `META-INF/spring-autoconfigure-metadata.properties` 文件，生成一个 AutoConfigurationMetadata 自动配置类元数据对象
		 *
		 * 说明：引入 `spring-boot-autoconfigure-processor` 工具模块依赖后，其中会通过 Java SPI 机制引入 {@link AutoConfigureAnnotationProcessor} 注解处理器在编译阶段进行相关处理
		 * 其中 `spring-boot-autoconfigure` 模块会引入该工具模块（不具有传递性），那么 Spring Boot 在编译 `spring-boot-autoconfigure` 这个 `jar` 包的时候，
		 * 在编译阶段会扫描到带有 `@ConditionalOnClass` 等注解的 `.class` 文件，也就是自动配置类，将自动配置类的信息保存至 `META-INF/spring-autoconfigure-metadata.properties` 文件中
		 * 例如保存类 `自动配置类类名.注解简称` => `注解中的值(逗号分隔)` 和 `自动配置类类名` => `空字符串`
		 *
		 * 当然，你自己写的 Spring Boot Starter 中的自动配置模块也可以引入这个 Spring Boot 提供的插件
		 */
		AutoConfigurationMetadata autoConfigurationMetadata = AutoConfigurationMetadataLoader.loadMetadata(this.beanClassLoader);
		// <3> 从所有的 `META-INF/spring.factories` 文件中找到 `@EnableAutoConfiguration` 注解对应的类（需要自动配置的类）
		// 会进行过滤处理，然后封装在一个对象中
		AutoConfigurationEntry autoConfigurationEntry = getAutoConfigurationEntry(autoConfigurationMetadata, annotationMetadata);
		// <4> 返回所有需要自动配置的类
		return StringUtils.toStringArray(autoConfigurationEntry.getConfigurations());
	}

	/**
	 * Return the {@link AutoConfigurationEntry} based on the {@link AnnotationMetadata}
	 * of the importing {@link Configuration @Configuration} class.
	 * @param autoConfigurationMetadata the auto-configuration metadata
	 * @param annotationMetadata the annotation metadata of the configuration class
	 * @return the auto-configurations that should be imported
	 */
	protected AutoConfigurationEntry getAutoConfigurationEntry(AutoConfigurationMetadata autoConfigurationMetadata,
			AnnotationMetadata annotationMetadata) {
		// <1> 如果通过 `spring.boot.enableautoconfiguration` 配置关闭了自动配置功能
		if (!isEnabled(annotationMetadata)) {
			// 则返回一个“空”的对象
			return EMPTY_ENTRY;
		}
		// <2> 获取 `@EnableAutoConfiguration` 注解的配置信息
		AnnotationAttributes attributes = getAttributes(annotationMetadata);
		// <3> 从所有的 `META-INF/spring.factories` 文件中找到 `@EnableAutoConfiguration` 注解对应的类（需要自动配置的类）
		List<String> configurations = getCandidateConfigurations(annotationMetadata, attributes);
		// <4> 对所有的自动配置类进行去重
		configurations = removeDuplicates(configurations);
		// <5> 获取需要排除的自动配置类
		// 可通过 `@EnableAutoConfiguration` 注解的 `exclude` 和 `excludeName` 配置
		// 也可以通过 `spring.autoconfigure.exclude` 配置
		Set<String> exclusions = getExclusions(annotationMetadata, attributes);
		// <6> 处理 `exclusions` 中特殊的类名称，保证能够排除它
		checkExcludedClasses(configurations, exclusions);
		// <7> 从 `configurations` 中将 `exclusions` 需要排除的自动配置类移除
		configurations.removeAll(exclusions);
		/**
		 * <8> 从 `META-INF/spring.factories` 找到所有的 {@link AutoConfigurationImportFilter} 对 `configurations` 进行过滤处理
		 * 例如 Spring Boot 中配置了 {@link org.springframework.boot.autoconfigure.condition.OnClassCondition}
		 * 在这里提前过滤掉一些不满足条件的自动配置类，在 Spring 注入 Bean 的时候也会判断哦~
 		 */
		configurations = filter(configurations, autoConfigurationMetadata);
		/**
		 * <9> 从 `META-INF/spring.factories` 找到所有的 {@link AutoConfigurationImportListener} 事件监听器
		 * 触发每个监听器去处理 {@link AutoConfigurationImportEvent} 事件，该事件中包含了 `configurations` 和 `exclusions`
		 * Spring Boot 中配置了一个 {@link org.springframework.boot.autoconfigure.condition.ConditionEvaluationReportAutoConfigurationImportListener}
		 * 目的就是将 `configurations` 和 `exclusions` 保存至 {@link AutoConfigurationImportEvent} 对象中，并注册到 IoC 容器中，名称为 `autoConfigurationReport`
		 * 这样一来我们可以注入这个 Bean 获取到自动配置类信息
		 */
		fireAutoConfigurationImportEvents(configurations, exclusions);
		// <10> 将所有的自动配置类封装成一个 AutoConfigurationEntry 对象，并返回
		return new AutoConfigurationEntry(configurations, exclusions);
	}

	@Override
	public Class<? extends Group> getImportGroup() {
		return AutoConfigurationGroup.class;
	}

	protected boolean isEnabled(AnnotationMetadata metadata) {
		if (getClass() == AutoConfigurationImportSelector.class) {
			return getEnvironment().getProperty(EnableAutoConfiguration.ENABLED_OVERRIDE_PROPERTY, Boolean.class, true);
		}
		return true;
	}

	/**
	 * Return the appropriate {@link AnnotationAttributes} from the
	 * {@link AnnotationMetadata}. By default this method will return attributes for
	 * {@link #getAnnotationClass()}.
	 * @param metadata the annotation metadata
	 * @return annotation attributes
	 */
	protected AnnotationAttributes getAttributes(AnnotationMetadata metadata) {
		// 获取 `@EnableAutoConfiguration` 注解的名称
		String name = getAnnotationClass().getName();
		// 从注解元数据里面获取 `@EnableAutoConfiguration` 注解的配置
		AnnotationAttributes attributes = AnnotationAttributes.fromMap(metadata.getAnnotationAttributes(name, true));
		Assert.notNull(attributes, () -> "No auto-configuration attributes found. Is " + metadata.getClassName()
				+ " annotated with " + ClassUtils.getShortName(name) + "?");
		return attributes;
	}

	/**
	 * Return the source annotation class used by the selector.
	 * @return the annotation class
	 */
	protected Class<?> getAnnotationClass() {
		return EnableAutoConfiguration.class;
	}

	/**
	 * Return the auto-configuration class names that should be considered. By default
	 * this method will load candidates using {@link SpringFactoriesLoader} with
	 * {@link #getSpringFactoriesLoaderFactoryClass()}.
	 * @param metadata the source metadata
	 * @param attributes the {@link #getAttributes(AnnotationMetadata) annotation
	 * attributes}
	 * @return a list of candidate configurations
	 */
	protected List<String> getCandidateConfigurations(AnnotationMetadata metadata, AnnotationAttributes attributes) {
		// 从所有的 `META-INF/spring.factories` 文件中找到 `@EnableAutoConfiguration` 注解对应的类（需要自动配置的类）
		List<String> configurations = SpringFactoriesLoader.loadFactoryNames(getSpringFactoriesLoaderFactoryClass(), getBeanClassLoader());
		// 如果为空则抛出异常
		Assert.notEmpty(configurations, "No auto configuration classes found in META-INF/spring.factories. If you "
				+ "are using a custom packaging, make sure that file is correct.");
		return configurations;
	}

	/**
	 * Return the class used by {@link SpringFactoriesLoader} to load configuration
	 * candidates.
	 * @return the factory class
	 */
	protected Class<?> getSpringFactoriesLoaderFactoryClass() {
		return EnableAutoConfiguration.class;
	}

	private void checkExcludedClasses(List<String> configurations, Set<String> exclusions) {
		List<String> invalidExcludes = new ArrayList<>(exclusions.size());
		for (String exclusion : exclusions) {
			if (ClassUtils.isPresent(exclusion, getClass().getClassLoader()) && !configurations.contains(exclusion)) {
				invalidExcludes.add(exclusion);
			}
		}
		if (!invalidExcludes.isEmpty()) {
			handleInvalidExcludes(invalidExcludes);
		}
	}

	/**
	 * Handle any invalid excludes that have been specified.
	 * @param invalidExcludes the list of invalid excludes (will always have at least one
	 * element)
	 */
	protected void handleInvalidExcludes(List<String> invalidExcludes) {
		StringBuilder message = new StringBuilder();
		for (String exclude : invalidExcludes) {
			message.append("\t- ").append(exclude).append(String.format("%n"));
		}
		throw new IllegalStateException(String.format(
				"The following classes could not be excluded because they are not auto-configuration classes:%n%s",
				message));
	}

	/**
	 * Return any exclusions that limit the candidate configurations.
	 * @param metadata the source metadata
	 * @param attributes the {@link #getAttributes(AnnotationMetadata) annotation
	 * attributes}
	 * @return exclusions or an empty set
	 */
	protected Set<String> getExclusions(AnnotationMetadata metadata, AnnotationAttributes attributes) {
		Set<String> excluded = new LinkedHashSet<>();
		excluded.addAll(asList(attributes, "exclude"));
		excluded.addAll(Arrays.asList(attributes.getStringArray("excludeName")));
		excluded.addAll(getExcludeAutoConfigurationsProperty());
		return excluded;
	}

	private List<String> getExcludeAutoConfigurationsProperty() {
		if (getEnvironment() instanceof ConfigurableEnvironment) {
			Binder binder = Binder.get(getEnvironment());
			return binder.bind(PROPERTY_NAME_AUTOCONFIGURE_EXCLUDE, String[].class).map(Arrays::asList)
					.orElse(Collections.emptyList());
		}
		String[] excludes = getEnvironment().getProperty(PROPERTY_NAME_AUTOCONFIGURE_EXCLUDE, String[].class);
		return (excludes != null) ? Arrays.asList(excludes) : Collections.emptyList();
	}

	private List<String> filter(List<String> configurations, AutoConfigurationMetadata autoConfigurationMetadata) {
		long startTime = System.nanoTime();
		// <1> 将自动配置类保存至 `candidates` 数组中
		String[] candidates = StringUtils.toStringArray(configurations);
		boolean[] skip = new boolean[candidates.length];
		boolean skipped = false;
		/*
		 * <2> 从 `META-INF/spring.factories` 找到所有的 AutoConfigurationImportFilter 对 `candidates` 进行过滤处理
		 * 有 OnClassCondition、OnBeanCondition、OnWebApplicationCondition
		 */
		for (AutoConfigurationImportFilter filter : getAutoConfigurationImportFilters()) {
			// <2.1> Aware 回调
			invokeAwareMethods(filter);
			// <2.2> 对 `candidates` 进行匹配处理，获取所有的匹配结果
			boolean[] match = filter.match(candidates, autoConfigurationMetadata);
			// <2.3> 遍历匹配结果，将不匹配的自动配置类至空
			for (int i = 0; i < match.length; i++) {
				if (!match[i]) {
					skip[i] = true;
					candidates[i] = null;
					skipped = true;
				}
			}
		}
		// <3> 如果没有不匹配的结果则全部返回
		if (!skipped) {
			return configurations;
		}
		// <4> 获取到所有匹配的自动配置类，并返回
		List<String> result = new ArrayList<>(candidates.length);
		for (int i = 0; i < candidates.length; i++) {
			if (!skip[i]) {
				result.add(candidates[i]);
			}
		}
		if (logger.isTraceEnabled()) {
			int numberFiltered = configurations.size() - result.size();
			logger.trace("Filtered " + numberFiltered + " auto configuration class in "
					+ TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime) + " ms");
		}
		return new ArrayList<>(result);
	}

	protected List<AutoConfigurationImportFilter> getAutoConfigurationImportFilters() {
		return SpringFactoriesLoader.loadFactories(AutoConfigurationImportFilter.class, this.beanClassLoader);
	}

	protected final <T> List<T> removeDuplicates(List<T> list) {
		return new ArrayList<>(new LinkedHashSet<>(list));
	}

	protected final List<String> asList(AnnotationAttributes attributes, String name) {
		String[] value = attributes.getStringArray(name);
		return Arrays.asList(value);
	}

	private void fireAutoConfigurationImportEvents(List<String> configurations, Set<String> exclusions) {
		List<AutoConfigurationImportListener> listeners = getAutoConfigurationImportListeners();
		if (!listeners.isEmpty()) {
			AutoConfigurationImportEvent event = new AutoConfigurationImportEvent(this, configurations, exclusions);
			for (AutoConfigurationImportListener listener : listeners) {
				invokeAwareMethods(listener);
				listener.onAutoConfigurationImportEvent(event);
			}
		}
	}

	protected List<AutoConfigurationImportListener> getAutoConfigurationImportListeners() {
		return SpringFactoriesLoader.loadFactories(AutoConfigurationImportListener.class, this.beanClassLoader);
	}

	private void invokeAwareMethods(Object instance) {
		if (instance instanceof Aware) {
			if (instance instanceof BeanClassLoaderAware) {
				((BeanClassLoaderAware) instance).setBeanClassLoader(this.beanClassLoader);
			}
			if (instance instanceof BeanFactoryAware) {
				((BeanFactoryAware) instance).setBeanFactory(this.beanFactory);
			}
			if (instance instanceof EnvironmentAware) {
				((EnvironmentAware) instance).setEnvironment(this.environment);
			}
			if (instance instanceof ResourceLoaderAware) {
				((ResourceLoaderAware) instance).setResourceLoader(this.resourceLoader);
			}
		}
	}

	@Override
	public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
		Assert.isInstanceOf(ConfigurableListableBeanFactory.class, beanFactory);
		this.beanFactory = (ConfigurableListableBeanFactory) beanFactory;
	}

	protected final ConfigurableListableBeanFactory getBeanFactory() {
		return this.beanFactory;
	}

	@Override
	public void setBeanClassLoader(ClassLoader classLoader) {
		this.beanClassLoader = classLoader;
	}

	protected ClassLoader getBeanClassLoader() {
		return this.beanClassLoader;
	}

	@Override
	public void setEnvironment(Environment environment) {
		this.environment = environment;
	}

	protected final Environment getEnvironment() {
		return this.environment;
	}

	@Override
	public void setResourceLoader(ResourceLoader resourceLoader) {
		this.resourceLoader = resourceLoader;
	}

	protected final ResourceLoader getResourceLoader() {
		return this.resourceLoader;
	}

	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE - 1;
	}

	private static class AutoConfigurationGroup
			implements DeferredImportSelector.Group, BeanClassLoaderAware, BeanFactoryAware, ResourceLoaderAware {

		private final Map<String, AnnotationMetadata> entries = new LinkedHashMap<>();

		private final List<AutoConfigurationEntry> autoConfigurationEntries = new ArrayList<>();

		private ClassLoader beanClassLoader;

		private BeanFactory beanFactory;

		private ResourceLoader resourceLoader;

		private AutoConfigurationMetadata autoConfigurationMetadata;

		@Override
		public void setBeanClassLoader(ClassLoader classLoader) {
			this.beanClassLoader = classLoader;
		}

		@Override
		public void setBeanFactory(BeanFactory beanFactory) {
			this.beanFactory = beanFactory;
		}

		@Override
		public void setResourceLoader(ResourceLoader resourceLoader) {
			this.resourceLoader = resourceLoader;
		}

		@Override
		public void process(AnnotationMetadata annotationMetadata, DeferredImportSelector deferredImportSelector) {
			Assert.state(deferredImportSelector instanceof AutoConfigurationImportSelector,
					() -> String.format("Only %s implementations are supported, got %s",
							AutoConfigurationImportSelector.class.getSimpleName(),
							deferredImportSelector.getClass().getName()));
			AutoConfigurationEntry autoConfigurationEntry = ((AutoConfigurationImportSelector) deferredImportSelector)
					.getAutoConfigurationEntry(getAutoConfigurationMetadata(), annotationMetadata);
			this.autoConfigurationEntries.add(autoConfigurationEntry);
			for (String importClassName : autoConfigurationEntry.getConfigurations()) {
				this.entries.putIfAbsent(importClassName, annotationMetadata);
			}
		}

		@Override
		public Iterable<Entry> selectImports() {
			if (this.autoConfigurationEntries.isEmpty()) {
				return Collections.emptyList();
			}
			Set<String> allExclusions = this.autoConfigurationEntries.stream()
					.map(AutoConfigurationEntry::getExclusions).flatMap(Collection::stream).collect(Collectors.toSet());
			Set<String> processedConfigurations = this.autoConfigurationEntries.stream()
					.map(AutoConfigurationEntry::getConfigurations).flatMap(Collection::stream)
					.collect(Collectors.toCollection(LinkedHashSet::new));
			processedConfigurations.removeAll(allExclusions);

			return sortAutoConfigurations(processedConfigurations, getAutoConfigurationMetadata()).stream()
					.map((importClassName) -> new Entry(this.entries.get(importClassName), importClassName))
					.collect(Collectors.toList());
		}

		private AutoConfigurationMetadata getAutoConfigurationMetadata() {
			if (this.autoConfigurationMetadata == null) {
				// 尝试加载 `META-INF/spring-autoconfigure-metadata.properties` 文件解析出一个 PropertiesAutoConfigurationMetadata 对象
				// 但是没发现哪里有这个文件
				this.autoConfigurationMetadata = AutoConfigurationMetadataLoader.loadMetadata(this.beanClassLoader);
			}
			return this.autoConfigurationMetadata;
		}

		private List<String> sortAutoConfigurations(Set<String> configurations,
				AutoConfigurationMetadata autoConfigurationMetadata) {
			return new AutoConfigurationSorter(getMetadataReaderFactory(), autoConfigurationMetadata)
					.getInPriorityOrder(configurations);
		}

		private MetadataReaderFactory getMetadataReaderFactory() {
			try {
				return this.beanFactory.getBean(SharedMetadataReaderFactoryContextInitializer.BEAN_NAME,
						MetadataReaderFactory.class);
			}
			catch (NoSuchBeanDefinitionException ex) {
				return new CachingMetadataReaderFactory(this.resourceLoader);
			}
		}

	}

	protected static class AutoConfigurationEntry {

		private final List<String> configurations;

		private final Set<String> exclusions;

		private AutoConfigurationEntry() {
			this.configurations = Collections.emptyList();
			this.exclusions = Collections.emptySet();
		}

		/**
		 * Create an entry with the configurations that were contributed and their
		 * exclusions.
		 * @param configurations the configurations that should be imported
		 * @param exclusions the exclusions that were applied to the original list
		 */
		AutoConfigurationEntry(Collection<String> configurations, Collection<String> exclusions) {
			this.configurations = new ArrayList<>(configurations);
			this.exclusions = new HashSet<>(exclusions);
		}

		public List<String> getConfigurations() {
			return this.configurations;
		}

		public Set<String> getExclusions() {
			return this.exclusions;
		}

	}

}
