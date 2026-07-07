package ru.copperside.paylimits.management.audit.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import ru.copperside.paylimits.management.audit.adapter.in.web.OperatorHeaderInterceptor;
import ru.copperside.paylimits.management.audit.adapter.in.web.RequestOperatorContext;

/**
 * Registers {@link OperatorHeaderInterceptor} on the limit-management API surface so that the
 * missing-operator 400 flows through the standard {@code @RestControllerAdvice} error handling.
 */
@Configuration(proxyBeanMethods = false)
public class OperatorWebMvcConfig implements WebMvcConfigurer {

    private final RequestOperatorContext operatorContext;

    public OperatorWebMvcConfig(RequestOperatorContext operatorContext) {
        this.operatorContext = operatorContext;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new OperatorHeaderInterceptor(operatorContext))
                .addPathPatterns("/internal/v1/limit-management/**");
    }
}
