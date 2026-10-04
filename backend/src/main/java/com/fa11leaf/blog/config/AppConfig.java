package com.fa11leaf.blog.config;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.client.RestClient;

/**
 * 把自定义配置类登记为 Bean，并提供几个基础设施 Bean。
 *
 * <p>也可以在主类上写 @ConfigurationPropertiesScan，但显式登记的好处是：
 * 想知道"我们有哪几个配置类"，只需要看这一个文件。
 */
@Configuration
@EnableConfigurationProperties({
        PythonProperties.class,
        AdminProperties.class,
        GitHubProperties.class,
        MediaProperties.class
})
public class AppConfig {

    /**
     * 共享的 RestClient 构造器。
     *
     * <p><b>为什么要自己声明：</b>Boot 3.x 会自动装配一个 {@code RestClient.Builder}，
     * 但 Boot 4 把自动配置按技术拆成了独立模块，本项目只引了
     * {@code spring-boot-starter-webmvc}，拿不到它。实测启动时报：
     * {@code NoSuchBeanDefinitionException: No qualifying bean of type
     * 'org.springframework.web.client.RestClient$Builder'}。
     * 与其去猜该补哪个模块，不如自己声明一个 —— 依赖更少，行为也更明确。
     *
     * <p><b>为什么必须把 HTTP 版本钉成 1.1：</b>Spring 的默认请求工厂是 JDK HttpClient，
     * 而它默认先试 HTTP/2 —— 对 {@code http://} 地址会先发一个 h2c 升级请求。
     * uvicorn 不支持升级，日志里会出现 {@code Unsupported upgrade request}，
     * 并且在回退到 HTTP/1.1 的那条路径上<b>请求体会被丢掉</b>：
     * 表现是 Python 端报 {@code 422 {"loc":["body"],"msg":"Field required"}}，
     * 也就是"后端明明发出去了、对方却说没收到 body"。
     * 找到这个原因花了两轮，所以这里把结论写在代码旁边。
     *
     * <p>顺便把超时也定下来：不设的话某个下游卡住会一直占着调用线程。
     *
     * <p><b>为什么是 prototype：</b>Builder 是有状态的，baseUrl、默认请求头都是可变配置。
     * 多个组件共享同一个实例会互相污染，所以每次注入都新建一个。
     */
    @Bean
    @Scope("prototype")
    public RestClient.Builder restClientBuilder() {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        // 读超时给 60 秒：发布时要连 GitHub 好几次，其中上传图片最慢
        factory.setReadTimeout(Duration.ofSeconds(60));

        return RestClient.builder().requestFactory(factory);
    }

    /**
     * 口令摘要算法。
     *
     * <p>BCrypt 自带随机盐并把盐写进摘要本身，因此同一个口令每次哈希结果都不同，
     * 也就不需要单独维护 salt 列。强度参数默认 10，本机单用户场景足够。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
