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

package org.springframework.boot.autoconfigure.condition;

import java.util.Map;

import org.springframework.boot.autoconfigure.AutoConfigurationImportFilter;
import org.springframework.boot.autoconfigure.AutoConfigurationMetadata;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.boot.web.reactive.context.ConfigurableReactiveWebEnvironment;
import org.springframework.boot.web.reactive.context.ReactiveWebApplicationContext;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.ClassUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.web.context.ConfigurableWebEnvironment;
import org.springframework.web.context.WebApplicationContext;

/**
 * {@link Condition} that checks for the presence or absence of
 * {@link WebApplicationContext}.
 *
 * @author Dave Syer
 * @author Phillip Webb
 * @see ConditionalOnWebApplication
 * @see ConditionalOnNotWebApplication
 */
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
class OnWebApplicationCondition extends FilteringSpringBootCondition {

	private static final String SERVLET_WEB_APPLICATION_CLASS = "org.springframework.web.context.support.GenericWebApplicationContext";

	private static final String REACTIVE_WEB_APPLICATION_CLASS = "org.springframework.web.reactive.HandlerResult";

	/**
	 * 该方法来自 {@link AutoConfigurationImportFilter} 判断这些自动配置类是否符合条件（`@ConditionalOnWebApplication`）
	 * 也是判断指定的 Web 引用类型是否有对应的 Class 类对象
	 */
	@Override
	protected ConditionOutcome[] getOutcomes(String[] autoConfigurationClasses,
			AutoConfigurationMetadata autoConfigurationMetadata) {
		ConditionOutcome[] outcomes = new ConditionOutcome[autoConfigurationClasses.length];
		/*
		 * 遍历这些自动配置类，依次进行处理
		 */
		for (int i = 0; i < outcomes.length; i++) {
			String autoConfigurationClass = autoConfigurationClasses[i];
			if (autoConfigurationClass != null) {
				// 先获取这个自动配置类的 `@ConditionalOnWebApplication` 注解的属性
				// 然后根据指定的 Web 引用类型判断是否存在所需的 Class 类对象
				outcomes[i] = getOutcome(autoConfigurationMetadata.get(autoConfigurationClass, "ConditionalOnWebApplication"));
			}
		}
		return outcomes;
	}

	private ConditionOutcome getOutcome(String type) {
		// <1> `@ConditionalOnWebApplication` 注解没有指定类型，默认匹配通过
		if (type == null) {
			return null;
		}
		// <2> 创建一个匹配信息构建器 `message`
		ConditionMessage.Builder message = ConditionMessage.forCondition(ConditionalOnWebApplication.class);
		// <3> 如果指定需要 **SERVLET** 类型
		if (ConditionalOnWebApplication.Type.SERVLET.name().equals(type)) {
			// 那么判断是否存在 `org.springframework.web.context.support.GenericWebApplicationContext` 这个类
			if (!ClassNameFilter.isPresent(SERVLET_WEB_APPLICATION_CLASS, getBeanClassLoader())) {
				// 不存在，则返回不匹配
				return ConditionOutcome.noMatch(message.didNotFind("servlet web application classes").atAll());
			}
		}
		// <3> 如果指定需要 **REACTIVE** 类型
		if (ConditionalOnWebApplication.Type.REACTIVE.name().equals(type)) {
			// 那么判断是否存在 `org.springframework.web.reactive.HandlerResult` 这个类
			if (!ClassNameFilter.isPresent(REACTIVE_WEB_APPLICATION_CLASS, getBeanClassLoader())) {
				// 不存在，则返回不匹配
				return ConditionOutcome.noMatch(message.didNotFind("reactive web application classes").atAll());
			}
		}
		// <4> 到这里，表示哪种 Web 引用类型都可以，那么判断上面 **SERVLET** 和 **REACTIVE** 所需要的 Class 对象是否都不存在
		if (!ClassNameFilter.isPresent(SERVLET_WEB_APPLICATION_CLASS, getBeanClassLoader())
				&& !ClassUtils.isPresent(REACTIVE_WEB_APPLICATION_CLASS, getBeanClassLoader())) {
			// 都不存在，则返回不匹配
			return ConditionOutcome.noMatch(message.didNotFind("reactive or servlet web application classes").atAll());
		}
		// <5> 默认匹配成功
		return null;
	}

	/**
	 * 该方法来自 {@link SpringBootCondition} 判断某个 Bean 是否符合注入条件（`@ConditionalOnWebApplication`）
	 */
	@Override
	public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
		// 这个 Bean 是否标注了 `@ConditionalOnWebApplication` 注解
		boolean required = metadata.isAnnotated(ConditionalOnWebApplication.class.getName());
		// 和上面逻辑差不多，进行匹配
		ConditionOutcome outcome = isWebApplication(context, metadata, required);
		if (required && !outcome.isMatch()) {
			return ConditionOutcome.noMatch(outcome.getConditionMessage());
		}
		if (!required && outcome.isMatch()) {
			return ConditionOutcome.noMatch(outcome.getConditionMessage());
		}
		return ConditionOutcome.match(outcome.getConditionMessage());
	}

	private ConditionOutcome isWebApplication(ConditionContext context, AnnotatedTypeMetadata metadata,
			boolean required) {
		// 根据指定的 Web 引用类型进行处理，默认为 **ANY**
		switch (deduceType(metadata)) {
		case SERVLET:
			return isServletWebApplication(context);
		case REACTIVE:
			return isReactiveWebApplication(context);
		default:
			return isAnyWebApplication(context, required);
		}
	}

	private ConditionOutcome isAnyWebApplication(ConditionContext context, boolean required) {
		ConditionMessage.Builder message = ConditionMessage.forCondition(ConditionalOnWebApplication.class,
				required ? "(required)" : "");
		ConditionOutcome servletOutcome = isServletWebApplication(context);
		if (servletOutcome.isMatch() && required) {
			return new ConditionOutcome(servletOutcome.isMatch(), message.because(servletOutcome.getMessage()));
		}
		ConditionOutcome reactiveOutcome = isReactiveWebApplication(context);
		if (reactiveOutcome.isMatch() && required) {
			return new ConditionOutcome(reactiveOutcome.isMatch(), message.because(reactiveOutcome.getMessage()));
		}
		return new ConditionOutcome(servletOutcome.isMatch() || reactiveOutcome.isMatch(),
				message.because(servletOutcome.getMessage()).append("and").append(reactiveOutcome.getMessage()));
	}

	private ConditionOutcome isServletWebApplication(ConditionContext context) {
		ConditionMessage.Builder message = ConditionMessage.forCondition("");
		if (!ClassNameFilter.isPresent(SERVLET_WEB_APPLICATION_CLASS, context.getClassLoader())) {
			return ConditionOutcome.noMatch(message.didNotFind("servlet web application classes").atAll());
		}
		if (context.getBeanFactory() != null) {
			String[] scopes = context.getBeanFactory().getRegisteredScopeNames();
			if (ObjectUtils.containsElement(scopes, "session")) {
				return ConditionOutcome.match(message.foundExactly("'session' scope"));
			}
		}
		if (context.getEnvironment() instanceof ConfigurableWebEnvironment) {
			return ConditionOutcome.match(message.foundExactly("ConfigurableWebEnvironment"));
		}
		if (context.getResourceLoader() instanceof WebApplicationContext) {
			return ConditionOutcome.match(message.foundExactly("WebApplicationContext"));
		}
		return ConditionOutcome.noMatch(message.because("not a servlet web application"));
	}

	private ConditionOutcome isReactiveWebApplication(ConditionContext context) {
		ConditionMessage.Builder message = ConditionMessage.forCondition("");
		if (!ClassNameFilter.isPresent(REACTIVE_WEB_APPLICATION_CLASS, context.getClassLoader())) {
			return ConditionOutcome.noMatch(message.didNotFind("reactive web application classes").atAll());
		}
		if (context.getEnvironment() instanceof ConfigurableReactiveWebEnvironment) {
			return ConditionOutcome.match(message.foundExactly("ConfigurableReactiveWebEnvironment"));
		}
		if (context.getResourceLoader() instanceof ReactiveWebApplicationContext) {
			return ConditionOutcome.match(message.foundExactly("ReactiveWebApplicationContext"));
		}
		return ConditionOutcome.noMatch(message.because("not a reactive web application"));
	}

	private Type deduceType(AnnotatedTypeMetadata metadata) {
		Map<String, Object> attributes = metadata.getAnnotationAttributes(ConditionalOnWebApplication.class.getName());
		if (attributes != null) {
			return (Type) attributes.get("type");
		}
		return Type.ANY;
	}

}
