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

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.context.properties.ConfigurationPropertiesBean.BindMethod;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.env.PropertySources;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

/**
 * {@link BeanPostProcessor} to bind {@link PropertySources} to beans annotated with
 * {@link ConfigurationProperties @ConfigurationProperties}.
 *
 * @author Dave Syer
 * @author Phillip Webb
 * @author Christian Dupuis
 * @author Stephane Nicoll
 * @author Madhura Bhave
 * @since 1.0.0
 */
public class ConfigurationPropertiesBindingPostProcessor
		implements BeanPostProcessor, PriorityOrdered, ApplicationContextAware, InitializingBean {

	/**
	 * The bean name that this post-processor is registered with.
	 */
	public static final String BEAN_NAME = ConfigurationPropertiesBindingPostProcessor.class.getName();

	/**
	 * The bean name of the configuration properties validator.
	 * @deprecated since 2.2.0 in favor of
	 * {@link EnableConfigurationProperties#VALIDATOR_BEAN_NAME}
	 */
	@Deprecated
	public static final String VALIDATOR_BEAN_NAME = EnableConfigurationProperties.VALIDATOR_BEAN_NAME;

	/**
	 * Spring 应用上下文
	 */
	private ApplicationContext applicationContext;

	/**
	 * BeanDefinition 注册中心
	 */
	private BeanDefinitionRegistry registry;

	/**
	 * 属性绑定器
	 */
	private ConfigurationPropertiesBinder binder;

	/**
	 * Create a new {@link ConfigurationPropertiesBindingPostProcessor} instance.
	 * @deprecated since 2.2.0 in favor of
	 * {@link EnableConfigurationProperties @EnableConfigurationProperties} or
	 * {@link ConfigurationPropertiesBindingPostProcessor#register(BeanDefinitionRegistry)}
	 */
	@Deprecated
	public ConfigurationPropertiesBindingPostProcessor() {
	}

	@Override
	public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
		// Aware 接口回调，获取 Spring 应用上下文
		this.applicationContext = applicationContext;
	}

	/**
	 * 初始化当前 Bean
	 */
	@Override
	public void afterPropertiesSet() throws Exception {
		// We can't use constructor injection of the application context because
		// it causes eager factory bean initialization
		// 从 Spring 应用上下文获取 BeanDefinition 注册中心
		this.registry = (BeanDefinitionRegistry) this.applicationContext.getAutowireCapableBeanFactory();
		// 获取 ConfigurationPropertiesBinder 这个 Bean，在这个类的 `register` 方法中注册了哦
		this.binder = ConfigurationPropertiesBinder.get(this.applicationContext);
	}

	// 次于最高优先级
	@Override
	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE + 1;
	}

	/**
	 * 在 Bean 的初始化前会调用这个方法
	 * 参考 {@link AbstractAutowireCapableBeanFactory#applyBeanPostProcessorsBeforeInitialization(Object, String)}
	 */
	@Override
	public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
		// <1> 先尝试根据 Bean 解析出一个 ConfigurationPropertiesBean 对象，包含 `@ConfigurationProperties` 注解信息
		// <2> 然后开始获取指定 `prefix` 前缀的属性值，设置到这个 Bean 中
		bind(ConfigurationPropertiesBean.get(this.applicationContext, bean, beanName));
		// <3> 返回属性填充后的 Bean
		return bean;
	}

	private void bind(ConfigurationPropertiesBean bean) {
		// <1> 如果这个 `bean` 为空，或者已经处理过，则直接返回
		if (bean == null || hasBoundValueObject(bean.getName())) {
			return;
		}
		// <2> 对 `@ConstructorBinding` 的校验，如果使用该注解但是没有找到合适的构造器，那么在这里抛出异常
		Assert.state(bean.getBindMethod() == BindMethod.JAVA_BEAN, "Cannot bind @ConfigurationProperties for bean '"
				+ bean.getName() + "'. Ensure that @ConstructorBinding has not been applied to regular bean");
		try {
			// <3> 通过 Binder 将指定 `prefix` 前缀的属性值设置到这个 Bean 中，会借助 Conversion 类型转换器进行类型转换，过程复杂，没看懂...
			this.binder.bind(bean);
		}
		catch (Exception ex) {
			throw new ConfigurationPropertiesBindException(bean, ex);
		}
	}

	private boolean hasBoundValueObject(String beanName) {
		return this.registry.containsBeanDefinition(beanName) && this.registry
				.getBeanDefinition(beanName) instanceof ConfigurationPropertiesValueObjectBeanDefinition;
	}

	/**
	 * Register a {@link ConfigurationPropertiesBindingPostProcessor} bean if one is not
	 * already registered.
	 * @param registry the bean definition registry
	 * @since 2.2.0
	 */
	public static void register(BeanDefinitionRegistry registry) {
		Assert.notNull(registry, "Registry must not be null");
		// 注册 ConfigurationPropertiesBindingPostProcessor 类型的 BeanDefinition（内部角色）
		if (!registry.containsBeanDefinition(BEAN_NAME)) {
			GenericBeanDefinition definition = new GenericBeanDefinition();
			definition.setBeanClass(ConfigurationPropertiesBindingPostProcessor.class);
			definition.setRole(BeanDefinition.ROLE_INFRASTRUCTURE);
			registry.registerBeanDefinition(BEAN_NAME, definition);
		}
		// 注册 ConfigurationPropertiesBinder 和 ConfigurationPropertiesBinder.Factory 两个 BeanDefinition（内部角色）
		ConfigurationPropertiesBinder.register(registry);
	}

}
