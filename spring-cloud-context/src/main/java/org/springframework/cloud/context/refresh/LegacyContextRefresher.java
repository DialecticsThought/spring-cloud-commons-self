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

package org.springframework.cloud.context.refresh;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.cloud.autoconfigure.RefreshAutoConfiguration;
import org.springframework.cloud.bootstrap.BootstrapApplicationListener;
import org.springframework.cloud.bootstrap.BootstrapConfigFileApplicationListener;
import org.springframework.cloud.context.scope.refresh.RefreshScope;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;

import static org.springframework.cloud.util.PropertyUtils.BOOTSTRAP_ENABLED_PROPERTY;

/**
 * @author Dave Syer
 * @author Venil Noronha
 */
public class LegacyContextRefresher extends ContextRefresher {

	@Deprecated
	public LegacyContextRefresher(ConfigurableApplicationContext context, RefreshScope scope) {
		super(context, scope);
	}

	public LegacyContextRefresher(ConfigurableApplicationContext context, RefreshScope scope,
			RefreshAutoConfiguration.RefreshProperties properties) {
		super(context, scope, properties);
	}

	@Override
	protected void updateEnvironment() {
		// TODO 进入
		addConfigFilesToEnvironment();
	}

	/* For testing. */
	ConfigurableApplicationContext addConfigFilesToEnvironment() {
		// 定义一个变量 capture，它用来保存临时启动的 Spring 应用上下文，用于捕获配置
		ConfigurableApplicationContext capture = null;
		try {
			// 这里是创建了一个新的 StandardEnvironment 对象，它是当前应用上下文的环境的拷贝，后面会在这个拷贝上做一些临时的操作
			StandardEnvironment environment = copyEnvironment(getContext().getEnvironment());

			Map<String, Object> map = new HashMap<>();
			// 禁用 JMX
			map.put("spring.jmx.enabled", false);
			// 清空启动源
			map.put("spring.main.sources", "");
			// gh-678 without this apps with this property set to REACTIVE or SERVLET fail
			// TODO 设置 Web 应用类型为 NONE（无 Web 环境）
			map.put("spring.main.web-application-type", "NONE");
			// 启用 bootstrap（BOOTSTRAP_ENABLED_PROPERTY）
			map.put(BOOTSTRAP_ENABLED_PROPERTY, Boolean.TRUE.toString());
			// 将这些参数作为一个新的 PropertySource 加入到 environment 的属性源列表中，位于列表的最前面（addFirst）
			environment.getPropertySources().addFirst(new MapPropertySource(REFRESH_ARGS_PROPERTY_SOURCE, map));
			// 通过 SpringApplicationBuilder 创建一个临时的 Spring 应用。
			// 使用 Empty.class 作为源类，
			// 不展示 banner，
			// 设置 Web 应用类型为 NONE（即没有 Web 服务器启动）。
			// 使用刚刚创建的 environment 作为这个应用的环境
			SpringApplicationBuilder builder = new SpringApplicationBuilder(Empty.class).bannerMode(Banner.Mode.OFF)
				.web(WebApplicationType.NONE)
				.environment(environment);
			// Just the listeners that affect the environment (e.g. excluding logging
			// listener because it has side effects)
			// 为这个临时应用设置监听器，
			// 主要是用于加载配置的监听器（BootstrapApplicationListener 和 BootstrapConfigFileApplicationListener），
			// 这些监听器负责影响环境配置
			builder.application()
				.setListeners(Arrays.asList(new BootstrapApplicationListener(),
						new BootstrapConfigFileApplicationListener()));
			// 通过 builder.run() 启动这个临时应用，并将其应用上下文保存到 capture 变量中
			capture = builder.run();
			// 检查 environment 是否包含之前添加的临时 REFRESH_ARGS_PROPERTY_SOURCE，如果有则移除它。因为这个参数是临时使用的
			if (environment.getPropertySources().contains(REFRESH_ARGS_PROPERTY_SOURCE)) {
				environment.getPropertySources().remove(REFRESH_ARGS_PROPERTY_SOURCE);
			}
			// 获取当前应用上下文的 PropertySources，即目标环境属性源
			MutablePropertySources target = getContext().getEnvironment().getPropertySources();
			String targetName = null;
			/**
			 * <pre>
			 *   TODO
			 *    遍历临时环境中的所有 PropertySource，对于不在 standardSources 列表中的配置源，
			 *    按照一定的逻辑添加或替换到当前应用的 PropertySources 中：
			 *    如果目标环境已经包含某个配置源，则使用新的 PropertySource 替换旧的。
			 *    如果目标环境不包含这个配置源，则根据之前的顺序插入到合适的位置，确保配置源顺序一致
			 * </pre>
			 */
			for (PropertySource<?> source : environment.getPropertySources()) {
				String name = source.getName();
				if (target.contains(name)) {
					targetName = name;
				}
				if (!this.standardSources.contains(name)) {
					if (target.contains(name)) {
						target.replace(name, source);
					}
					else {
						if (targetName != null) {
							target.addAfter(targetName, source);
							// update targetName to preserve ordering
							targetName = name;
						}
						else {
							// targetName was null so we are at the start of the list
							target.addFirst(source);
							targetName = name;
						}
					}
				}
			}
		}
		finally {
			/**
			 * 最终处理：在 finally 块中关闭临时创建的应用上下文，以释放资源。它通过检查应用上下文的父上下文，逐层关闭所有可能关联的上下文
			 */
			ConfigurableApplicationContext closeable = capture;
			while (closeable != null) {
				try {
					closeable.close();
				}
				catch (Exception e) {
					// Ignore;
				}
				if (closeable.getParent() instanceof ConfigurableApplicationContext) {
					closeable = (ConfigurableApplicationContext) closeable.getParent();
				}
				else {
					break;
				}
			}
		}
		// 最后返回临时启动的 capture 上下文
		return capture;
	}

}
