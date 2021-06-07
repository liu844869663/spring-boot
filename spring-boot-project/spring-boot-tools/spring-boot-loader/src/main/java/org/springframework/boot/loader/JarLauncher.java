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

package org.springframework.boot.loader;

import org.springframework.boot.loader.archive.Archive;

/**
 * {@link Launcher} for JAR based archives. This launcher assumes that dependency jars are
 * included inside a {@code /BOOT-INF/lib} directory and that application classes are
 * included inside a {@code /BOOT-INF/classes} directory.
 *
 * @author Phillip Webb
 * @author Andy Wilkinson
 * @since 1.0.0
 */
public class JarLauncher extends ExecutableArchiveLauncher {

	static final String BOOT_INF_CLASSES = "BOOT-INF/classes/";

	static final String BOOT_INF_LIB = "BOOT-INF/lib/";

	public JarLauncher() {
	}

	protected JarLauncher(Archive archive) {
		super(archive);
	}

	@Override
	protected boolean isNestedArchive(Archive.Entry entry) {
		// 只接受 `BOOT-INF/classes/` 目录
		if (entry.isDirectory()) {
			return entry.getName().equals(BOOT_INF_CLASSES);
		}
		// 只接受 `BOOT-INF/lib/` 目录下的 jar 包
		return entry.getName().startsWith(BOOT_INF_LIB);
	}

	/**
	 * 这里是 java -jar 启动 SpringBoot 打包后的 jar 包的入口
	 * 可查看 jar 包中的 META-INF/MANIFEST.MF 文件（该文件用于对 Java 应用进行配置）
	 * 参考 Oracle 官方对于 jar 的说明（https://docs.oracle.com/javase/8/docs/technotes/guides/jar/jar.html）
	 * 该文件其中会有一个配置项：Main-Class: org.springframework.boot.loader.JarLauncher
	 * 这个配置表示会调用 JarLauncher#main(String[]) 方法，也就当前方法
	 */
	public static void main(String[] args) throws Exception {
		// <1> 创建当前类的实例对象，会创建一个 Archive 对象（当前应用），可用于解析 jar 包（当前应用）中所有的信息
		// <2> 调用其 launch(String[]) 方法
		new JarLauncher().launch(args);
	}

}
