package ru.copperside.paylimits.management.runtimeconfig.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.copperside.paylimits.management.audit.application.AuditRecorder;
import ru.copperside.paylimits.management.common.invariant.port.TransactionRunner;
import ru.copperside.paylimits.management.runtimeconfig.application.RuntimeManifestCompiler;
import ru.copperside.paylimits.management.runtimeconfig.application.port.out.RuntimeManifestRepository;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class RuntimeManifestUseCaseConfig {

    @Bean
    @ConditionalOnBean(RuntimeManifestRepository.class)
    RuntimeManifestCompiler runtimeManifestCompiler(
            RuntimeManifestRepository repository,
            Clock clock,
            RuntimeManifestProperties properties,
            TransactionRunner transactionRunner,
            AuditRecorder auditRecorder
    ) {
        return new RuntimeManifestCompiler(
                repository,
                clock,
                properties.minActivationLeadTime(),
                properties.businessTimezone(),
                transactionRunner,
                auditRecorder);
    }
}
