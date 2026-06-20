package com.example.complaints.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

/**
 * In-JVM Caffeine cache for hot reads (categories, subdivisions, DCs).
 * See TECHNICAL_DESIGN.md §1, §9.
 */
@Configuration
@EnableCaching
public class CaffeineCacheConfig {

    public static final String CACHE_CATEGORIES   = "categories";
    public static final String CACHE_SUBDIVISIONS = "subdivisions";
    public static final String CACHE_DCS          = "distributionCenters";

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager mgr = new CaffeineCacheManager();
        mgr.setCacheNames(List.of(CACHE_CATEGORIES, CACHE_SUBDIVISIONS, CACHE_DCS));
        mgr.setCaffeine(Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(Duration.ofMinutes(10))
                .recordStats());
        return mgr;
    }
}

