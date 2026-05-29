package ru.copperside.paylimits.management.limitassignment.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.copperside.paylimits.management.limitassignment.application.LimitAssignmentService;
import ru.copperside.paylimits.management.limitassignment.application.port.out.LimitAssignmentRepository;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class LimitAssignmentUseCaseConfig {

    @Bean
    @ConditionalOnBean(LimitAssignmentRepository.class)
    LimitAssignmentService limitAssignmentService(LimitAssignmentRepository repository, Clock clock) {
        return new LimitAssignmentService(repository, clock);
    }
}
