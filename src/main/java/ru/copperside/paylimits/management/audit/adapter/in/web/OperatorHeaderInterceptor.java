package ru.copperside.paylimits.management.audit.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import ru.copperside.paylimits.management.audit.domain.OperatorProblemException;

import java.util.Set;

/**
 * Enforces operator identity on mutating requests and, for every request that carries it,
 * populates the request-scoped {@link RequestOperatorContext}.
 *
 * <p>For POST/PATCH/PUT/DELETE a non-blank {@code X-Operator-Id} header is mandatory; a missing
 * or blank value raises {@link OperatorProblemException} ({@code OPERATOR_ID_REQUIRED}) before the
 * controller runs, which {@code GlobalExceptionHandler} maps to HTTP 400. Safe methods
 * (GET/HEAD/OPTIONS) never require the header.
 */
public class OperatorHeaderInterceptor implements HandlerInterceptor {

    static final String OPERATOR_ID_HEADER = "X-Operator-Id";
    static final String OPERATOR_NAME_HEADER = "X-Operator-Name";

    private static final Set<String> MUTATING_METHODS = Set.of(
            HttpMethod.POST.name(), HttpMethod.PATCH.name(), HttpMethod.PUT.name(), HttpMethod.DELETE.name());

    private final RequestOperatorContext operatorContext;

    public OperatorHeaderInterceptor(RequestOperatorContext operatorContext) {
        this.operatorContext = operatorContext;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String operatorId = trimToNull(request.getHeader(OPERATOR_ID_HEADER));
        String operatorName = trimToNull(request.getHeader(OPERATOR_NAME_HEADER));

        if (isMutating(request.getMethod()) && operatorId == null) {
            throw new OperatorProblemException(
                    "OPERATOR_ID_REQUIRED",
                    "X-Operator-Id header is required for mutating requests");
        }

        if (operatorId != null) {
            operatorContext.set(operatorId, operatorName);
        }
        return true;
    }

    private static boolean isMutating(String method) {
        return method != null && MUTATING_METHODS.contains(method.toUpperCase());
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
