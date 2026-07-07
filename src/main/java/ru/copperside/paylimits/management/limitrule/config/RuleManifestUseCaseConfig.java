package ru.copperside.paylimits.management.limitrule.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.copperside.paylimits.management.audit.application.AuditRecorder;
import ru.copperside.paylimits.management.common.invariant.port.TransactionRunner;
import ru.copperside.paylimits.management.limitrule.application.RuleManifestCompiler;
import ru.copperside.paylimits.management.limitrule.application.port.out.RuleManifestRepository;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class RuleManifestUseCaseConfig {

    @Bean
    @ConditionalOnBean(RuleManifestRepository.class)
    RuleManifestCompiler ruleManifestCompiler(
            RuleManifestRepository repository,
            Clock clock,
            TransactionRunner transactionRunner,
            AuditRecorder auditRecorder
    ) {
        return new RuleManifestCompiler(repository, clock, transactionRunner, auditRecorder);
    }
}
