package com.qinghuan.workbench.config;

import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.net.Proxy;

/**
 * Spring AI 出站 HTTP 定制：30s 超时 + 显式无代理。
 * Spring AI 的 OpenAI 自动装配使用 Spring Boot 自动配置的 RestClient.Builder，
 * 本项目内仅 AI 调用使用该 Builder，无其他受影响方。
 */
@Configuration
public class AiRestClientConfig {

    private static final int TIMEOUT_MS = 30_000;

    @Bean
    RestClientCustomizer aiRestClientCustomizer() {
        return builder -> {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(TIMEOUT_MS);
            factory.setReadTimeout(TIMEOUT_MS);
            // 避免系统代理干扰 localhost 调试；直连外部 AI 服务
            factory.setProxy(Proxy.NO_PROXY);
            builder.requestFactory(factory);
        };
    }
}
