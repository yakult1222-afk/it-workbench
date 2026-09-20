package com.qinghuan.workbench.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

/**
 * 一体化部署模式（可选）：前端构建产物放入 classpath:/static 后，
 * 由后端单端口同时提供页面与 API；未命中静态资源的前端路由回退到 index.html。
 * 前后端分离部署时该配置不产生任何影响。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    private final Resource index = new ClassPathResource("static/index.html");

                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        Resource requested = location.createRelative(resourcePath);
                        if (requested.exists() && requested.isReadable()) {
                            return requested;
                        }
                        // 前端路由（如 /tasks）回退到 index.html；/api 等接口路径不处理
                        if (resourcePath.startsWith("api/") || !index.exists()) {
                            return null;
                        }
                        return index;
                    }
                });
    }
}
