package ru.copperside.paylimits.management.effectivelimits.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.copperside.paylimits.management.effectivelimits.application.EffectiveLimitsService;
import ru.copperside.paylimits.management.effectivelimits.application.port.out.EffectiveLimitsRepository;

@Configuration(proxyBeanMethods = false)
public class EffectiveLimitsUseCaseConfig {

    @Bean
    @ConditionalOnBean(EffectiveLimitsRepository.class)
    EffectiveLimitsService effectiveLimitsService(EffectiveLimitsRepository repository) {
        return new EffectiveLimitsService(repository);
    }
}
