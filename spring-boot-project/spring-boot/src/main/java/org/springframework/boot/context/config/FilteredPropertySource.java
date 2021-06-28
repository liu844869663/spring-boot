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

package org.springframework.boot.context.config;

import java.util.Set;
import java.util.function.Consumer;

import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;

/**
 * Internal {@link PropertySource} implementation used by
 * {@link ConfigFileApplicationListener} to filter out properties for specific operations.
 *
 * @author Phillip Webb
 */
class FilteredPropertySource extends PropertySource<PropertySource<?>> {

	private final Set<String> filteredProperties;

	FilteredPropertySource(PropertySource<?> original, Set<String> filteredProperties) {
		super(original.getName(), original);
		this.filteredProperties = filteredProperties;
	}

	@Override
	public Object getProperty(String name) {
		if (this.filteredProperties.contains(name)) {
			return null;
		}
		return getSource().getProperty(name);
	}

	static void apply(ConfigurableEnvironment environment, String propertySourceName, Set<String> filteredProperties,
			Consumer<PropertySource<?>> operation) {
		MutablePropertySources propertySources = environment.getPropertySources();
		// 先获取当前环境中 `defaultProperties` 的 PropertySource 对象，默认没有，通常我们也不会配置
		PropertySource<?> original = propertySources.get(propertySourceName);
		if (original == null) {
			// 直接调用 `operation` 函数
			operation.accept(null);
			return;
		}
		// 将这个当前环境中 `defaultProperties` 的 PropertySource 对象进行替换
		// 也就是封装成一个 FilteredPropertySource 对象，设置了几个需要过滤的属性
		propertySources.replace(propertySourceName, new FilteredPropertySource(original, filteredProperties));
		try {
			// 调用 `operation` 函数，入参是默认值的 PropertySource
			operation.accept(original);
		}
		finally {
			// 将当前环境中 `defaultProperties` 的 PropertySource 对象还原
			propertySources.replace(propertySourceName, original);
		}
	}

}
