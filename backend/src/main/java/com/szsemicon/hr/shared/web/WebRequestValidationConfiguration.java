package com.szsemicon.hr.shared.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class WebRequestValidationConfiguration implements WebMvcConfigurer {

    private final RetiredBoundaryQueryParameterInterceptor
            retiredBoundaryQueryParameterInterceptor;

    public WebRequestValidationConfiguration(
            RetiredBoundaryQueryParameterInterceptor
                    retiredBoundaryQueryParameterInterceptor) {
        this.retiredBoundaryQueryParameterInterceptor =
                retiredBoundaryQueryParameterInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(retiredBoundaryQueryParameterInterceptor)
                .addPathPatterns("/api/**");
    }
}
