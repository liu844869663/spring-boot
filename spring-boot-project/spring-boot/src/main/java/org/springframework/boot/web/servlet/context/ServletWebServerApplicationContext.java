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

package org.springframework.boot.web.servlet.context;

import org.apache.catalina.startup.Tomcat;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.Scope;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.web.context.ConfigurableWebServerApplicationContext;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.servlet.*;
import org.springframework.boot.web.servlet.server.ServletWebServerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextException;
import org.springframework.core.io.Resource;
import org.springframework.util.StringUtils;
import org.springframework.web.context.ContextLoaderListener;
import org.springframework.web.context.ServletContextAware;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.*;
import org.springframework.web.servlet.DispatcherServlet;

import javax.servlet.*;
import java.util.*;

/**
 * A {@link WebApplicationContext} that can be used to bootstrap itself from a contained
 * {@link ServletWebServerFactory} bean.
 * <p>
 * This context will create, initialize and run an {@link WebServer} by searching for a
 * single {@link ServletWebServerFactory} bean within the {@link ApplicationContext}
 * itself. The {@link ServletWebServerFactory} is free to use standard Spring concepts
 * (such as dependency injection, lifecycle callbacks and property placeholder variables).
 * <p>
 * In addition, any {@link Servlet} or {@link Filter} beans defined in the context will be
 * automatically registered with the web server. In the case of a single Servlet bean, the
 * '/' mapping will be used. If multiple Servlet beans are found then the lowercase bean
 * name will be used as a mapping prefix. Any Servlet named 'dispatcherServlet' will
 * always be mapped to '/'. Filter beans will be mapped to all URLs ('/*').
 * <p>
 * For more advanced configuration, the context can instead define beans that implement
 * the {@link ServletContextInitializer} interface (most often
 * {@link ServletRegistrationBean}s and/or {@link FilterRegistrationBean}s). To prevent
 * double registration, the use of {@link ServletContextInitializer} beans will disable
 * automatic Servlet and Filter bean registration.
 * <p>
 * Although this context can be used directly, most developers should consider using the
 * {@link AnnotationConfigServletWebServerApplicationContext} or
 * {@link XmlServletWebServerApplicationContext} variants.
 *
 * @author Phillip Webb
 * @author Dave Syer
 * @author Scott Frederick
 * @since 2.0.0
 * @see AnnotationConfigServletWebServerApplicationContext
 * @see XmlServletWebServerApplicationContext
 * @see ServletWebServerFactory
 */
public class ServletWebServerApplicationContext extends GenericWebApplicationContext
		implements ConfigurableWebServerApplicationContext {

	private static final Log logger = LogFactory.getLog(ServletWebServerApplicationContext.class);

	/**
	 * Constant value for the DispatcherServlet bean name. A Servlet bean with this name
	 * is deemed to be the "main" servlet and is automatically given a mapping of "/" by
	 * default. To change the default behavior you can use a
	 * {@link ServletRegistrationBean} or a different bean name.
	 */
	public static final String DISPATCHER_SERVLET_NAME = "dispatcherServlet";

	private volatile WebServer webServer;

	private ServletConfig servletConfig;

	private String serverNamespace;

	/**
	 * Create a new {@link ServletWebServerApplicationContext}.
	 */
	public ServletWebServerApplicationContext() {
	}

	/**
	 * Create a new {@link ServletWebServerApplicationContext} with the given
	 * {@code DefaultListableBeanFactory}.
	 * @param beanFactory the DefaultListableBeanFactory instance to use for this context
	 */
	public ServletWebServerApplicationContext(DefaultListableBeanFactory beanFactory) {
		super(beanFactory);
	}

	/**
	 * Register ServletContextAwareProcessor.
	 * @see ServletContextAwareProcessor
	 */
	@Override
	protected void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
		beanFactory.addBeanPostProcessor(new WebApplicationContextServletContextAwareProcessor(this));
		beanFactory.ignoreDependencyInterface(ServletContextAware.class);
		registerWebApplicationScopes();
	}

	@Override
	public final void refresh() throws BeansException, IllegalStateException {
		try {
			super.refresh();
		}
		catch (RuntimeException ex) {
			stopAndReleaseWebServer();
			throw ex;
		}
	}

	@Override
	protected void onRefresh() {
		// 调用父类方法，初始化 ThemeSource 对象
		super.onRefresh();
		try {
			/**
			 * 创建一个 WebServer 服务（默认 Tomcat），并初始化 ServletContext 上下文
			 * 会先创建一个 {@link Tomcat} 容器并启动，同时会注册各种 Servlet
			 * 例如 借助 {@link org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration}
			 * 注册 {@link DispatcherServlet} 对象到 ServletContext 上下文，这样就可以通过 Spring MVC 的核心组件来实现一个 Web 应用
 			 */
			createWebServer();
		}
		catch (Throwable ex) {
			throw new ApplicationContextException("Unable to start web server", ex);
		}
	}

	@Override
	protected void finishRefresh() {
		// 调用父类方法，会发布 ContextRefreshedEvent 上下文刷新事件
		super.finishRefresh();
		/**
		 * 启动上面 {@link #onRefresh } 创建的 WebServer，上面仅启动 {@link Tomcat} 容器，Servlet 添加到了 ServletContext 上下文中
		 * 这里启动 {@link TomcatWebServer} 容器对象，对每一个 TomcatEmbeddedContext 中的 Servlet 进行加载并初始化
 		 */
		WebServer webServer = startWebServer();
		if (webServer != null) {
			publishEvent(new ServletWebServerInitializedEvent(webServer, this));
		}
	}

	@Override
	protected void onClose() {
		super.onClose();
		stopAndReleaseWebServer();
	}

	private void createWebServer() {
		// <1> 获取当前 `WebServer` 容器对象，首次进来为空
		WebServer webServer = this.webServer;
		// <2> 获取 `ServletContext` 上下文对象
		ServletContext servletContext = getServletContext();
		// <3> 如果 WebServer 和 ServletContext 都为空，则需要创建一个
		// 使用 Spring Boot 内嵌 Tomcat 容器则会进入该分支
		if (webServer == null && servletContext == null) {
			// <3.1> 获取 Servlet 容器工厂对象（默认为 Tomcat）`factory`
			ServletWebServerFactory factory = getWebServerFactory();
			/**
			 * <3.2> 先创建一个 {@link ServletContextInitializer} Servlet 上下文初始器，实现也就是当前类的 {@link this#selfInitialize(ServletContext)} 方法
			 * 至于为什么不用 Servlet 3.0 新增的 {@link javax.servlet.ServletContainerInitializer} 这个类，我在
			 * [精尽Spring MVC源码分析 - 寻找遗失的 web.xml](https://www.cnblogs.com/lifullmoon/p/14122704.html)有提到过
			 *
			 * <3.3> 从 `factory` 工厂中创建一个 WebServer 容器对象
			 * 例如创建一个 {@link TomcatWebServer} 容器对象，并初始化 `ServletContext` 上下文，创建 {@link Tomcat} 容器并启动
			 * 启动过程异步触发了 {@link org.springframework.boot.web.embedded.tomcat.TomcatStarter#onStartup} 方法
			 * 也就会调用这个传入的 {@link ServletContextInitializer} 的 {@link #selfInitialize(ServletContext)} 方法
			 */
			this.webServer = factory.getWebServer(getSelfInitializer());
		}
		// <4> 否则，如果 ServletContext 不为空，说明使用了外部的 Servlet 容器（例如 Tomcat）
		else if (servletContext != null) {
			try {
				/** 那么这里主动调用 {@link this#selfInitialize(ServletContext)} 方法来注册各种 Servlet、Filter */
				getSelfInitializer().onStartup(servletContext);
			}
			catch (ServletException ex) {
				throw new ApplicationContextException("Cannot initialize servlet context", ex);
			}
		}
		// <5> 将 ServletContext 的一些初始化参数关联到当前 Spring 应用的 Environment 环境中
		initPropertySources();
	}

	/**
	 * Returns the {@link ServletWebServerFactory} that should be used to create the
	 * embedded {@link WebServer}. By default this method searches for a suitable bean in
	 * the context itself.
	 * @return a {@link ServletWebServerFactory} (never {@code null})
	 */
	protected ServletWebServerFactory getWebServerFactory() {
		// Use bean names so that we don't consider the hierarchy
		// 获取当前 BeanFactory 中类型为 ServletWebServerFactory 的 Bean 的名称，不考虑层次性
		// 必须存在一个，否则抛出异常
		// 所以想要切换 Servlet 容器得引入对应的 Starter 模块并排除 `spring-boot-starter-web` 中默认的 `tomcat` Starter 模块
		String[] beanNames = getBeanFactory().getBeanNamesForType(ServletWebServerFactory.class);
		if (beanNames.length == 0) {
			throw new ApplicationContextException("Unable to start ServletWebServerApplicationContext due to missing "
					+ "ServletWebServerFactory bean.");
		}
		if (beanNames.length > 1) {
			throw new ApplicationContextException("Unable to start ServletWebServerApplicationContext due to multiple "
					+ "ServletWebServerFactory beans : " + StringUtils.arrayToCommaDelimitedString(beanNames));
		}
		// 获取这个 ServletWebServerFactory 对象
		return getBeanFactory().getBean(beanNames[0], ServletWebServerFactory.class);
	}

	/**
	 * Returns the {@link ServletContextInitializer} that will be used to complete the
	 * setup of this {@link WebApplicationContext}.
	 * @return the self initializer
	 * @see #prepareWebApplicationContext(ServletContext)
	 */
	private org.springframework.boot.web.servlet.ServletContextInitializer getSelfInitializer() {
		return this::selfInitialize;
	}

	private void selfInitialize(ServletContext servletContext) throws ServletException {
		// <1> 将当前 Spring 应用上下文设置到 ServletContext 上下文的属性中
		// 同时将 ServletContext 上下文设置到 Spring 应用上下文中
		prepareWebApplicationContext(servletContext);
		// <2> 向 Spring 应用上下文注册一个 ServletContextScope 对象（ServletContext 的封装）
		registerApplicationScope(servletContext);
		// <3> 向 Spring 应用上下文注册 `contextParameters` 和 `contextAttributes` 属性（会先被封装成 Map）
		WebApplicationContextUtils.registerEnvironmentBeans(getBeanFactory(), servletContext);
		/**
		 * <4> 【重点】先从 Spring 应用上下文找到所有的 {@link ServletContextInitializer}
		 * 也就会找到各种 {@link RegistrationBean}，然后依次调用他们的 `onStartup` 方法，向 ServletContext 上下文注册 Servlet、Filter 和 EventListener
		 * 例如 {@link DispatcherServletAutoConfiguration} 中的 {@link DispatcherServletRegistrationBean} 就会注册 {@link DispatcherServlet} 对象
		 * 这也就是我们熟知的 Spring MVC 的核心组件，关于它可参考我的 [精尽Spring MVC源码分析 - 文章导读](https://www.cnblogs.com/lifullmoon/p/14123963.html) 文章
		 * 所以这里执行完了，也就启动了 Tomcat，同时注册了所有的 Servlet，那么 Web 应用准备就绪了
		 */
		for (ServletContextInitializer beans : getServletContextInitializerBeans()) {
			beans.onStartup(servletContext);
		}
	}

	private void registerApplicationScope(ServletContext servletContext) {
		ServletContextScope appScope = new ServletContextScope(servletContext);
		getBeanFactory().registerScope(WebApplicationContext.SCOPE_APPLICATION, appScope);
		// Register as ServletContext attribute, for ContextCleanupListener to detect it.
		servletContext.setAttribute(ServletContextScope.class.getName(), appScope);
	}

	private void registerWebApplicationScopes() {
		ExistingWebApplicationScopes existingScopes = new ExistingWebApplicationScopes(getBeanFactory());
		WebApplicationContextUtils.registerWebApplicationScopes(getBeanFactory());
		existingScopes.restore();
	}

	/**
	 * Returns {@link ServletContextInitializer}s that should be used with the embedded
	 * web server. By default this method will first attempt to find
	 * {@link ServletContextInitializer}, {@link Servlet}, {@link Filter} and certain
	 * {@link EventListener} beans.
	 * @return the servlet initializer beans
	 */
	protected Collection<ServletContextInitializer> getServletContextInitializerBeans() {
		return new ServletContextInitializerBeans(getBeanFactory());
	}

	/**
	 * Prepare the {@link WebApplicationContext} with the given fully loaded
	 * {@link ServletContext}. This method is usually called from
	 * {@link ServletContextInitializer#onStartup(ServletContext)} and is similar to the
	 * functionality usually provided by a {@link ContextLoaderListener}.
	 * @param servletContext the operational servlet context
	 */
	protected void prepareWebApplicationContext(ServletContext servletContext) {
		Object rootContext = servletContext.getAttribute(WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE);
		if (rootContext != null) {
			if (rootContext == this) {
				throw new IllegalStateException(
						"Cannot initialize context because there is already a root application context present - "
								+ "check whether you have multiple ServletContextInitializers!");
			}
			return;
		}
		servletContext.log("Initializing Spring embedded WebApplicationContext");
		try {
			servletContext.setAttribute(WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE, this);
			if (logger.isDebugEnabled()) {
				logger.debug("Published root WebApplicationContext as ServletContext attribute with name ["
						+ WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE + "]");
			}
			setServletContext(servletContext);
			if (logger.isInfoEnabled()) {
				long elapsedTime = System.currentTimeMillis() - getStartupDate();
				logger.info("Root WebApplicationContext: initialization completed in " + elapsedTime + " ms");
			}
		}
		catch (RuntimeException | Error ex) {
			logger.error("Context initialization failed", ex);
			servletContext.setAttribute(WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE, ex);
			throw ex;
		}
	}

	private WebServer startWebServer() {
		WebServer webServer = this.webServer;
		if (webServer != null) {
			webServer.start();
		}
		return webServer;
	}

	private void stopAndReleaseWebServer() {
		WebServer webServer = this.webServer;
		if (webServer != null) {
			try {
				webServer.stop();
				this.webServer = null;
			}
			catch (Exception ex) {
				throw new IllegalStateException(ex);
			}
		}
	}

	@Override
	protected Resource getResourceByPath(String path) {
		if (getServletContext() == null) {
			return new ClassPathContextResource(path, getClassLoader());
		}
		return new ServletContextResource(getServletContext(), path);
	}

	@Override
	public String getServerNamespace() {
		return this.serverNamespace;
	}

	@Override
	public void setServerNamespace(String serverNamespace) {
		this.serverNamespace = serverNamespace;
	}

	@Override
	public void setServletConfig(ServletConfig servletConfig) {
		this.servletConfig = servletConfig;
	}

	@Override
	public ServletConfig getServletConfig() {
		return this.servletConfig;
	}

	/**
	 * Returns the {@link WebServer} that was created by the context or {@code null} if
	 * the server has not yet been created.
	 * @return the embedded web server
	 */
	@Override
	public WebServer getWebServer() {
		return this.webServer;
	}

	/**
	 * Utility class to store and restore any user defined scopes. This allow scopes to be
	 * registered in an ApplicationContextInitializer in the same way as they would in a
	 * classic non-embedded web application context.
	 */
	public static class ExistingWebApplicationScopes {

		private static final Set<String> SCOPES;

		static {
			Set<String> scopes = new LinkedHashSet<>();
			scopes.add(WebApplicationContext.SCOPE_REQUEST);
			scopes.add(WebApplicationContext.SCOPE_SESSION);
			SCOPES = Collections.unmodifiableSet(scopes);
		}

		private final ConfigurableListableBeanFactory beanFactory;

		private final Map<String, Scope> scopes = new HashMap<>();

		public ExistingWebApplicationScopes(ConfigurableListableBeanFactory beanFactory) {
			this.beanFactory = beanFactory;
			for (String scopeName : SCOPES) {
				Scope scope = beanFactory.getRegisteredScope(scopeName);
				if (scope != null) {
					this.scopes.put(scopeName, scope);
				}
			}
		}

		public void restore() {
			this.scopes.forEach((key, value) -> {
				if (logger.isInfoEnabled()) {
					logger.info("Restoring user defined scope " + key);
				}
				this.beanFactory.registerScope(key, value);
			});
		}

	}

}
