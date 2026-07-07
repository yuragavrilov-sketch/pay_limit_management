package ru.copperside.paylimits.management.common.invariant.adapter;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import ru.copperside.paylimits.management.common.invariant.port.TransactionRunner;

import java.util.function.Supplier;

/**
 * {@link TransactionRunner} backed by Spring's {@link TransactionTemplate}. Present only when a real
 * datasource is wired (a {@link PlatformTransactionManager} is then auto-configured); datasource-
 * excluded slice tests fall back to their own pass-through runner. Gated with the same expression as
 * the datasource-backed adapters rather than {@code @ConditionalOnBean}, which would evaluate before
 * the auto-configured transaction manager is registered.
 */
@Component
@ConditionalOnExpression("!'${spring.autoconfigure.exclude:}'.contains('DataSourceAutoConfiguration')")
public class SpringTransactionRunner implements TransactionRunner {

    private final TransactionTemplate transactionTemplate;
    private final TransactionTemplate repeatableReadTransactionTemplate;

    public SpringTransactionRunner(PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.repeatableReadTransactionTemplate = new TransactionTemplate(transactionManager);
        this.repeatableReadTransactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Override
    public <T> T run(Supplier<T> work) {
        return transactionTemplate.execute(status -> work.get());
    }

    @Override
    public <T> T runRepeatableRead(Supplier<T> work) {
        return repeatableReadTransactionTemplate.execute(status -> work.get());
    }
}
