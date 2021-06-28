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

package org.springframework.boot.autoconfigureprocessor;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.tools.FileObject;
import javax.tools.StandardLocation;

/**
 * Annotation processor to store certain annotations from auto-configuration classes in a
 * property file.
 *
 * @author Madhura Bhave
 * @author Phillip Webb
 * @since 1.5.0
 */
@SupportedAnnotationTypes({ "org.springframework.boot.autoconfigure.condition.ConditionalOnClass",
		"org.springframework.boot.autoconfigure.condition.ConditionalOnBean",
		"org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate",
		"org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication",
		"org.springframework.boot.autoconfigure.AutoConfigureBefore",
		"org.springframework.boot.autoconfigure.AutoConfigureAfter",
		"org.springframework.boot.autoconfigure.AutoConfigureOrder" })
public class AutoConfigureAnnotationProcessor extends AbstractProcessor {

	/**
	 * 生成的文件
	 */
	protected static final String PROPERTIES_PATH = "META-INF/spring-autoconfigure-metadata.properties";
	/**
	 * 保存指定注解的简称和注解全称之间的对应关系（不可修改）
	 */
	private final Map<String, String> annotations;

	private final Map<String, ValueExtractor> valueExtractors;

	private final Properties properties = new Properties();

	public AutoConfigureAnnotationProcessor() {
		// <1> 创建一个 Map 集合
		Map<String, String> annotations = new LinkedHashMap<>();
		// <1.1> 将指定注解的简称和全称之间的对应关系保存至第 `1` 步创建的 Map 中
		addAnnotations(annotations);
		// <1.2> 将 `1.1` 的 Map 转换成不可修改的 UnmodifiableMap 集合，赋值给 `annotations`
		this.annotations = Collections.unmodifiableMap(annotations);
		// <2> 创建一个 Map 集合
		Map<String, ValueExtractor> valueExtractors = new LinkedHashMap<>();
		// <2.1> 将指定注解的简称和对应的 ValueExtractor 对象保存至第 `2` 步创建的 Map 中
		addValueExtractors(valueExtractors);
		// <2.2> 将 `2.1` 的 Map 转换成不可修改的 UnmodifiableMap 集合，赋值给 `valueExtractors`
		this.valueExtractors = Collections.unmodifiableMap(valueExtractors);
	}

	protected void addAnnotations(Map<String, String> annotations) {
		annotations.put("ConditionalOnClass", "org.springframework.boot.autoconfigure.condition.ConditionalOnClass");
		annotations.put("ConditionalOnBean", "org.springframework.boot.autoconfigure.condition.ConditionalOnBean");
		annotations.put("ConditionalOnSingleCandidate", "org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate");
		annotations.put("ConditionalOnWebApplication", "org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication");
		annotations.put("AutoConfigureBefore", "org.springframework.boot.autoconfigure.AutoConfigureBefore");
		annotations.put("AutoConfigureAfter", "org.springframework.boot.autoconfigure.AutoConfigureAfter");
		annotations.put("AutoConfigureOrder", "org.springframework.boot.autoconfigure.AutoConfigureOrder");
	}

	private void addValueExtractors(Map<String, ValueExtractor> attributes) {
		attributes.put("ConditionalOnClass", new OnClassConditionValueExtractor());
		attributes.put("ConditionalOnBean", new OnBeanConditionValueExtractor());
		attributes.put("ConditionalOnSingleCandidate", new OnBeanConditionValueExtractor());
		attributes.put("ConditionalOnWebApplication", ValueExtractor.allFrom("type"));
		attributes.put("AutoConfigureBefore", ValueExtractor.allFrom("value", "name"));
		attributes.put("AutoConfigureAfter", ValueExtractor.allFrom("value", "name"));
		attributes.put("AutoConfigureOrder", ValueExtractor.allFrom("value"));
	}

	@Override
	public SourceVersion getSupportedSourceVersion() {
		// 返回 Java 版本，默认 1.5
		return SourceVersion.latestSupported();
	}

	@Override
	public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
		// <1> 遍历上面的几个 `@Conditional` 注解和几个定义自动配置类顺序的注解，依次进行处理
		for (Map.Entry<String, String> entry : this.annotations.entrySet()) {
			// <1.1> 对支持的注解进行处理，也就是找到所有标注了该注解的类，然后解析出该注解的值，保存至 Properties
			// 例如 `类名.注解简称` => `注解中的值(逗号分隔)` 和 `类名` => `空字符串`，将自动配置类的信息已经对应注解的信息都保存起来
			// 避免你每次启动 Spring Boot 应用都要去解析自动配置类上面的注解，是引入 `spring-boot-autoconfigure` 后可以从 `META-INF/spring-autoconfigure-metadata.properties` 文件中直接获取
			// 这么一想，Spring Boot 设计的太棒了，所以你自己写的 Spring Boot Starter 中的自动配置模块也可以引入这个 Spring Boot 提供的插件
			process(roundEnv, entry.getKey(), entry.getValue());
		}
		// <2> 如果处理完成
		if (roundEnv.processingOver()) {
			try {
				// <2.1> 将 Properties 写入 `META-INF/spring-autoconfigure-metadata.properties` 文件
				writeProperties();
			}
			catch (Exception ex) {
				throw new IllegalStateException("Failed to write metadata", ex);
			}
		}
		// <3> 返回 `false`
		return false;
	}

	private void process(RoundEnvironment roundEnv, String propertyKey, String annotationName) {
		// <1> 获取到这个注解名称对应的 Java 类型
		TypeElement annotationType = this.processingEnv.getElementUtils().getTypeElement(annotationName);
		if (annotationType != null) {
			// <2> 如果存在该注解，则从 RoundEnvironment 中获取标注了该注解的所有 Element 元素，进行遍历
			for (Element element : roundEnv.getElementsAnnotatedWith(annotationType)) {
				// <2.1> 获取这个 Element 元素 innermost 最深处的 Element
				Element enclosingElement = element.getEnclosingElement();
				// <2.2> 如果最深处的 Element 的类型是 PACKAGE 包，那么表示这个元素是一个类，则进行处理
				if (enclosingElement != null && enclosingElement.getKind() == ElementKind.PACKAGE) {
					// <2.2.1> 解析这个类上面 `annotationName` 注解的信息，并保存至 `properties` 中
					processElement(element, propertyKey, annotationName);
				}
			}
		}
	}

	private void processElement(Element element, String propertyKey, String annotationName) {
		try {
			// <1> 获取这个类的名称
			String qualifiedName = Elements.getQualifiedName(element);
			// <2> 获取这个类上面的 `annotationName` 类型的注解信息
			AnnotationMirror annotation = getAnnotation(element, annotationName);
			if (qualifiedName != null && annotation != null) {
				// <3> 获取这个注解中的值
				List<Object> values = getValues(propertyKey, annotation);
				// <4> 往 `properties` 中添加 `类名.注解简称` => `注解中的值(逗号分隔)`
				this.properties.put(qualifiedName + "." + propertyKey, toCommaDelimitedString(values));
				// <5> 往 `properties` 中添加 `类名` => `空字符串`
				this.properties.put(qualifiedName, "");
			}
		}
		catch (Exception ex) {
			throw new IllegalStateException("Error processing configuration meta-data on " + element, ex);
		}
	}

	private AnnotationMirror getAnnotation(Element element, String type) {
		if (element != null) {
			for (AnnotationMirror annotation : element.getAnnotationMirrors()) {
				if (type.equals(annotation.getAnnotationType().toString())) {
					return annotation;
				}
			}
		}
		return null;
	}

	private String toCommaDelimitedString(List<Object> list) {
		StringBuilder result = new StringBuilder();
		for (Object item : list) {
			result.append((result.length() != 0) ? "," : "");
			result.append(item);
		}
		return result.toString();
	}

	private List<Object> getValues(String propertyKey, AnnotationMirror annotation) {
		// 获取该注解对应的 value 抽取器
		ValueExtractor extractor = this.valueExtractors.get(propertyKey);
		if (extractor == null) {
			return Collections.emptyList();
		}
		// 获取这个注解中的值，并返回
		return extractor.getValues(annotation);
	}

	private void writeProperties() throws IOException {
		if (!this.properties.isEmpty()) {
			FileObject file = this.processingEnv.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "", PROPERTIES_PATH);
			try (OutputStream outputStream = file.openOutputStream()) {
				this.properties.store(outputStream, null);
			}
		}
	}

	@FunctionalInterface
	private interface ValueExtractor {

		List<Object> getValues(AnnotationMirror annotation);

		static ValueExtractor allFrom(String... names) {
			return new NamedValuesExtractor(names);
		}

	}

	private abstract static class AbstractValueExtractor implements ValueExtractor {

		@SuppressWarnings("unchecked")
		protected Stream<Object> extractValues(AnnotationValue annotationValue) {
			if (annotationValue == null) {
				return Stream.empty();
			}
			Object value = annotationValue.getValue();
			if (value instanceof List) {
				return ((List<AnnotationValue>) value).stream()
						.map((annotation) -> extractValue(annotation.getValue()));
			}
			return Stream.of(extractValue(value));
		}

		private Object extractValue(Object value) {
			if (value instanceof DeclaredType) {
				return Elements.getQualifiedName(((DeclaredType) value).asElement());
			}
			return value;
		}

	}

	private static class NamedValuesExtractor extends AbstractValueExtractor {

		private final Set<String> names;

		NamedValuesExtractor(String... names) {
			this.names = new HashSet<>(Arrays.asList(names));
		}

		@Override
		public List<Object> getValues(AnnotationMirror annotation) {
			List<Object> result = new ArrayList<>();
			annotation.getElementValues().forEach((key, value) -> {
				if (this.names.contains(key.getSimpleName().toString())) {
					extractValues(value).forEach(result::add);
				}
			});
			return result;
		}

	}

	private static class OnBeanConditionValueExtractor extends AbstractValueExtractor {

		@Override
		public List<Object> getValues(AnnotationMirror annotation) {
			Map<String, AnnotationValue> attributes = new LinkedHashMap<>();
			annotation.getElementValues()
					.forEach((key, value) -> attributes.put(key.getSimpleName().toString(), value));
			if (attributes.containsKey("name")) {
				return Collections.emptyList();
			}
			List<Object> result = new ArrayList<>();
			extractValues(attributes.get("value")).forEach(result::add);
			extractValues(attributes.get("type")).forEach(result::add);
			return result;
		}

	}

	private static class OnClassConditionValueExtractor extends NamedValuesExtractor {

		OnClassConditionValueExtractor() {
			super("value", "name");
		}

		@Override
		public List<Object> getValues(AnnotationMirror annotation) {
			List<Object> values = super.getValues(annotation);
			values.sort(this::compare);
			return values;
		}

		private int compare(Object o1, Object o2) {
			return Comparator.comparing(this::isSpringClass).thenComparing(String.CASE_INSENSITIVE_ORDER)
					.compare(o1.toString(), o2.toString());
		}

		private boolean isSpringClass(String type) {
			return type.startsWith("org.springframework");
		}

	}

}
