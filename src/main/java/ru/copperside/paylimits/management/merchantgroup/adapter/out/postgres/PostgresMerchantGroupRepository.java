package ru.copperside.paylimits.management.merchantgroup.adapter.out.postgres;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import ru.copperside.paylimits.management.merchantgroup.application.port.out.MerchantGroupRepository;
import ru.copperside.paylimits.management.merchantgroup.domain.MerchantGroup;
import ru.copperside.paylimits.management.merchantgroup.domain.MerchantGroupMembership;
import ru.copperside.paylimits.management.merchantgroup.domain.MerchantGroupProblemException;
import ru.copperside.paylimits.management.merchantgroup.domain.MerchantGroupType;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
@ConditionalOnExpression("!'${spring.autoconfigure.exclude:}'.contains('DataSourceAutoConfiguration')")
public class PostgresMerchantGroupRepository implements MerchantGroupRepository {

    private final JdbcTemplate jdbcTemplate;

    public PostgresMerchantGroupRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public MerchantGroupType saveType(MerchantGroupType type) {
        try {
            jdbcTemplate.update("""
                    insert into limit_management.merchant_group_types
                        (id, code, name, description, enabled, sort_order, created_at, updated_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    type.id(), type.code(), type.name(), type.description(), type.enabled(), type.sortOrder(),
                    Timestamp.from(type.createdAt()), Timestamp.from(type.updatedAt()));
            return type;
        } catch (DataIntegrityViolationException ex) {
            throw new MerchantGroupProblemException("GROUP_TYPE_CODE_CONFLICT", "Group type code already exists");
        }
    }

    @Override
    public Optional<MerchantGroupType> findType(UUID typeId) {
        return jdbcTemplate.query("""
                select id, code, name, description, enabled, sort_order, created_at, updated_at
                from limit_management.merchant_group_types
                where id = ?
                """, (rs, rowNum) -> mapType(rs), typeId).stream().findFirst();
    }

    @Override
    public MerchantGroup saveGroup(MerchantGroup group) {
        try {
            jdbcTemplate.update("""
                    insert into limit_management.merchant_groups
                        (id, type_id, code, name, description, enabled, created_at, updated_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    group.id(), group.typeId(), group.code(), group.name(), group.description(), group.enabled(),
                    Timestamp.from(group.createdAt()), Timestamp.from(group.updatedAt()));
            return group;
        } catch (DataIntegrityViolationException ex) {
            throw new MerchantGroupProblemException("GROUP_CODE_CONFLICT", "Group code already exists in type");
        }
    }

    @Override
    public Optional<MerchantGroup> findGroup(UUID groupId) {
        return jdbcTemplate.query("""
                select id, type_id, code, name, description, enabled, created_at, updated_at
                from limit_management.merchant_groups
                where id = ?
                """, (rs, rowNum) -> mapGroup(rs), groupId).stream().findFirst();
    }

    @Override
    public Optional<MerchantGroupMembership> findActiveMembership(String merchantId, UUID groupTypeId, Instant at) {
        return jdbcTemplate.query("""
                select id, merchant_id, group_id, group_type_id, valid_from, valid_to, created_at, created_by, closed_at, closed_by
                from limit_management.merchant_group_memberships
                where merchant_id = ?
                  and group_type_id = ?
                  and valid_from <= ?
                  and (valid_to is null or valid_to > ?)
                order by valid_from desc
                limit 1
                """, (rs, rowNum) -> mapMembership(rs), merchantId, groupTypeId, Timestamp.from(at), Timestamp.from(at))
                .stream()
                .findFirst();
    }

    @Override
    public Optional<MerchantGroupMembership> findOverlappingMembership(String merchantId, UUID groupTypeId, Instant validFrom) {
        return jdbcTemplate.query("""
                select id, merchant_id, group_id, group_type_id, valid_from, valid_to, created_at, created_by, closed_at, closed_by
                from limit_management.merchant_group_memberships
                where merchant_id = ?
                  and group_type_id = ?
                  and (valid_to is null or valid_to > ?)
                order by valid_from asc
                limit 1
                """, (rs, rowNum) -> mapMembership(rs), merchantId, groupTypeId, Timestamp.from(validFrom))
                .stream()
                .findFirst();
    }

    @Override
    public void closeMembership(UUID membershipId, Instant validTo, Instant closedAt, String closedBy) {
        closeMembershipRow(membershipId, validTo, closedAt, closedBy);
    }

    @Override
    public MerchantGroupMembership saveMembership(MerchantGroupMembership membership) {
        try {
            insertMembership(membership);
            return membership;
        } catch (DataIntegrityViolationException ex) {
            throw membershipOverlap();
        }
    }

    @Override
    @Transactional
    public MerchantGroupMembership replaceMembership(
            UUID membershipId,
            Instant validTo,
            Instant closedAt,
            String closedBy,
            MerchantGroupMembership membership
    ) {
        try {
            closeMembershipRow(membershipId, validTo, closedAt, closedBy);
            insertMembership(membership);
            return membership;
        } catch (DataIntegrityViolationException ex) {
            throw membershipOverlap();
        }
    }

    private void closeMembershipRow(UUID membershipId, Instant validTo, Instant closedAt, String closedBy) {
        jdbcTemplate.update("""
                update limit_management.merchant_group_memberships
                set valid_to = ?, closed_at = ?, closed_by = ?
                where id = ?
                """, Timestamp.from(validTo), Timestamp.from(closedAt), closedBy, membershipId);
    }

    private void insertMembership(MerchantGroupMembership membership) {
        jdbcTemplate.update("""
                insert into limit_management.merchant_group_memberships
                    (id, merchant_id, group_id, group_type_id, valid_from, valid_to, created_at, created_by, closed_at, closed_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                membership.id(), membership.merchantId(), membership.groupId(), membership.groupTypeId(),
                Timestamp.from(membership.validFrom()),
                membership.validTo() == null ? null : Timestamp.from(membership.validTo()),
                Timestamp.from(membership.createdAt()), membership.createdBy(),
                membership.closedAt() == null ? null : Timestamp.from(membership.closedAt()),
                membership.closedBy());
    }

    private MerchantGroupProblemException membershipOverlap() {
        return new MerchantGroupProblemException("MEMBERSHIP_PERIOD_OVERLAP", "Membership period overlaps another membership");
    }

    private MerchantGroupType mapType(ResultSet rs) throws SQLException {
        return new MerchantGroupType(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getBoolean("enabled"),
                rs.getInt("sort_order"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    private MerchantGroup mapGroup(ResultSet rs) throws SQLException {
        return new MerchantGroup(
                rs.getObject("id", UUID.class),
                rs.getObject("type_id", UUID.class),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getBoolean("enabled"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    private MerchantGroupMembership mapMembership(ResultSet rs) throws SQLException {
        Timestamp validTo = rs.getTimestamp("valid_to");
        Timestamp closedAt = rs.getTimestamp("closed_at");
        return new MerchantGroupMembership(
                rs.getObject("id", UUID.class),
                rs.getString("merchant_id"),
                rs.getObject("group_id", UUID.class),
                rs.getObject("group_type_id", UUID.class),
                rs.getTimestamp("valid_from").toInstant(),
                validTo == null ? null : validTo.toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                rs.getString("created_by"),
                closedAt == null ? null : closedAt.toInstant(),
                rs.getString("closed_by")
        );
    }
}
