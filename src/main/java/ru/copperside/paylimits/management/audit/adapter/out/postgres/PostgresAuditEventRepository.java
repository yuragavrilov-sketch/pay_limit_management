package ru.copperside.paylimits.management.audit.adapter.out.postgres;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.copperside.paylimits.management.audit.application.port.out.AuditEventRepository;
import ru.copperside.paylimits.management.audit.domain.AuditEvent;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
@ConditionalOnExpression("!'${spring.autoconfigure.exclude:}'.contains('DataSourceAutoConfiguration')")
public class PostgresAuditEventRepository implements AuditEventRepository {

    private final JdbcTemplate jdbcTemplate;

    public PostgresAuditEventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void append(AuditEvent event) {
        jdbcTemplate.update("""
                insert into limit_management.audit_event
                    (id, entity_type, entity_id, action, actor_id, actor_name, occurred_at, before, after)
                values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
                """,
                event.id(),
                event.entityType(),
                event.entityId(),
                event.action(),
                event.actorId(),
                event.actorName(),
                Timestamp.from(event.occurredAt()),
                event.beforeJson(),
                event.afterJson());
    }

    @Override
    public List<AuditEvent> find(String entityType, String entityId, Instant from, Instant to, int page, int size) {
        StringBuilder sql = new StringBuilder("""
                select id, entity_type, entity_id, action, actor_id, actor_name, occurred_at,
                       before::text as before_json, after::text as after_json
                from limit_management.audit_event
                where 1 = 1
                """);
        List<Object> args = new ArrayList<>();
        if (entityType != null) {
            sql.append(" and entity_type = ?");
            args.add(entityType);
        }
        if (entityId != null) {
            sql.append(" and entity_id = ?");
            args.add(entityId);
        }
        if (from != null) {
            sql.append(" and occurred_at >= ?");
            args.add(Timestamp.from(from));
        }
        if (to != null) {
            sql.append(" and occurred_at < ?");
            args.add(Timestamp.from(to));
        }
        sql.append(" order by occurred_at desc, id desc limit ? offset ?");
        args.add(size);
        args.add((long) page * size);
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> mapEvent(rs), args.toArray());
    }

    private AuditEvent mapEvent(ResultSet rs) throws SQLException {
        return new AuditEvent(
                rs.getObject("id", UUID.class),
                rs.getString("entity_type"),
                rs.getString("entity_id"),
                rs.getString("action"),
                rs.getString("actor_id"),
                rs.getString("actor_name"),
                rs.getTimestamp("occurred_at").toInstant(),
                rs.getString("before_json"),
                rs.getString("after_json"));
    }
}
