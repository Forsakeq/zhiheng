package com.yqsoft.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfiguration implements WebMvcConfigurer {

    private final AppProperties properties;

    public WebConfiguration(AppProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (!properties.getCorsAllowedOrigins().isEmpty()) {
            registry.addMapping("/api/**")
                    .allowedOrigins(properties.getCorsAllowedOrigins().toArray(String[]::new))
                    .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                    .allowedHeaders(
                            "Authorization",
                            "Content-Type",
                            "Idempotency-Key",
                            "X-Data-Epoch",
                            "X-Client-Platform",
                            "X-HTTP-Method-Override",
                            "Last-Event-ID")
                    .exposedHeaders("X-Request-Id")
                    .maxAge(600);
        }
    }
}
