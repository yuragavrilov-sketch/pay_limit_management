package ru.copperside.paylimits.management.merchantgroup.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.copperside.paylimits.management.audit.application.AuditRecorder;
import ru.copperside.paylimits.management.common.invariant.LimitKindInvariantChecker;
import ru.copperside.paylimits.management.common.invariant.port.TransactionRunner;
import ru.copperside.paylimits.management.merchantgroup.application.MerchantGroupService;
import ru.copperside.paylimits.management.merchantgroup.application.port.out.MerchantGroupRepository;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class MerchantGroupUseCaseConfig {

    @Bean
    @ConditionalOnBean(MerchantGroupRepository.class)
    MerchantGroupService merchantGroupService(
            MerchantGroupRepository repository,
            LimitKindInvariantChecker invariantChecker,
            TransactionRunner transactionRunner,
            AuditRecorder auditRecorder,
            Clock clock
    ) {
        return new MerchantGroupService(repository, invariantChecker, transactionRunner, auditRecorder, clock);
    }
}
