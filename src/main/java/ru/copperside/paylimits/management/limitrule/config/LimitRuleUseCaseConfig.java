package ru.copperside.paylimits.management.limitrule.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.copperside.paylimits.management.audit.application.AuditRecorder;
import ru.copperside.paylimits.management.common.invariant.LimitKindInvariantChecker;
import ru.copperside.paylimits.management.common.invariant.port.TransactionRunner;
import ru.copperside.paylimits.management.limitrule.application.LimitRuleService;
import ru.copperside.paylimits.management.limitrule.application.port.out.LimitRuleRepository;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class LimitRuleUseCaseConfig {

    @Bean
    @ConditionalOnBean(LimitRuleRepository.class)
    LimitRuleService limitRuleService(
            LimitRuleRepository repository,
            LimitKindInvariantChecker invariantChecker,
            TransactionRunner transactionRunner,
            AuditRecorder auditRecorder,
            Clock clock
    ) {
        return new LimitRuleService(repository, invariantChecker, transactionRunner, auditRecorder, clock);
    }
}
