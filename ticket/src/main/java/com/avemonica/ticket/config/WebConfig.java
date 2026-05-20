package com.avemonica.ticket.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${avemonica.upload.base-path}")
    private String basePath;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 映射 /uploads/** 到总根目录，这样下面的 /avatar/、/poster/ 都能自动被访问到
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:" + basePath + "/");
    }
}