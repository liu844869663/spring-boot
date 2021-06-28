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

package org.springframework.boot.context.properties;

import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.type.AnnotationMetadata;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@link ImportBeanDefinitionRegistrar} for
 * {@link EnableConfigurationProperties @EnableConfigurationProperties}.
 *
 * @author Phillip Webb
 */
class EnableConfigurationPropertiesRegistrar implements ImportBeanDefinitionRegistrar {

	@Override
	public void registerBeanDefinitions(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
		// <1> 先注册两个内部 Bean
		registerInfrastructureBeans(registry);
		// <2> 创建一个 ConfigurationPropertiesBeanRegistrar 对象
		ConfigurationPropertiesBeanRegistrar beanRegistrar = new ConfigurationPropertiesBeanRegistrar(registry);
		// <3> 获取 `@EnableConfigurationProperties` 注解指定的 Class 类对象们
		// <4> 依次注册指定的 Class 类对应的 BeanDefinition
		// 这样一来这个 Class 不用标注 `@Component` 就可以注入这个配置属性对象了
		getTypes(metadata).forEach(beanRegistrar::register);
	}

	private Set<Class<?>> getTypes(AnnotationMetadata metadata) {
		return metadata.getAnnotations().stream(EnableConfigurationProperties.class)
				.flatMap((annotation) -> Arrays.stream(annotation.getClassArray(MergedAnnotation.VALUE)))
				.filter((type) -> void.class != type).collect(Collectors.toSet());
	}

	/**
	 * 可参考 ConfigurationPropertiesAutoConfiguration 自动配置类
	 */
	@SuppressWarnings("deprecation")
	static void registerInfrastructureBeans(BeanDefinitionRegistry registry) {
		// 注册一个 ConfigurationPropertiesBindingPostProcessor 类型的 BeanDefinition（内部角色），如果不存在的话
		// 同时也会注册 ConfigurationPropertiesBinder 和 ConfigurationPropertiesBinder.Factory 两个 Bean，如果不存在的话
		ConfigurationPropertiesBindingPostProcessor.register(registry);
		// 注册一个 ConfigurationBeanFactoryMetadata 类型的 BeanDefinition（内部角色）
		// 这个 Bean 从 Spring 2.2.0 开始就被废弃了
		ConfigurationBeanFactoryMetadata.register(registry);
	}

}
