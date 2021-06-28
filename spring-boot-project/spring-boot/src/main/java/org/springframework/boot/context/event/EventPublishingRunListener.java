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

package org.springframework.boot.context.event;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringApplicationRunListener;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.context.event.SimpleApplicationEventMulticaster;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.util.ErrorHandler;

/**
 * {@link SpringApplicationRunListener} to publish {@link SpringApplicationEvent}s.
 * <p>
 * Uses an internal {@link ApplicationEventMulticaster} for the events that are fired
 * before the context is actually refreshed.
 *
 * @author Phillip Webb
 * @author Stephane Nicoll
 * @author Andy Wilkinson
 * @author Artsiom Yudovin
 * @since 1.0.0
 */
public class EventPublishingRunListener implements SpringApplicationRunListener, Ordered {

	/**
	 * Spring Boot 应用的启动类
	 */
	private final SpringApplication application;

	/**
	 * 启动类 `main(String[])` 方法的入参
	 */
	private final String[] args;

	/**
	 * 事件广播器，包含了所有的 `META-INF/spring.factories` 文件中配置的 {@link ApplicationListener} 监听器
	 */
	private final SimpleApplicationEventMulticaster initialMulticaster;

	public EventPublishingRunListener(SpringApplication application, String[] args) {
		this.application = application;
		this.args = args;
		this.initialMulticaster = new SimpleApplicationEventMulticaster();
		// 在实例化 SpringApplication 的过程中会从 `META-INF/spring.factories` 文件中获取 ApplicationListener 类型的类名称，并进行实例化
		// 这里会将他们添加至广播器中
		for (ApplicationListener<?> listener : application.getListeners()) {
			this.initialMulticaster.addApplicationListener(listener);
		}
	}

	@Override
	public int getOrder() {
		return 0;
	}

	@Override
	public void starting() {
		// 广播 Spring Boot 应用正在启动事件
		this.initialMulticaster.multicastEvent(new ApplicationStartingEvent(this.application, this.args));
	}

	@Override
	public void environmentPrepared(ConfigurableEnvironment environment) {
		// 广播 Spring Boot 应用的 Environment 环境已准备事件
		this.initialMulticaster
				.multicastEvent(new ApplicationEnvironmentPreparedEvent(this.application, this.args, environment));
	}

	@Override
	public void contextPrepared(ConfigurableApplicationContext context) {
		// 广播 Spring Boot 应用的 Spring 上下文已初始化事件
		this.initialMulticaster
				.multicastEvent(new ApplicationContextInitializedEvent(this.application, this.args, context));
	}

	@Override
	public void contextLoaded(ConfigurableApplicationContext context) {
		// 将所有的 ApplicationListener 添加至 Spring 应用上下文
		for (ApplicationListener<?> listener : this.application.getListeners()) {
			if (listener instanceof ApplicationContextAware) {
				((ApplicationContextAware) listener).setApplicationContext(context);
			}
			context.addApplicationListener(listener);
		}
		// 广播 Spring Boot 应用的 Spring 上下文已准备事件
		this.initialMulticaster.multicastEvent(new ApplicationPreparedEvent(this.application, this.args, context));
	}

	@Override
	public void started(ConfigurableApplicationContext context) {
		// 广播 Spring Boot 应用的 Spring 上下文已启动事件
		context.publishEvent(new ApplicationStartedEvent(this.application, this.args, context));
	}

	@Override
	public void running(ConfigurableApplicationContext context) {
		// 广播 Spring Boot 应用的 Spring 上下文准备就绪事件
		context.publishEvent(new ApplicationReadyEvent(this.application, this.args, context));
	}

	@Override
	public void failed(ConfigurableApplicationContext context, Throwable exception) {
		ApplicationFailedEvent event = new ApplicationFailedEvent(this.application, this.args, context, exception);
		if (context != null && context.isActive()) {
			// Listeners have been registered to the application context so we should
			// use it at this point if we can
			context.publishEvent(event);
		}
		else {
			// An inactive context may not have a multicaster so we use our multicaster to
			// call all of the context's listeners instead
			if (context instanceof AbstractApplicationContext) {
				for (ApplicationListener<?> listener : ((AbstractApplicationContext) context)
						.getApplicationListeners()) {
					this.initialMulticaster.addApplicationListener(listener);
				}
			}
			this.initialMulticaster.setErrorHandler(new LoggingErrorHandler());
			this.initialMulticaster.multicastEvent(event);
		}
	}

	private static class LoggingErrorHandler implements ErrorHandler {

		private static final Log logger = LogFactory.getLog(EventPublishingRunListener.class);

		@Override
		public void handleError(Throwable throwable) {
			logger.warn("Error calling ApplicationEventListener", throwable);
		}

	}

}
