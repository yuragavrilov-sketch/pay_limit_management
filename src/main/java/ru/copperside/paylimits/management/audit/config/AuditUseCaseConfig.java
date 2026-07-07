package ru.copperside.paylimits.management.audit.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.copperside.paylimits.management.audit.application.AuditEventService;
import ru.copperside.paylimits.management.audit.application.port.out.AuditEventRepository;

@Configuration(proxyBeanMethods = false)
public class AuditUseCaseConfig {

    @Bean
    @ConditionalOnBean(AuditEventRepository.class)
    AuditEventService auditEventService(AuditEventRepository repository) {
        return new AuditEventService(repository);
    }
}
