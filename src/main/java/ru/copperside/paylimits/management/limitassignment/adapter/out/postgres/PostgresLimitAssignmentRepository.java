package ru.copperside.paylimits.management.limitassignment.adapter.out.postgres;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.copperside.paylimits.management.limitassignment.application.port.out.LimitAssignmentRepository;
import ru.copperside.paylimits.management.limitassignment.domain.AssignmentOwnerType;
import ru.copperside.paylimits.management.limitassignment.domain.LimitAssignment;
import ru.copperside.paylimits.management.limitassignment.domain.LimitAssignmentProblemException;
import ru.copperside.paylimits.management.limitassignment.domain.LimitMode;
import ru.copperside.paylimits.management.limitassignment.domain.MerchantGroupReference;
import ru.copperside.paylimits.management.limitassignment.domain.RuleReference;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@ConditionalOnExpression("!'${spring.autoconfigure.exclude:}'.contains('DataSourceAutoConfiguration')")
public class PostgresLimitAssignmentRepository implements LimitAssignmentRepository {

    private final JdbcTemplate jdbcTemplate;

    public PostgresLimitAssignmentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<LimitAssignment> listAssignments() {
        return jdbcTemplate.query("""
                select id, rule_id, owner_type, owner_id, limit_mode,
                       valid_from, valid_to, enabled, created_at, updated_at
                from limit_management.limit_assignments
                order by valid_from desc, created_at desc, id asc
                """, (rs, rowNum) -> mapAssignment(rs));
    }

    @Override
    public Optional<LimitAssignment> findAssignment(UUID assignmentId) {
        return jdbcTemplate.query("""
                select id, rule_id, owner_type, owner_id, limit_mode,
                       valid_from, valid_to, enabled, created_at, updated_at
                from limit_management.limit_assignments
                where id = ?
                """, (rs, rowNum) -> mapAssignment(rs), assignmentId).stream().findFirst();
    }

    @Override
    public Optional<RuleReference> findRule(UUID ruleId) {
        return jdbcTemplate.query("""
                select id, status
                from limit_management.limit_rules
                where id = ?
                """, (rs, rowNum) -> new RuleReference(
                rs.getObject("id", UUID.class),
                "ACTIVE".equals(rs.getString("status"))
        ), ruleId).stream().findFirst();
    }

    @Override
    public Optional<MerchantGroupReference> findMerchantGroup(UUID groupId) {
        return jdbcTemplate.query("""
                select id, enabled
                from limit_management.merchant_groups
                where id = ?
                """, (rs, rowNum) -> new MerchantGroupReference(
                rs.getObject("id", UUID.class),
                rs.getBoolean("enabled")
        ), groupId).stream().findFirst();
    }

    @Override
    public boolean hasEnabledOverlap(
            UUID excludedAssignmentId,
            UUID ruleId,
            AssignmentOwnerType ownerType,
            String ownerId,
            Instant validFrom,
            Instant validTo
    ) {
        Boolean exists = jdbcTemplate.queryForObject("""
                select exists (
                    select 1
                    from limit_management.limit_assignments
                    where enabled = true
                      and (cast(? as uuid) is null or id <> ?)
                      and rule_id = ?
                      and owner_type = ?
                      and coalesce(owner_id, '') = coalesce(?, '')
                      and tstzrange(valid_from, coalesce(valid_to, 'infinity'::timestamptz), '[)')
                          && tstzrange(?, coalesce(?, 'infinity'::timestamptz), '[)')
                )
                """, Boolean.class,
                excludedAssignmentId,
                excludedAssignmentId,
                ruleId,
                ownerType.name(),
                ownerId,
                Timestamp.from(validFrom),
                toTimestamp(validTo));
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public LimitAssignment saveAssignment(LimitAssignment assignment) {
        try {
            jdbcTemplate.update("""
                    insert into limit_management.limit_assignments
                        (id, rule_id, owner_type, owner_id, limit_mode,
                         valid_from, valid_to, enabled, created_at, updated_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    assignment.id(),
                    assignment.ruleId(),
                    assignment.ownerType().name(),
                    assignment.ownerId(),
                    assignment.limitMode().name(),
                    Timestamp.from(assignment.validFrom()),
                    toTimestamp(assignment.validTo()),
                    assignment.enabled(),
                    Timestamp.from(assignment.createdAt()),
                    Timestamp.from(assignment.updatedAt()));
            return assignment;
        } catch (DataIntegrityViolationException ex) {
            throw mapIntegrityViolation(ex);
        }
    }

    @Override
    public LimitAssignment updateAssignment(LimitAssignment assignment) {
        try {
            jdbcTemplate.update("""
                    update limit_management.limit_assignments
                    set limit_mode = ?, valid_from = ?, valid_to = ?, enabled = ?, updated_at = ?
                    where id = ?
                    """,
                    assignment.limitMode().name(),
                    Timestamp.from(assignment.validFrom()),
                    toTimestamp(assignment.validTo()),
                    assignment.enabled(),
                    Timestamp.from(assignment.updatedAt()),
                    assignment.id());
            return findAssignment(assignment.id())
                    .orElseThrow(() -> new LimitAssignmentProblemException("ASSIGNMENT_NOT_FOUND", "Assignment not found"));
        } catch (DataIntegrityViolationException ex) {
            throw mapIntegrityViolation(ex);
        }
    }

    private LimitAssignment mapAssignment(ResultSet rs) throws SQLException {
        Timestamp validTo = rs.getTimestamp("valid_to");
        return new LimitAssignment(
                rs.getObject("id", UUID.class),
                rs.getObject("rule_id", UUID.class),
                AssignmentOwnerType.valueOf(rs.getString("owner_type")),
                rs.getString("owner_id"),
                LimitMode.valueOf(rs.getString("limit_mode")),
                rs.getTimestamp("valid_from").toInstant(),
                validTo == null ? null : validTo.toInstant(),
                rs.getBoolean("enabled"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    private Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private LimitAssignmentProblemException mapIntegrityViolation(DataIntegrityViolationException ex) {
        String message = rootMessage(ex);
        if (message.contains("limit_assignments_enabled_no_overlap")) {
            return new LimitAssignmentProblemException(
                    "ASSIGNMENT_CONFLICT",
                    "Enabled assignments for the same rule and owner must not overlap"
            );
        }
        if (message.contains("limit_assignments_rule_fk")) {
            return new LimitAssignmentProblemException("RULE_NOT_FOUND", "Rule not found");
        }
        if (message.contains("limit_assignments")) {
            return new LimitAssignmentProblemException("VALIDATION_ERROR", "Assignment data violates repository constraints");
        }
        return new LimitAssignmentProblemException("VALIDATION_ERROR", "Assignment data violates repository constraints");
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? throwable.getMessage() : current.getMessage();
    }
}
