package ru.copperside.paylimits.management.limitrule.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.copperside.paylimits.management.limitrule.application.LimitRuleService;
import ru.copperside.paylimits.management.limitrule.application.port.out.LimitRuleRepository;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class LimitRuleUseCaseConfig {

    @Bean
    @ConditionalOnBean(LimitRuleRepository.class)
    LimitRuleService limitRuleService(LimitRuleRepository repository, Clock clock) {
        return new LimitRuleService(repository, clock);
    }
}
