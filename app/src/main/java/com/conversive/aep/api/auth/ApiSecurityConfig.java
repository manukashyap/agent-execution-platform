package com.conversive.aep.api.auth;

import com.conversive.aep.tenancy.persistence.ApiKeyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** API-key auth and scope checks apply to /v1/** only; actuator stays unauthenticated for probes. */
@Configuration(proxyBeanMethods = false)
public class ApiSecurityConfig implements WebMvcConfigurer {

    static final String API_PATTERN = "/v1/*";

    @Bean
    FilterRegistrationBean<ApiKeyAuthFilter> apiKeyAuthFilter(ApiKeyRepository keys, ObjectMapper mapper) {
        FilterRegistrationBean<ApiKeyAuthFilter> registration =
                new FilterRegistrationBean<>(new ApiKeyAuthFilter(keys, mapper));
        registration.addUrlPatterns(API_PATTERN);
        registration.setOrder(0);
        return registration;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new ScopeInterceptor()).addPathPatterns("/v1/**");
    }
}
