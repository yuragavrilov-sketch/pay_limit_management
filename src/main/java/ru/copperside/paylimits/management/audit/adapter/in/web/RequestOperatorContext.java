package ru.copperside.paylimits.management.audit.adapter.in.web;

import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;
import ru.copperside.paylimits.management.audit.application.OperatorContext;

/**
 * Request-scoped holder for the operator identity carried by the current HTTP request.
 * Populated by {@link OperatorHeaderInterceptor} from the {@code X-Operator-Id} /
 * {@code X-Operator-Name} headers.
 */
@Component
@RequestScope
public class RequestOperatorContext implements OperatorContext {

    private String operatorId;
    private String operatorName;

    public void set(String operatorId, String operatorName) {
        this.operatorId = operatorId;
        this.operatorName = operatorName;
    }

    @Override
    public String operatorId() {
        return operatorId;
    }

    @Override
    public String operatorName() {
        return operatorName;
    }
}
