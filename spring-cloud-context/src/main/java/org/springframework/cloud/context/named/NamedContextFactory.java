/*
 * Copyright 2012-2024 the original author or authors.
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

package org.springframework.cloud.context.named;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.aot.AotDetector;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.AnnotationConfigRegistry;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.ResolvableType;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.Assert;

/**
 * Creates a set of child contexts that allows a set of Specifications to define the beans
 * in each child context. Ported from spring-cloud-netflix FeignClientFactory and
 * SpringClientFactory
 *
 * <pre>
 *   针对不同的服务客户端（如多个 Feign 客户端、不同的数据源客户端等）提供独立的 ApplicationContext 上下文环境。
 *   每个客户端的上下文可以包含该客户端特有的 Bean、配置、属性等，从而实现配置隔离和依赖管理的独立性
 *
 * 	在 Spring Cloud 中，尤其是在使用 NamedContextFactory 这种框架时，
 * 	每个 Feign 客户端、Ribbon 客户端等可以拥有自己的上下文，便于针对不同的远程服务或资源做定制化配置。
 * 	这些上下文共享父级应用上下文中的通用配置，同时允许为每个客户端定义独立的配置
 *
 * 	在微服务环境中，一个应用可能会调用多个不同的远程服务。
 * 	例如，一个应用可能既需要调用用户服务（user-service），也需要调用订单服务（order-service）。每个服务都可以通过一个 Feign 客户端来调用。
 * 	不同的远程服务可能有不同的超时设置、重试策略、负载均衡策略等。这就需要为每个客户端提供独立的上下文
 *
 * 不同客户端的上下文能够带来以下好处：
 * 		配置隔离：每个客户端可以有独立的配置，例如不同的超时设置、拦截器、编码解码器等。
 * 		Bean 隔离：每个客户端的上下文可以有不同的 Bean 定义，防止客户端之间的 Bean 依赖冲突。
 * 		灵活性和可扩展性：在运行时根据不同的客户端需求加载不同的配置，使应用更加灵活。
 * 		多实例支持：在同一应用中可以针对不同的服务使用多个 Feign 客户端或 Ribbon 客户端，且每个客户端可以有自己独立的上下文。
 *
 * 	TODO Spring Cloud OpenFeign 使用 NamedContextFactory 来管理多个 Feign 客户端的上下文
 * 	  eg:
 *        @FeignClient(name = "user-service", url = "http://user-service.com", configuration = UserServiceFeignConfig.class)
 * 		public interface UserServiceClient {
 *            @GetMapping("/users/{id}")
 *            User getUserById(@PathVariable("id") Long id);
 *        }
 *         @FeignClient(name = "order-service", url = "http://order-service.com", configuration = OrderServiceFeignConfig.class)
 * 		 public interface OrderServiceClient {
 *             @GetMapping("/orders/{id}")
 *             Order getOrderById(@PathVariable("id") Long id);
 *         }
 * 		这个例子中，UserServiceClient 和 OrderServiceClient 分别是两个不同的 Feign 客户端，调用不同的远程服务（用户服务和订单服务）。每个客户端都有自己的配置类：
 * 		UserServiceFeignConfig.class：用于 user-service 的客户端配置。
 * 		OrderServiceFeignConfig.class：用于 order-service 的客户端配置
 *
 * 	通过 NamedContextFactory，Spring 会为 user-service 和 order-service 创建两个独立的上下文，每个上下文都有各自的配置环境。
 * 	这些独立的上下文可以确保每个客户端都有自己的 ApplicationContext，从而避免相互干扰
 * </pre>
 *
 * @param <C> specification
 * @author Spencer Gibb
 * @author Dave Syer
 * @author Tommy Karlsson
 * @author Olga Maciaszek-Sharma
 */
public abstract class NamedContextFactory<C extends NamedContextFactory.Specification>
		implements DisposableBean, ApplicationContextAware {

	private final Map<String, ApplicationContextInitializer<GenericApplicationContext>> applicationContextInitializers;
	// 定义属性源名称，用于配置上下文中的环境变量
	// 每个上下文环境会创建一个 PropertySource，将该属性源与 propertyName 和 name 关联，存储到上下文的环境中。
	// 在上下文的环境中，可以通过 propertySourceName 来识别和获取特定配置，从而实现对不同命名上下文的独立配置管理
	private final String propertySourceName;
	// 定义属性名称，用于指定服务的名称 用于在每个 PropertySource 中存储服务的名称。
	// 在创建上下文时，propertyName 与 propertySourceName 共同用于标识上下文的配置项。例如，对于 Feign 客户端，这个属性可以存储客户端的服务名称
	// 在需要根据服务名称、ID 等信息对上下文进行隔离时，可以借助 propertyName 将上下文配置与特定名称关联
	private final String propertyName;
	// 维护命名上下文实例的映射表，每个上下文名称对应一个 GenericApplicationContext
	// 用于存储并管理所有已创建的上下文实例，实现不同服务或客户端的隔离
	private final Map<String, GenericApplicationContext> contexts = new ConcurrentHashMap<>();
	// 存储命名上下文的配置信息，每个上下文名称对应一个 Specification 配置实例
	// Specification 中包含了上下文的名称和配置类，configurations 存储了每个上下文的独立配置，从而在上下文构建时能够加载对应的配置
	private final Map<String, C> configurations = new ConcurrentHashMap<>();
	// 父级应用上下文，用于共享 Spring 容器中的 Bean 和配置
	// 微服务环境中，父级上下文可以包含公共的基础配置和 Bean，供每个命名上下文共享使用。
	// 例如，在 Feign 客户端中，公共的 HTTP 配置可以放在父上下文中，而各客户端上下文则有各自的服务配置
	private ApplicationContext parent;
	// 指定默认配置的类型，用于所有创建的上下文
	// 如果在上下文配置中没有找到特定的配置类，会使用 defaultConfigType 来提供默认的配置，从而确保上下文的完整性
	// 例如，在 FeignClient 配置中，默认的编码器、解码器、拦截器等可以通过 defaultConfigType 来注册到上下文中
	private final Class<?> defaultConfigType;

	public NamedContextFactory(Class<?> defaultConfigType, String propertySourceName, String propertyName) {
		this(defaultConfigType, propertySourceName, propertyName, new HashMap<>());
	}

	public NamedContextFactory(Class<?> defaultConfigType, String propertySourceName, String propertyName,
							   Map<String, ApplicationContextInitializer<GenericApplicationContext>> applicationContextInitializers) {
		this.defaultConfigType = defaultConfigType;
		this.propertySourceName = propertySourceName;
		this.propertyName = propertyName;
		this.applicationContextInitializers = applicationContextInitializers;
	}

	@Override
	public void setApplicationContext(ApplicationContext parent) throws BeansException {
		this.parent = parent;
	}

	public ApplicationContext getParent() {
		return parent;
	}

	public void setConfigurations(List<C> configurations) {
		for (C client : configurations) {
			this.configurations.put(client.getName(), client);
		}
	}

	public Set<String> getContextNames() {
		return new HashSet<>(this.contexts.keySet());
	}

	@Override
	public void destroy() {
		Collection<GenericApplicationContext> values = this.contexts.values();
		for (GenericApplicationContext context : values) {
			// This can fail, but it never throws an exception (you see stack traces
			// logged as WARN).
			context.close();
		}
		this.contexts.clear();
	}

	/**
	 * 为指定名称的客户端获取或创建一个独立的 GenericApplicationContext 上下文
	 *
	 * @param name
	 * @return
	 */
	protected GenericApplicationContext getContext(String name) {
		if (!this.contexts.containsKey(name)) {  // 如果 `contexts` 映射表中不包含该名称的上下文
			synchronized (this.contexts) { // 加锁 `contexts` 映射表，确保线程安全
				if (!this.contexts.containsKey(name)) { // 双重检查模式，确保上下文在同步块中未被创建
					// 调用 `createContext` 方法创建新的上下文，并放入 `contexts` 映射表中
					this.contexts.put(name, createContext(name));
				}
			}
		}
		return this.contexts.get(name);
	}

	/**
	 * 创建指定名称的 `GenericApplicationContext` 实例
	 *
	 * @param name
	 * @return
	 */
	public GenericApplicationContext createContext(String name) {
		// 调用 `buildContext` 方法，构建一个新的上下文实例，并为其配置基础信息
		GenericApplicationContext context = buildContext(name);
		// there's an AOT initializer for this context
		// 检查 `applicationContextInitializers` 中是否存在指定名称的初始化器
		if (applicationContextInitializers.get(name) != null) {
			// 如果初始化器存在，调用 `initialize` 方法对上下文进行初始化配置
			applicationContextInitializers.get(name).initialize(context);
			// 刷新上下文，完成 Bean 定义和初始化过程
			context.refresh();
			return context;
		}
		// 如果没有初始化器，则调用 `registerBeans` 方法为上下文注册默认的 Bean 定义
		registerBeans(name, context);
		// 刷新上下文，完成 Bean 初始化
		context.refresh();
		return context;
	}

	/**
	 * 用于将特定名称的 Bean 配置注册到给定的 GenericApplicationContext 中
	 *
	 * @param name
	 * @param context
	 */
	public void registerBeans(String name, GenericApplicationContext context) {
		Assert.isInstanceOf(AnnotationConfigRegistry.class, context);

		// 将 `context` 转换为 `AnnotationConfigRegistry` 类型，以便可以动态注册配置类
		AnnotationConfigRegistry registry = (AnnotationConfigRegistry) context;

		// 检查 `configurations` 映射中是否存在该名称的配置
		if (this.configurations.containsKey(name)) {
			// 获取配置类数组，并逐个注册到 `registry` 中
			for (Class<?> configuration : this.configurations.get(name).getConfiguration()) {
				registry.register(configuration);
			}
		}
		// 遍历 `configurations` 映射中的每个条目
		for (Map.Entry<String, C> entry : this.configurations.entrySet()) {
			if (entry.getKey().startsWith("default.")) { // 检查配置名称是否以 "default." 开头，以确定这是默认配置
				for (Class<?> configuration : entry.getValue().getConfiguration()) {
					// 注册每个默认配置的配置类
					registry.register(configuration);
				}
			}
		}
		// 注册 `PropertyPlaceholderAutoConfiguration` 和 `defaultConfigType` 到上下文
		// 这些类用于提供占位符解析和默认配置
		registry.register(PropertyPlaceholderAutoConfiguration.class, this.defaultConfigType);
	}

	/**
	 * 用于构建新的 GenericApplicationContext 实例，并配置其基本属性，如类加载器、父上下文、属性源等
	 *
	 * @param name
	 * @return
	 */
	public GenericApplicationContext buildContext(String name) {
		// https://github.com/spring-cloud/spring-cloud-netflix/issues/3101
		// https://github.com/spring-cloud/spring-cloud-openfeign/issues/475
		// 获取当前类的类加载器，以便在新上下文中使用相同的类加载器
		ClassLoader classLoader = getClass().getClassLoader();
		// 声明一个 `GenericApplicationContext` 变量，用于存储新建的上下文实例
		GenericApplicationContext context;
		if (this.parent != null) {// 如果存在父上下文
			// 创建 `DefaultListableBeanFactory` 实例，作为新上下文的 Bean 工厂
			DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();

			// 如果父上下文是 `ConfigurableApplicationContext` 实例，则使用其 Bean 工厂的类加载器
			if (parent instanceof ConfigurableApplicationContext) {
				beanFactory.setBeanClassLoader(
						((ConfigurableApplicationContext) parent).getBeanFactory().getBeanClassLoader());
			} else {// 否则，使用当前类的类加载器
				beanFactory.setBeanClassLoader(classLoader);
			}
			// 根据 AOT（提前生成的工件）是否存在，选择 `GenericApplicationContext` 或 `AnnotationConfigApplicationContext`
			context = AotDetector.useGeneratedArtifacts() ? new GenericApplicationContext(beanFactory)
					: new AnnotationConfigApplicationContext(beanFactory);
		} else {
			// 如果没有父上下文，根据是否有 AOT 生成的工件创建上下文
			context = AotDetector.useGeneratedArtifacts() ? new GenericApplicationContext()
					: new AnnotationConfigApplicationContext();
		}

		// 设置新上下文的类加载器
		context.setClassLoader(classLoader);

		// 获取上下文的环境配置，并将新的属性源添加到环境的首位
		// 新属性源包含 `propertySourceName` 和 `propertyName` 以及 `name` 映射的值
		context.getEnvironment()
				.getPropertySources()
				.addFirst(
						new MapPropertySource(this.propertySourceName, Collections.singletonMap(this.propertyName, name)));

		// 如果有父上下文，将其设置为新上下文的父级，以共享父级环境和 Bean
		if (this.parent != null) {
			// Uses Environment from parent as well as beans
			context.setParent(this.parent);
		}
		// 设置上下文的显示名称，通常为类名加上上下文名称
		context.setDisplayName(generateDisplayName(name));

		// 返回构建并配置完成的上下文
		return context;
	}

	protected String generateDisplayName(String name) {
		return this.getClass().getSimpleName() + "-" + name;
	}

	/**
	 * 指定上下文中查找单个指定类型的 Bean 实例。如果未找到该类型的 Bean，返回 null
	 *
	 * @param name
	 * @param type
	 * @param <T>
	 * @return
	 */
	public <T> T getInstance(String name, Class<T> type) {
		// 调用 `getContext` 方法，根据名称获取或创建一个 `GenericApplicationContext` 实例、
		// TODO 进入
		GenericApplicationContext context = getContext(name);
		try {
			// 调用 `context.getBean(type)` 方法，尝试获取指定类型的 Bean
			// 如果找到，则返回该 Bean 实例
			return context.getBean(type);
		} catch (NoSuchBeanDefinitionException e) {
			// ignore
		}
		// 如果未找到指定类型的 Bean，返回 `null`
		return null;
	}

	public <T> ObjectProvider<T> getLazyProvider(String name, Class<T> type) {
		return new ClientFactoryObjectProvider<>(this, name, type);
	}

	public <T> ObjectProvider<T> getProvider(String name, Class<T> type) {
		GenericApplicationContext context = getContext(name);
		return context.getBeanProvider(type);
	}

	/**
	 * 泛型方法 `getInstance`，用于获取指定上下文中的泛型 Bean 实例
	 * <pre>
	 *    Step 1: 定义泛型 Bean
	 *		public interface Service<T> {
	 *     		T performAction();
	 * 		}
	 *
	 * 		public class Client {}
	 *
	 * 		public class ClientService implements Service<Client> {
	 * 		    @Override
	 * 		    public Client performAction() {
	 * 		        return new Client();
	 * 		    }
	 * 		}
	 *    Step 2: 将 ClientService 注册到上下文中
	 *		在 order-service 上下文中注册 ClientService 作为一个 Bean。
	 *		@Configuration
	 * 		public class OrderServiceConfig {
	 * 		    @Bean
	 * 		    public Service<Client> clientService() {
	 * 		        return new ClientService();
	 * 		    }
	 * 		}
	 *     Step 3: 使用 getInstance 方法获取 Bean 实例
	 *     		// 获取 order-service 上下文中的 Service<Client> Bean 实例
	 * 			Service<Client> clientService = namedContextFactory.getInstance("order-service", Service.class, Client.class);
	 * </pre>
	 * @param name 表示上下文的名称
	 * @param clazz 表示目标 Bean 的基础类
	 * @param generics 表示泛型参数类型
	 * @return
	 * @param <T>
	 */
	public <T> T getInstance(String name, Class<?> clazz, Class<?>... generics) {
		// 使用 `ResolvableType.forClassWithGenerics` 创建带泛型的 `ResolvableType` 实例
		// 这样可以将基础类和泛型类型信息组合成一个完整的类型
		ResolvableType type = ResolvableType.forClassWithGenerics(clazz, generics);
		// 调用 `getInstance(name, type)` 重载方法，传入生成的 `ResolvableType`
		// 返回指定泛型类型的 Bean 实例
		return getInstance(name, type);
	}

	/**
	 * 泛型方法 `getInstance`，用于根据 `ResolvableType` 获取指定上下文中的 Bean 实例
	 * @param name 上下文的名称
	 * @param type 目标 Bean 的 `ResolvableType`
	 * @return
	 * @param <T>
	 */
	@SuppressWarnings("unchecked")
	public <T> T getInstance(String name, ResolvableType type) {
		// 调用 `getContext` 方法，获取或创建指定名称的 `GenericApplicationContext`
		GenericApplicationContext context = getContext(name);
		// 使用 `BeanFactoryUtils.beanNamesForTypeIncludingAncestors` 获取上下文及其父上下文中所有符合 `type` 类型的 Bean 名称
		String[] beanNames = BeanFactoryUtils.beanNamesForTypeIncludingAncestors(context, type);
		for (String beanName : beanNames) { // 遍历所有匹配类型的 Bean 名称
			if (context.isTypeMatch(beanName, type)) {  // 检查当前 Bean 名称是否与指定 `ResolvableType` 匹配
				return (T) context.getBean(beanName); // 如果匹配，则获取该 Bean 实例并返回
			}
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	public <T> T getAnnotatedInstance(String name, ResolvableType type, Class<? extends Annotation> annotationType) {
		GenericApplicationContext context = getContext(name);
		String[] beanNames = BeanFactoryUtils.beanNamesForAnnotationIncludingAncestors(context, annotationType);

		List<T> beans = new ArrayList<>();
		for (String beanName : beanNames) {
			if (context.isTypeMatch(beanName, type)) {
				beans.add((T) context.getBean(beanName));
			}
		}
		if (beans.size() > 1) {
			throw new IllegalStateException("Only one annotated bean for type expected.");
		}
		return beans.isEmpty() ? null : beans.get(0);
	}

	/**
	 * 指定上下文中获取指定类型的所有 Bean，并返回一个 Map，其中包含 Bean 的名称和实例
	 *
	 * @param name 表示上下文的名称
	 * @param type 表示目标 Bean 的类型
	 * @param <T>
	 * @return
	 */
	public <T> Map<String, T> getInstances(String name, Class<T> type) {
		// 调用 `getContext` 方法，根据名称获取或创建一个 `GenericApplicationContext` 实例
		// TODO 进入
		GenericApplicationContext context = getContext(name);
		// 调用 `BeanFactoryUtils.beansOfTypeIncludingAncestors` 方法，
		// 查找上下文及其父上下文中类型为 `type` 的所有 Bean
		// 返回一个包含 Bean 名称和实例的 `Map`
		return BeanFactoryUtils.beansOfTypeIncludingAncestors(context, type);
	}

	public Map<String, C> getConfigurations() {
		return configurations;
	}

	/**
	 * Specification with name and configuration.
	 */
	public interface Specification {
		// 上下文的名称，通常是客户端名称（例如服务名或客户端的标识）
		String getName();

		// 获取 该上下文关联的配置类数组
		Class<?>[] getConfiguration();

	}

}
