package com.avemonica.ticket.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class LocalCacheConfig {

    @Bean("eventLocalCache")
    public Cache<String, String> eventLocalCache() {
        return Caffeine.newBuilder()
                .initialCapacity(1000) // 初始容量
                .maximumSize(10000)    // 最大缓存条数（防止把 JVM 内存撑爆）
                .expireAfterWrite(5, TimeUnit.SECONDS) // 🚨 核心：本地缓存仅存活 5 秒
                .build();
    }
}