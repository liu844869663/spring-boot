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

package org.springframework.boot.web.servlet;

import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EventListener;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;

import javax.servlet.Filter;
import javax.servlet.MultipartConfigElement;
import javax.servlet.Servlet;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.aop.scope.ScopedProxyUtils;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * A collection {@link ServletContextInitializer}s obtained from a
 * {@link ListableBeanFactory}. Includes all {@link ServletContextInitializer} beans and
 * also adapts {@link Servlet}, {@link Filter} and certain {@link EventListener} beans.
 * <p>
 * Items are sorted so that adapted beans are top ({@link Servlet}, {@link Filter} then
 * {@link EventListener}) and direct {@link ServletContextInitializer} beans are at the
 * end. Further sorting is applied within these groups using the
 * {@link AnnotationAwareOrderComparator}.
 *
 * @author Dave Syer
 * @author Phillip Webb
 * @author Brian Clozel
 * @since 1.4.0
 */
public class ServletContextInitializerBeans extends AbstractCollection<ServletContextInitializer> {

	private static final String DISPATCHER_SERVLET_NAME = "dispatcherServlet";

	private static final Log logger = LogFactory.getLog(ServletContextInitializerBeans.class);

	/**
	 * Seen bean instances or bean names.
	 * 所有的 Servlet or Filter or EventListener or ServletContextInitializer 对象
	 * 也可能是该对象对应的 `beanName`
	 */
	private final Set<Object> seen = new HashSet<>();

	/**
	 * 保存不同类型的 ServletContextInitializer 对象
	 * key：Servlet or Filter or EventListener or ServletContextInitializer
	 * value：ServletContextInitializer 实现类
	 */
	private final MultiValueMap<Class<?>, ServletContextInitializer> initializers;

	/**
	 * 指定 ServletContextInitializer 的类型，默认就是它
	 */
	private final List<Class<? extends ServletContextInitializer>> initializerTypes;

	/**
	 * 排序后的所有 `initializers` 中的 ServletContextInitializer 实现类（不可被修改）
	 */
	private List<ServletContextInitializer> sortedList;

	@SafeVarargs
	public ServletContextInitializerBeans(ListableBeanFactory beanFactory,
			Class<? extends ServletContextInitializer>... initializerTypes) {
		this.initializers = new LinkedMultiValueMap<>();
		// <1> 设置类型为 `ServletContextInitializer`
		this.initializerTypes = (initializerTypes.length != 0) ? Arrays.asList(initializerTypes)
				: Collections.singletonList(ServletContextInitializer.class);
		// <2> 找到 IoC 容器中所有 `ServletContextInitializer` 类型的 Bean
		// 并将这些信息添加到 `seen` 和 `initializers` 集合中
		addServletContextInitializerBeans(beanFactory);
		// <3> 从 IoC 容器中获取 Servlet or Filter or EventListener 类型的 Bean
		// 适配成 RegistrationBean 对象，并添加到 `initializers` 和 `seen` 集合中
		addAdaptableBeans(beanFactory);
		// <4> 将 `initializers` 中的所有 ServletContextInitializer 进行排序，并保存至 `sortedList` 集合中
		List<ServletContextInitializer> sortedInitializers = this.initializers.values().stream()
				.flatMap((value) -> value.stream().sorted(AnnotationAwareOrderComparator.INSTANCE))
				.collect(Collectors.toList());
		this.sortedList = Collections.unmodifiableList(sortedInitializers);
		// <5> DEBUG 模式下打印日志
		logMappings(this.initializers);
	}

	private void addServletContextInitializerBeans(ListableBeanFactory beanFactory) {
		for (Class<? extends ServletContextInitializer> initializerType : this.initializerTypes) {
			// 找到所有 ServletContextInitializer 类型的 Bean
			for (Entry<String, ? extends ServletContextInitializer> initializerBean : getOrderedBeansOfType(beanFactory, initializerType)) {
				// 对该 Bean 进行处理，添加至 `seen` 和 `initializers` 集合中
				addServletContextInitializerBean(initializerBean.getKey(), initializerBean.getValue(), beanFactory);
			}
		}
	}

	private void addServletContextInitializerBean(String beanName, ServletContextInitializer initializer,
			ListableBeanFactory beanFactory) {
		if (initializer instanceof ServletRegistrationBean) {
			// 获取对应的 Servlet 对象
			Servlet source = ((ServletRegistrationBean<?>) initializer).getServlet();
			// 将 `initializer` 和 `source` 添加至 `initializers` 和 `seen` 集合中
			addServletContextInitializerBean(Servlet.class, beanName, initializer, beanFactory, source);
		}
		else if (initializer instanceof FilterRegistrationBean) {
			// 获取对应的 Filter 对象
			Filter source = ((FilterRegistrationBean<?>) initializer).getFilter();
			// 将 `initializer` 和 `source` 添加至 `initializers` 和 `seen` 集合中
			addServletContextInitializerBean(Filter.class, beanName, initializer, beanFactory, source);
		}
		else if (initializer instanceof DelegatingFilterProxyRegistrationBean) {
			// 获取对应的 `beanName`
			String source = ((DelegatingFilterProxyRegistrationBean) initializer).getTargetBeanName();
			// 将 `initializer` 和 `source` 添加至 `initializers` 和 `seen` 集合中
			addServletContextInitializerBean(Filter.class, beanName, initializer, beanFactory, source);
		}
		else if (initializer instanceof ServletListenerRegistrationBean) {
			// 获取对应的 EventListener 对象
			EventListener source = ((ServletListenerRegistrationBean<?>) initializer).getListener();
			// 将 `initializer` 和 `source` 添加至 `initializers` 和 `seen` 集合中
			addServletContextInitializerBean(EventListener.class, beanName, initializer, beanFactory, source);
		}
		else {
			// 将 `initializer` 和 `source` 添加至 `initializers` 和 `seen` 集合中
			addServletContextInitializerBean(ServletContextInitializer.class, beanName, initializer, beanFactory, initializer);
		}
	}

	private void addServletContextInitializerBean(Class<?> type, String beanName, ServletContextInitializer initializer,
			ListableBeanFactory beanFactory, Object source) {
		this.initializers.add(type, initializer);
		if (source != null) {
			// Mark the underlying source as seen in case it wraps an existing bean
			this.seen.add(source);
		}
		if (logger.isTraceEnabled()) {
			String resourceDescription = getResourceDescription(beanName, beanFactory);
			int order = getOrder(initializer);
			logger.trace("Added existing " + type.getSimpleName() + " initializer bean '" + beanName + "'; order="
					+ order + ", resource=" + resourceDescription);
		}
	}

	private String getResourceDescription(String beanName, ListableBeanFactory beanFactory) {
		if (beanFactory instanceof BeanDefinitionRegistry) {
			BeanDefinitionRegistry registry = (BeanDefinitionRegistry) beanFactory;
			return registry.getBeanDefinition(beanName).getResourceDescription();
		}
		return "unknown";
	}

	@SuppressWarnings("unchecked")
	protected void addAdaptableBeans(ListableBeanFactory beanFactory) {
		// 获取 MultipartConfigElement 对象
		MultipartConfigElement multipartConfig = getMultipartConfig(beanFactory);
		// 从 IoC 容器中获取 Servlet 类型的 Bean，适配成 ServletRegistrationBean 对象，并添加到 `initializers` 和 `seen` 集合中
		addAsRegistrationBean(beanFactory, Servlet.class, new ServletRegistrationBeanAdapter(multipartConfig));
		// 从 IoC 容器中获取 Filter 类型的 Bean，适配成 FilterRegistrationBean 对象，并添加到 `initializers` 和 `seen` 集合中
		addAsRegistrationBean(beanFactory, Filter.class, new FilterRegistrationBeanAdapter());
		// 从 IoC 容器中获取其他支持的 EventListener 类型（例如 ServletContextListener）的 Bean
		// 适配成 ServletRegistrationBean 对象，并添加到 `initializers` 和 `seen` 集合中
		for (Class<?> listenerType : ServletListenerRegistrationBean.getSupportedTypes()) {
			addAsRegistrationBean(beanFactory, EventListener.class, (Class<EventListener>) listenerType,
					new ServletListenerRegistrationBeanAdapter());
		}
	}

	private MultipartConfigElement getMultipartConfig(ListableBeanFactory beanFactory) {
		List<Entry<String, MultipartConfigElement>> beans = getOrderedBeansOfType(beanFactory,
				MultipartConfigElement.class);
		return beans.isEmpty() ? null : beans.get(0).getValue();
	}

	protected <T> void addAsRegistrationBean(ListableBeanFactory beanFactory, Class<T> type,
			RegistrationBeanAdapter<T> adapter) {
		addAsRegistrationBean(beanFactory, type, type, adapter);
	}

	private <T, B extends T> void addAsRegistrationBean(ListableBeanFactory beanFactory, Class<T> type,
			Class<B> beanType, RegistrationBeanAdapter<T> adapter) {
		List<Map.Entry<String, B>> entries = getOrderedBeansOfType(beanFactory, beanType, this.seen);
		for (Entry<String, B> entry : entries) {
			String beanName = entry.getKey();
			B bean = entry.getValue();
			if (this.seen.add(bean)) {
				// One that we haven't already seen
				RegistrationBean registration = adapter.createRegistrationBean(beanName, bean, entries.size());
				int order = getOrder(bean);
				registration.setOrder(order);
				this.initializers.add(type, registration);
				if (logger.isTraceEnabled()) {
					logger.trace("Created " + type.getSimpleName() + " initializer for bean '" + beanName + "'; order="
							+ order + ", resource=" + getResourceDescription(beanName, beanFactory));
				}
			}
		}
	}

	private int getOrder(Object value) {
		return new AnnotationAwareOrderComparator() {
			@Override
			public int getOrder(Object obj) {
				return super.getOrder(obj);
			}
		}.getOrder(value);
	}

	private <T> List<Entry<String, T>> getOrderedBeansOfType(ListableBeanFactory beanFactory, Class<T> type) {
		return getOrderedBeansOfType(beanFactory, type, Collections.emptySet());
	}

	private <T> List<Entry<String, T>> getOrderedBeansOfType(ListableBeanFactory beanFactory, Class<T> type,
			Set<?> excludes) {
		String[] names = beanFactory.getBeanNamesForType(type, true, false);
		Map<String, T> map = new LinkedHashMap<>();
		for (String name : names) {
			if (!excludes.contains(name) && !ScopedProxyUtils.isScopedTarget(name)) {
				T bean = beanFactory.getBean(name, type);
				if (!excludes.contains(bean)) {
					map.put(name, bean);
				}
			}
		}
		List<Entry<String, T>> beans = new ArrayList<>(map.entrySet());
		beans.sort((o1, o2) -> AnnotationAwareOrderComparator.INSTANCE.compare(o1.getValue(), o2.getValue()));
		return beans;
	}

	private void logMappings(MultiValueMap<Class<?>, ServletContextInitializer> initializers) {
		if (logger.isDebugEnabled()) {
			logMappings("filters", initializers, Filter.class, FilterRegistrationBean.class);
			logMappings("servlets", initializers, Servlet.class, ServletRegistrationBean.class);
		}
	}

	private void logMappings(String name, MultiValueMap<Class<?>, ServletContextInitializer> initializers,
			Class<?> type, Class<? extends RegistrationBean> registrationType) {
		List<ServletContextInitializer> registrations = new ArrayList<>();
		registrations.addAll(initializers.getOrDefault(registrationType, Collections.emptyList()));
		registrations.addAll(initializers.getOrDefault(type, Collections.emptyList()));
		String info = registrations.stream().map(Object::toString).collect(Collectors.joining(", "));
		logger.debug("Mapping " + name + ": " + info);
	}

	@Override
	public Iterator<ServletContextInitializer> iterator() {
		return this.sortedList.iterator();
	}

	@Override
	public int size() {
		return this.sortedList.size();
	}

	/**
	 * Adapter to convert a given Bean type into a {@link RegistrationBean} (and hence a
	 * {@link ServletContextInitializer}).
	 *
	 * @param <T> the type of the Bean to adapt
	 */
	@FunctionalInterface
	protected interface RegistrationBeanAdapter<T> {

		RegistrationBean createRegistrationBean(String name, T source, int totalNumberOfSourceBeans);

	}

	/**
	 * {@link RegistrationBeanAdapter} for {@link Servlet} beans.
	 */
	private static class ServletRegistrationBeanAdapter implements RegistrationBeanAdapter<Servlet> {

		private final MultipartConfigElement multipartConfig;

		ServletRegistrationBeanAdapter(MultipartConfigElement multipartConfig) {
			this.multipartConfig = multipartConfig;
		}

		@Override
		public RegistrationBean createRegistrationBean(String name, Servlet source, int totalNumberOfSourceBeans) {
			String url = (totalNumberOfSourceBeans != 1) ? "/" + name + "/" : "/";
			if (name.equals(DISPATCHER_SERVLET_NAME)) {
				url = "/"; // always map the main dispatcherServlet to "/"
			}
			ServletRegistrationBean<Servlet> bean = new ServletRegistrationBean<>(source, url);
			bean.setName(name);
			bean.setMultipartConfig(this.multipartConfig);
			return bean;
		}

	}

	/**
	 * {@link RegistrationBeanAdapter} for {@link Filter} beans.
	 */
	private static class FilterRegistrationBeanAdapter implements RegistrationBeanAdapter<Filter> {

		@Override
		public RegistrationBean createRegistrationBean(String name, Filter source, int totalNumberOfSourceBeans) {
			FilterRegistrationBean<Filter> bean = new FilterRegistrationBean<>(source);
			bean.setName(name);
			return bean;
		}

	}

	/**
	 * {@link RegistrationBeanAdapter} for certain {@link EventListener} beans.
	 */
	private static class ServletListenerRegistrationBeanAdapter implements RegistrationBeanAdapter<EventListener> {

		@Override
		public RegistrationBean createRegistrationBean(String name, EventListener source,
				int totalNumberOfSourceBeans) {
			return new ServletListenerRegistrationBean<>(source);
		}

	}

}
