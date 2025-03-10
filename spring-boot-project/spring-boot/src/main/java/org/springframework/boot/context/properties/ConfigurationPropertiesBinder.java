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

package org.springframework.boot.context.properties;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.springframework.beans.BeansException;
import org.springframework.beans.PropertyEditorRegistry;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.boot.context.properties.bind.BindHandler;
import org.springframework.boot.context.properties.bind.BindResult;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.bind.handler.IgnoreErrorsBindHandler;
import org.springframework.boot.context.properties.bind.handler.IgnoreTopLevelConverterNotFoundBindHandler;
import org.springframework.boot.context.properties.bind.handler.NoUnboundElementsBindHandler;
import org.springframework.boot.context.properties.bind.validation.ValidationBindHandler;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.UnboundElementsSourceFilter;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.env.PropertySources;
import org.springframework.validation.Validator;
import org.springframework.validation.annotation.Validated;

/**
 * Internal class used by the {@link ConfigurationPropertiesBindingPostProcessor} to
 * handle the actual {@link ConfigurationProperties @ConfigurationProperties} binding.
 *
 * @author Stephane Nicoll
 * @author Phillip Webb
 */
class ConfigurationPropertiesBinder {

	private static final String BEAN_NAME = "org.springframework.boot.context.internalConfigurationPropertiesBinder";

	private static final String FACTORY_BEAN_NAME = "org.springframework.boot.context.internalConfigurationPropertiesBinderFactory";

	private static final String VALIDATOR_BEAN_NAME = EnableConfigurationProperties.VALIDATOR_BEAN_NAME;

	private final ApplicationContext applicationContext;

	private final PropertySources propertySources;

	private final Validator configurationPropertiesValidator;

	private final boolean jsr303Present;

	private volatile Validator jsr303Validator;

	private volatile Binder binder;

	ConfigurationPropertiesBinder(ApplicationContext applicationContext) {
		this.applicationContext = applicationContext;
		this.propertySources = new PropertySourcesDeducer(applicationContext).getPropertySources();
		this.configurationPropertiesValidator = getConfigurationPropertiesValidator(applicationContext);
		this.jsr303Present = ConfigurationPropertiesJsr303Validator.isJsr303Present(applicationContext);
	}

	BindResult<?> bind(ConfigurationPropertiesBean propertiesBean) {
		// <1> 获取这个 Bean 的 Bindable 对象（包含了 `@ConfigurationProperties`、`@Validated` 配置信息和这个 Bean）
		Bindable<?> target = propertiesBean.asBindTarget();
		// <2> 获取这个 Bean 的 `@ConfigurationProperties` 注解信息
		ConfigurationProperties annotation = propertiesBean.getAnnotation();
		// <3> 获取一个 BindHandler 绑定处理器
		BindHandler bindHandler = getBindHandler(target, annotation);
		// <4> 获取一个 Binder 对象，包含了 Spring 应用上下文的所有配置信息，占位符处理器，类型转换器
		// <5> 通过这个 Binder 将指定 `prefix` 前缀的属性值设置到这个 Bean 中，会借助 Conversion 类型转换器进行类型转换，过程复杂，没看懂...
		return getBinder().bind(annotation.prefix(), target, bindHandler);
	}

	Object bindOrCreate(ConfigurationPropertiesBean propertiesBean) {
		Bindable<?> target = propertiesBean.asBindTarget();
		ConfigurationProperties annotation = propertiesBean.getAnnotation();
		BindHandler bindHandler = getBindHandler(target, annotation);
		return getBinder().bindOrCreate(annotation.prefix(), target, bindHandler);
	}

	private Validator getConfigurationPropertiesValidator(ApplicationContext applicationContext) {
		if (applicationContext.containsBean(VALIDATOR_BEAN_NAME)) {
			return applicationContext.getBean(VALIDATOR_BEAN_NAME, Validator.class);
		}
		return null;
	}

	private <T> BindHandler getBindHandler(Bindable<T> target, ConfigurationProperties annotation) {
		// <1> 获取几个 Validator 校验器
		List<Validator> validators = getValidators(target);
		// <2> 创建一个最顶层的 BindHandler
		BindHandler handler = new IgnoreTopLevelConverterNotFoundBindHandler();
		// <3> 如果忽略无效的字段（默认为 `false`）
		if (annotation.ignoreInvalidFields()) {
			handler = new IgnoreErrorsBindHandler(handler);
		}
		// <4> 如果不忽略不知道的字段（默认也不会进入这里）
		if (!annotation.ignoreUnknownFields()) {
			UnboundElementsSourceFilter filter = new UnboundElementsSourceFilter();
			handler = new NoUnboundElementsBindHandler(handler, filter);
		}
		// <5> 如果检验器不为空，则将其封装成 ValidationBindHandler 对象，里面保存了这几个 Validator
		if (!validators.isEmpty()) {
			handler = new ValidationBindHandler(handler, validators.toArray(new Validator[0]));
		}
		// <6> 获取 ConfigurationPropertiesBindHandlerAdvisor 对 `handler` 应用，暂时忽略
		for (ConfigurationPropertiesBindHandlerAdvisor advisor : getBindHandlerAdvisors()) {
			handler = advisor.apply(handler);
		}
		// <7> 返回这个 `handler` 配置绑定处理器
		return handler;
	}

	private List<Validator> getValidators(Bindable<?> target) {
		List<Validator> validators = new ArrayList<>(3);
		if (this.configurationPropertiesValidator != null) {
			validators.add(this.configurationPropertiesValidator);
		}
		if (this.jsr303Present && target.getAnnotation(Validated.class) != null) {
			validators.add(getJsr303Validator());
		}
		if (target.getValue() != null && target.getValue().get() instanceof Validator) {
			validators.add((Validator) target.getValue().get());
		}
		return validators;
	}

	private Validator getJsr303Validator() {
		if (this.jsr303Validator == null) {
			this.jsr303Validator = new ConfigurationPropertiesJsr303Validator(this.applicationContext);
		}
		return this.jsr303Validator;
	}

	private List<ConfigurationPropertiesBindHandlerAdvisor> getBindHandlerAdvisors() {
		return this.applicationContext.getBeanProvider(ConfigurationPropertiesBindHandlerAdvisor.class).orderedStream()
				.collect(Collectors.toList());
	}

	private Binder getBinder() {
		if (this.binder == null) {
			this.binder = new Binder(getConfigurationPropertySources(), // Spring 应用的 PropertySource 属性资源
					getPropertySourcesPlaceholdersResolver(), // 占位符处理器
					getConversionService(),  // 类型转换器
					getPropertyEditorInitializer(), // 属性编辑器
					null,
					ConfigurationPropertiesBindConstructorProvider.INSTANCE);
		}
		return this.binder;
	}

	private Iterable<ConfigurationPropertySource> getConfigurationPropertySources() {
		return ConfigurationPropertySources.from(this.propertySources);
	}

	private PropertySourcesPlaceholdersResolver getPropertySourcesPlaceholdersResolver() {
		return new PropertySourcesPlaceholdersResolver(this.propertySources);
	}

	private ConversionService getConversionService() {
		return new ConversionServiceDeducer(this.applicationContext).getConversionService();
	}

	private Consumer<PropertyEditorRegistry> getPropertyEditorInitializer() {
		if (this.applicationContext instanceof ConfigurableApplicationContext) {
			return ((ConfigurableApplicationContext) this.applicationContext).getBeanFactory()::copyRegisteredEditorsTo;
		}
		return null;
	}

	static void register(BeanDefinitionRegistry registry) {
		if (!registry.containsBeanDefinition(FACTORY_BEAN_NAME)) {
			GenericBeanDefinition definition = new GenericBeanDefinition();
			definition.setBeanClass(ConfigurationPropertiesBinder.Factory.class);
			definition.setRole(BeanDefinition.ROLE_INFRASTRUCTURE);
			registry.registerBeanDefinition(ConfigurationPropertiesBinder.FACTORY_BEAN_NAME, definition);
		}
		if (!registry.containsBeanDefinition(BEAN_NAME)) {
			GenericBeanDefinition definition = new GenericBeanDefinition();
			definition.setBeanClass(ConfigurationPropertiesBinder.class);
			definition.setRole(BeanDefinition.ROLE_INFRASTRUCTURE);
			definition.setFactoryBeanName(FACTORY_BEAN_NAME);
			definition.setFactoryMethodName("create");
			registry.registerBeanDefinition(ConfigurationPropertiesBinder.BEAN_NAME, definition);
		}
	}

	static ConfigurationPropertiesBinder get(BeanFactory beanFactory) {
		return beanFactory.getBean(BEAN_NAME, ConfigurationPropertiesBinder.class);
	}

	/**
	 * Factory bean used to create the {@link ConfigurationPropertiesBinder}. The bean
	 * needs to be {@link ApplicationContextAware} since we can't directly inject an
	 * {@link ApplicationContext} into the constructor without causing eager
	 * {@link FactoryBean} initialization.
	 */
	static class Factory implements ApplicationContextAware {

		private ApplicationContext applicationContext;

		@Override
		public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
			this.applicationContext = applicationContext;
		}

		ConfigurationPropertiesBinder create() {
			return new ConfigurationPropertiesBinder(this.applicationContext);
		}

	}

}
