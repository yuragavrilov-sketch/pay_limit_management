package ru.copperside.paylimits.management.common.invariant.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.copperside.paylimits.management.common.invariant.LimitKindInvariantChecker;
import ru.copperside.paylimits.management.common.invariant.port.LimitKindInvariantRepository;

/**
 * Wires the {@link LimitKindInvariantChecker} from its outbound port. Only created when the
 * datasource-backed {@link LimitKindInvariantRepository} adapter is active, so datasource-excluded
 * slice tests supply their own checker. Gated with the same expression as the adapters rather than
 * {@code @ConditionalOnBean} to stay independent of bean-registration ordering.
 */
@Configuration(proxyBeanMethods = false)
public class LimitKindInvariantConfig {

    @Bean
    @ConditionalOnExpression("!'${spring.autoconfigure.exclude:}'.contains('DataSourceAutoConfiguration')")
    LimitKindInvariantChecker limitKindInvariantChecker(LimitKindInvariantRepository repository) {
        return new LimitKindInvariantChecker(repository);
    }
}
