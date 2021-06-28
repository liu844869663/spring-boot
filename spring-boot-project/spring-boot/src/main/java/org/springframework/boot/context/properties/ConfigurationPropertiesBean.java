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

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.validation.annotation.Validated;

/**
 * Provides access to {@link ConfigurationProperties @ConfigurationProperties} bean
 * details, regardless of if the annotation was used directly or on a {@link Bean @Bean}
 * factory method. This class can be used to access {@link #getAll(ApplicationContext)
 * all} configuration properties beans in an ApplicationContext, or
 * {@link #get(ApplicationContext, Object, String) individual beans} on a case-by-case
 * basis (for example, in a {@link BeanPostProcessor}).
 *
 * @author Phillip Webb
 * @since 2.2.0
 * @see #getAll(ApplicationContext)
 * @see #get(ApplicationContext, Object, String)
 */
public final class ConfigurationPropertiesBean {

	/**
	 * Bean 的名称
	 */
	private final String name;
	/**
	 * Bean 的实例对象
	 */
	private final Object instance;
	/**
	 * Bean 的 `@ConfigurationProperties` 注解
	 */
	private final ConfigurationProperties annotation;
	/**
	 * `@Bean` 对应的方法资源对象，包括实例对象和注解信息
	 */
	private final Bindable<?> bindTarget;
	/**
	 * `@Bean` 对应的方法
	 */
	private final BindMethod bindMethod;

	private ConfigurationPropertiesBean(String name, Object instance, ConfigurationProperties annotation,
			Bindable<?> bindTarget) {
		this.name = name;
		this.instance = instance;
		this.annotation = annotation;
		this.bindTarget = bindTarget;
		this.bindMethod = BindMethod.forType(bindTarget.getType().resolve());
	}

	/**
	 * Return the name of the Spring bean.
	 * @return the bean name
	 */
	public String getName() {
		return this.name;
	}

	/**
	 * Return the actual Spring bean instance.
	 * @return the bean instance
	 */
	public Object getInstance() {
		return this.instance;
	}

	/**
	 * Return the bean type.
	 * @return the bean type
	 */
	Class<?> getType() {
		return this.bindTarget.getType().resolve();
	}

	/**
	 * Return the property binding method that was used for the bean.
	 * @return the bind type
	 */
	public BindMethod getBindMethod() {
		return this.bindMethod;
	}

	/**
	 * Return the {@link ConfigurationProperties} annotation for the bean. The annotation
	 * may be defined on the bean itself or from the factory method that create the bean
	 * (usually a {@link Bean @Bean} method).
	 * @return the configuration properties annotation
	 */
	public ConfigurationProperties getAnnotation() {
		return this.annotation;
	}

	/**
	 * Return a {@link Bindable} instance suitable that can be used as a target for the
	 * {@link Binder}.
	 * @return a bind target for use with the {@link Binder}
	 */
	public Bindable<?> asBindTarget() {
		return this.bindTarget;
	}

	/**
	 * Return all {@link ConfigurationProperties @ConfigurationProperties} beans contained
	 * in the given application context. Both directly annotated beans, as well as beans
	 * that have {@link ConfigurationProperties @ConfigurationProperties} annotated
	 * factory methods are included.
	 * @param applicationContext the source application context
	 * @return a map of all configuration properties beans keyed by the bean name
	 */
	public static Map<String, ConfigurationPropertiesBean> getAll(ApplicationContext applicationContext) {
		Assert.notNull(applicationContext, "ApplicationContext must not be null");
		if (applicationContext instanceof ConfigurableApplicationContext) {
			return getAll((ConfigurableApplicationContext) applicationContext);
		}
		Map<String, ConfigurationPropertiesBean> propertiesBeans = new LinkedHashMap<>();
		applicationContext.getBeansWithAnnotation(ConfigurationProperties.class)
				.forEach((beanName, bean) -> propertiesBeans.put(beanName, get(applicationContext, bean, beanName)));
		return propertiesBeans;
	}

	private static Map<String, ConfigurationPropertiesBean> getAll(ConfigurableApplicationContext applicationContext) {
		Map<String, ConfigurationPropertiesBean> propertiesBeans = new LinkedHashMap<>();
		ConfigurableListableBeanFactory beanFactory = applicationContext.getBeanFactory();
		Iterator<String> beanNames = beanFactory.getBeanNamesIterator();
		while (beanNames.hasNext()) {
			String beanName = beanNames.next();
			if (isConfigurationPropertiesBean(beanFactory, beanName)) {
				try {
					Object bean = beanFactory.getBean(beanName);
					ConfigurationPropertiesBean propertiesBean = get(applicationContext, bean, beanName);
					propertiesBeans.put(beanName, propertiesBean);
				}
				catch (Exception ex) {
				}
			}
		}
		return propertiesBeans;
	}

	private static boolean isConfigurationPropertiesBean(ConfigurableListableBeanFactory beanFactory, String beanName) {
		try {
			if (beanFactory.getBeanDefinition(beanName).isAbstract()) {
				return false;
			}
			if (beanFactory.findAnnotationOnBean(beanName, ConfigurationProperties.class) != null) {
				return true;
			}
			Method factoryMethod = findFactoryMethod(beanFactory, beanName);
			return findMergedAnnotation(factoryMethod, ConfigurationProperties.class).isPresent();
		}
		catch (NoSuchBeanDefinitionException ex) {
			return false;
		}
	}

	/**
	 * Return a {@link ConfigurationPropertiesBean @ConfigurationPropertiesBean} instance
	 * for the given bean details or {@code null} if the bean is not a
	 * {@link ConfigurationProperties @ConfigurationProperties} object. Annotations are
	 * considered both on the bean itself, as well as any factory method (for example a
	 * {@link Bean @Bean} method).
	 * @param applicationContext the source application context
	 * @param bean the bean to consider
	 * @param beanName the bean name
	 * @return a configuration properties bean or {@code null} if the neither the bean or
	 * factory method are annotated with
	 * {@link ConfigurationProperties @ConfigurationProperties}
	 */
	public static ConfigurationPropertiesBean get(ApplicationContext applicationContext, Object bean, String beanName) {
		// <1> 找到这个 `beanName` 对应的工厂方法，例如 `@Bean` 标注的方法就是一个工厂方法，不是 `@Bean` 的话这里为空
		Method factoryMethod = findFactoryMethod(applicationContext, beanName);
		// <2> 创建一个 ConfigurationPropertiesBean 对象，包含了这个 Bean 的 `@ConfigurationProperties` 注解信息
		return create(beanName, bean, bean.getClass(), factoryMethod);
	}

	private static Method findFactoryMethod(ApplicationContext applicationContext, String beanName) {
		if (applicationContext instanceof ConfigurableApplicationContext) {
			return findFactoryMethod((ConfigurableApplicationContext) applicationContext, beanName);
		}
		return null;
	}

	private static Method findFactoryMethod(ConfigurableApplicationContext applicationContext, String beanName) {
		return findFactoryMethod(applicationContext.getBeanFactory(), beanName);
	}

	private static Method findFactoryMethod(ConfigurableListableBeanFactory beanFactory, String beanName) {
		if (beanFactory.containsBeanDefinition(beanName)) {
			BeanDefinition beanDefinition = beanFactory.getMergedBeanDefinition(beanName);
			if (beanDefinition instanceof RootBeanDefinition) {
				Method resolvedFactoryMethod = ((RootBeanDefinition) beanDefinition).getResolvedFactoryMethod();
				if (resolvedFactoryMethod != null) {
					return resolvedFactoryMethod;
				}
			}
			return findFactoryMethodUsingReflection(beanFactory, beanDefinition);
		}
		return null;
	}

	private static Method findFactoryMethodUsingReflection(ConfigurableListableBeanFactory beanFactory,
			BeanDefinition beanDefinition) {
		String factoryMethodName = beanDefinition.getFactoryMethodName();
		String factoryBeanName = beanDefinition.getFactoryBeanName();
		if (factoryMethodName == null || factoryBeanName == null) {
			return null;
		}
		Class<?> factoryType = beanFactory.getType(factoryBeanName);
		if (factoryType.getName().contains(ClassUtils.CGLIB_CLASS_SEPARATOR)) {
			factoryType = factoryType.getSuperclass();
		}
		AtomicReference<Method> factoryMethod = new AtomicReference<>();
		ReflectionUtils.doWithMethods(factoryType, (method) -> {
			if (method.getName().equals(factoryMethodName)) {
				factoryMethod.set(method);
			}
		});
		return factoryMethod.get();
	}

	static ConfigurationPropertiesBean forValueObject(Class<?> beanClass, String beanName) {
		ConfigurationPropertiesBean propertiesBean = create(beanName, null, beanClass, null);
		Assert.state(propertiesBean != null && propertiesBean.getBindMethod() == BindMethod.VALUE_OBJECT,
				"Bean '" + beanName + "' is not a @ConfigurationProperties value object");
		return propertiesBean;
	}

	private static ConfigurationPropertiesBean create(String name, Object instance, Class<?> type, Method factory) {
		// <1> 找到这个 Bean 上面的 `@ConfigurationProperties` 注解
		// 如果是 `@Bean` 标注的方法 Bean，也会尝试从所在的 Class 类上面获取
		ConfigurationProperties annotation = findAnnotation(instance, type, factory, ConfigurationProperties.class);
		// <2> 如果没有配置 `@ConfigurationProperties` 注解，则直接返回 `null`
		if (annotation == null) {
			return null;
		}
		// <3> 找到这个 Bean 上面的 `@Validated` 注解
		Validated validated = findAnnotation(instance, type, factory, Validated.class);
		// <4> 将 `@ConfigurationProperties`、`Validated`注解信息，目标 Bean 以及它的 Class 对象，绑定到一个 Bindable 对象中
		Annotation[] annotations = (validated != null) ? new Annotation[] { annotation, validated }
				: new Annotation[] { annotation };
		ResolvableType bindType = (factory != null) ? ResolvableType.forMethodReturnType(factory)
				: ResolvableType.forClass(type);

		Bindable<Object> bindTarget = Bindable.of(bindType).withAnnotations(annotations);
		if (instance != null) {
			bindTarget = bindTarget.withExistingValue(instance);
		}
		// <5> 将 `beanName`、目标 Bean、`ConfigurationProperties` 注解、第 `4` 步的 Bindable 对象封装到一个 ConfigurationPropertiesBean 对象中
		return new ConfigurationPropertiesBean(name, instance, annotation, bindTarget);
	}

	private static <A extends Annotation> A findAnnotation(Object instance, Class<?> type, Method factory,
			Class<A> annotationType) {
		MergedAnnotation<A> annotation = MergedAnnotation.missing();
		if (factory != null) {
			annotation = findMergedAnnotation(factory, annotationType);
		}
		if (!annotation.isPresent()) {
			annotation = findMergedAnnotation(type, annotationType);
		}
		if (!annotation.isPresent() && AopUtils.isAopProxy(instance)) {
			annotation = MergedAnnotations.from(AopUtils.getTargetClass(instance), SearchStrategy.TYPE_HIERARCHY)
					.get(annotationType);
		}
		return annotation.isPresent() ? annotation.synthesize() : null;
	}

	private static <A extends Annotation> MergedAnnotation<A> findMergedAnnotation(AnnotatedElement element,
			Class<A> annotationType) {
		return (element != null) ? MergedAnnotations.from(element, SearchStrategy.TYPE_HIERARCHY).get(annotationType)
				: MergedAnnotation.missing();
	}

	/**
	 * The binding method that is used for the bean.
	 */
	public enum BindMethod {

		/**
		 * Java Bean using getter/setter binding.
		 */
		JAVA_BEAN,

		/**
		 * Value object using constructor binding.
		 */
		VALUE_OBJECT;

		static BindMethod forType(Class<?> type) {
			return (ConfigurationPropertiesBindConstructorProvider.INSTANCE.getBindConstructor(type, false) != null)
					? VALUE_OBJECT : JAVA_BEAN;
		}

	}

}
