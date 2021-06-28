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

package org.springframework.boot.web.servlet.support;

import java.util.Collections;

import javax.servlet.*;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.builder.ParentContextApplicationContextInitializer;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.boot.web.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.util.Assert;
import org.springframework.web.WebApplicationInitializer;
import org.springframework.web.context.ConfigurableWebEnvironment;
import org.springframework.web.context.ContextLoaderListener;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;

/**
 * 这个类目的是支持你将 Spring Boot 应用打包成 war 包放入外部的 Servlet 容器中运行
 * 原理就是借助 Servlet 3.0 新特性中新增的 {@link ServletContainerInitializer} 接口
 * 跳到 spring-web 包下面的 `META-INF.services/javax.servlet.ServletContainerInitializer` 文件中，里面配置了 {@link org.springframework.web.SpringServletContainerInitializer}
 * 也就是说在外部的 Servlet 容器启动时会调用这个类的 `onStartup(..)` 方法，然后它会找到你配置的 SpringBootServletInitializer，也就是 `this`，调用其 `onStartup(..)` 方法
 *
 * An opinionated {@link WebApplicationInitializer} to run a {@link SpringApplication}
 * from a traditional WAR deployment. Binds {@link Servlet}, {@link Filter} and
 * {@link ServletContextInitializer} beans from the application context to the server.
 * <p>
 * To configure the application either override the
 * {@link #configure(SpringApplicationBuilder)} method (calling
 * {@link SpringApplicationBuilder#sources(Class...)}) or make the initializer itself a
 * {@code @Configuration}. If you are using {@link SpringBootServletInitializer} in
 * combination with other {@link WebApplicationInitializer WebApplicationInitializers} you
 * might also want to add an {@code @Ordered} annotation to configure a specific startup
 * order.
 * <p>
 * Note that a WebApplicationInitializer is only needed if you are building a war file and
 * deploying it. If you prefer to run an embedded web server then you won't need this at
 * all.
 *
 * @author Dave Syer
 * @author Phillip Webb
 * @author Andy Wilkinson
 * @since 2.0.0
 * @see #configure(SpringApplicationBuilder)
 */
public abstract class SpringBootServletInitializer implements WebApplicationInitializer {

	protected Log logger; // Don't initialize early

	private boolean registerErrorPageFilter = true;

	/**
	 * Set if the {@link ErrorPageFilter} should be registered. Set to {@code false} if
	 * error page mappings should be handled via the server and not Spring Boot.
	 * @param registerErrorPageFilter if the {@link ErrorPageFilter} should be registered.
	 */
	protected final void setRegisterErrorPageFilter(boolean registerErrorPageFilter) {
		this.registerErrorPageFilter = registerErrorPageFilter;
	}

	@Override
	public void onStartup(ServletContext servletContext) throws ServletException {
		// Logger initialization is deferred in case an ordered
		// LogServletContextInitializer is being used
		this.logger = LogFactory.getLog(getClass());
		// <1> 创建一个 WebApplicationContext 作为 Root Spring 应用上下文
		WebApplicationContext rootAppContext = createRootApplicationContext(servletContext);
		if (rootAppContext != null) {
			// <2> 添加一个 ContextLoaderListener 监听器，会监听到 ServletContext 的启动事件
			// 因为 Spring 应用上下文在上面第 `1` 步已经准备好了，所以这里什么都不用做
			servletContext.addListener(new ContextLoaderListener(rootAppContext) {
				@Override
				public void contextInitialized(ServletContextEvent event) {
					// no-op because the application context is already initialized
				}
			});
		}
		else {
			this.logger.debug("No ContextLoaderListener registered, as createRootApplicationContext() did not "
					+ "return an application context");
		}
	}

	protected WebApplicationContext createRootApplicationContext(ServletContext servletContext) {
		// <1> 创建一个 SpringApplication 构造器
		SpringApplicationBuilder builder = createSpringApplicationBuilder();
		// <2> 设置 `mainApplicationClass`，主要用于打印日志
		builder.main(getClass());
		// <3> 从 ServletContext 上下文中获取最顶部的 Root ApplicationContext 应用上下文
		ApplicationContext parent = getExistingRootWebApplicationContext(servletContext);
		// <4> 如果已存在 Root ApplicationContext，则先置空，因为这里会创建一个 ApplicationContext 作为 Root
		if (parent != null) {
			this.logger.info("Root context already created (using as parent).");
			servletContext.setAttribute(WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE, null);
			// <4.1> 添加一个 ApplicationContextInitializer 初始器，
			// 用于设置现在要创建的 Root ApplicationContext 应用上下文的父容器为 `parent`
			builder.initializers(new ParentContextApplicationContextInitializer(parent));
		}
		/**
		 * <5> 添加一个 ApplicationContextInitializer 初始器
		 * 目的是往 ServletContext 上下文中设置 Root ApplicationContext 为现在要创建的 Root ApplicationContext 应用上下文
		 * 并将这个 ServletContext 保存至 ApplicationContext 中，参考 {@link ServletWebServerApplicationContext#createWebServer()} 方法，
		 * 如果获取到了 ServletContext 那么直接调用其 {@link ServletWebServerApplicationContext#selfInitialize} 方法来注册各个 Servlet、Filter
		 * 例如 {@link DispatcherServlet}
 		 */
		builder.initializers(new ServletContextApplicationContextInitializer(servletContext));
		// <6> 设置要创建的 Root ApplicationContext 应用上下文的类型（Servlet）
		builder.contextClass(AnnotationConfigServletWebServerApplicationContext.class);
		// <7> 对 SpringApplicationBuilder 进行扩展
		builder = configure(builder);
		// <8> 添加一个 ApplicationListener 监听器
		// 用于将 ServletContext 中的相关属性关联到 Environment 环境中
		builder.listeners(new WebEnvironmentPropertySourceInitializer(servletContext));
		// <9> 构建一个 SpringApplication 对象，用于启动 Spring 应用
		SpringApplication application = builder.build();
		// <10> 如果没有设置 `source` 源对象，那么这里尝试设置为当前 Class 对象，需要有 `@Configuration` 注解
		if (application.getAllSources().isEmpty()
				&& MergedAnnotations.from(getClass(), SearchStrategy.TYPE_HIERARCHY).isPresent(Configuration.class)) {
			application.addPrimarySources(Collections.singleton(getClass()));
		}
		// <11> 因为 SpringApplication 在创建 ApplicationContext 应用上下文的过程中需要优先注册 `source` 源对象，如果为空则抛出异常
		Assert.state(!application.getAllSources().isEmpty(),
				"No SpringApplication sources have been defined. Either override the "
						+ "configure method or add an @Configuration annotation");
		// Ensure error pages are registered
		if (this.registerErrorPageFilter) {
			// <12> 添加一个错误页面 Filter 作为 `sources`
			application.addPrimarySources(Collections.singleton(ErrorPageFilterConfiguration.class));
		}
		// <13> 调用 `application` 的 `run` 方法启动整个 Spring Boot 应用
		return run(application);
	}

	/**
	 * Returns the {@code SpringApplicationBuilder} that is used to configure and create
	 * the {@link SpringApplication}. The default implementation returns a new
	 * {@code SpringApplicationBuilder} in its default state.
	 * @return the {@code SpringApplicationBuilder}.
	 * @since 1.3.0
	 */
	protected SpringApplicationBuilder createSpringApplicationBuilder() {
		return new SpringApplicationBuilder();
	}

	/**
	 * Called to run a fully configured {@link SpringApplication}.
	 * @param application the application to run
	 * @return the {@link WebApplicationContext}
	 */
	protected WebApplicationContext run(SpringApplication application) {
		return (WebApplicationContext) application.run();
	}

	private ApplicationContext getExistingRootWebApplicationContext(ServletContext servletContext) {
		Object context = servletContext.getAttribute(WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE);
		if (context instanceof ApplicationContext) {
			return (ApplicationContext) context;
		}
		return null;
	}

	/**
	 * Configure the application. Normally all you would need to do is to add sources
	 * (e.g. config classes) because other settings have sensible defaults. You might
	 * choose (for instance) to add default command line arguments, or set an active
	 * Spring profile.
	 * @param builder a builder for the application context
	 * @return the application builder
	 * @see SpringApplicationBuilder
	 */
	protected SpringApplicationBuilder configure(SpringApplicationBuilder builder) {
		return builder;
	}

	private static final class WebEnvironmentPropertySourceInitializer
			implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {

		private final ServletContext servletContext;

		private WebEnvironmentPropertySourceInitializer(ServletContext servletContext) {
			this.servletContext = servletContext;
		}

		@Override
		public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
			ConfigurableEnvironment environment = event.getEnvironment();
			if (environment instanceof ConfigurableWebEnvironment) {
				// 将 ServletContext 的一些初始化参数关联到当前 Spring 应用的 Environment 环境中
				((ConfigurableWebEnvironment) environment).initPropertySources(this.servletContext, null);
			}
		}

		@Override
		public int getOrder() {
			return Ordered.HIGHEST_PRECEDENCE;
		}

	}

}
