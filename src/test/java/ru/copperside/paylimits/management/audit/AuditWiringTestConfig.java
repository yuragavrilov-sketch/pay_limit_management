package ru.copperside.paylimits.management.audit;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import ru.copperside.paylimits.management.audit.application.AuditRecorder;
import ru.copperside.paylimits.management.audit.application.OperatorContext;
import ru.copperside.paylimits.management.audit.application.port.out.AuditEventRepository;
import ru.copperside.paylimits.management.audit.application.port.out.AuditPayloadSerializer;

import java.time.Clock;

/**
 * Audit wiring for datasource-excluded controller slice tests: a {@link Primary} in-memory
 * {@link AuditTestSupport.RecordingAuditEventRepository} (so tests can assert on the events a mutation
 * produced) plus an explicit, unconditional {@link AuditRecorder}. Defined here rather than relying on
 * {@code AuditUseCaseConfig}'s {@code @ConditionalOnBean(AuditEventRepository)} bean, whose condition is
 * order-sensitive against a fake repository contributed by an imported {@code @TestConfiguration}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class AuditWiringTestConfig {

    @Bean
    @Primary
    public AuditTestSupport.RecordingAuditEventRepository auditEventRepository() {
        return new AuditTestSupport.RecordingAuditEventRepository();
    }

    // Distinct bean name (not "auditRecorder") so this never collides with AuditUseCaseConfig's
    // conditional bean when its @ConditionalOnBean(AuditEventRepository) happens to fire in-test;
    // @Primary makes it the one injected either way.
    @Bean
    @Primary
    public AuditRecorder testAuditRecorder(
            OperatorContext operatorContext,
            AuditEventRepository repository,
            AuditPayloadSerializer serializer,
            Clock clock
    ) {
        return new AuditRecorder(operatorContext, repository, serializer, clock);
    }
}
