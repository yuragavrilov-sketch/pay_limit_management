package ru.copperside.paylimits.management.merchantgroup.adapter.out.postgres;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.copperside.paylimits.management.merchantgroup.domain.MerchantGroup;
import ru.copperside.paylimits.management.merchantgroup.domain.MerchantGroupMembership;
import ru.copperside.paylimits.management.merchantgroup.domain.MerchantGroupType;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class PostgresMerchantGroupRepositoryIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.schemas", () -> "limit_management");
        registry.add("spring.flyway.default-schema", () -> "limit_management");
    }

    @Autowired
    private PostgresMerchantGroupRepository repository;

    @Test
    void savesAndFindsTypeGroupAndActiveMembership() {
        Instant now = Instant.parse("2026-05-27T09:00:00Z");
        MerchantGroupType type = repository.saveType(new MerchantGroupType(
                UUID.randomUUID(), "risk-tier", "Risk tier", null, true, 10, now, now));
        MerchantGroup group = repository.saveGroup(new MerchantGroup(
                UUID.randomUUID(), type.id(), "risk-high", "High risk", null, true, now, now));
        MerchantGroupMembership membership = repository.saveMembership(new MerchantGroupMembership(
                UUID.randomUUID(), "502118", group.id(), type.id(), now, null, now, "alice", null, null));

        assertThat(repository.findType(type.id())).contains(type);
        assertThat(repository.findGroup(group.id())).contains(group);
        assertThat(repository.findActiveMembership("502118", type.id(), now.plusSeconds(60))).contains(membership);
    }

    @Test
    void closesMembership() {
        Instant now = Instant.parse("2026-05-27T09:00:00Z");
        MerchantGroupType type = repository.saveType(new MerchantGroupType(
                UUID.randomUUID(), "segment", "Segment", null, true, 20, now, now));
        MerchantGroup group = repository.saveGroup(new MerchantGroup(
                UUID.randomUUID(), type.id(), "vip", "VIP", null, true, now, now));
        MerchantGroupMembership membership = repository.saveMembership(new MerchantGroupMembership(
                UUID.randomUUID(), "502118", group.id(), type.id(), now, null, now, "alice", null, null));

        repository.closeMembership(membership.id(), now.plusSeconds(3600), now.plusSeconds(1), "bob");

        assertThat(repository.findActiveMembership("502118", type.id(), now.plusSeconds(7200))).isEmpty();
    }

    @Test
    void findOverlappingMembershipFindsFutureMembership() {
        Instant now = Instant.parse("2026-05-27T09:00:00Z");
        Instant future = Instant.parse("2026-05-28T09:00:00Z");
        MerchantGroupType type = repository.saveType(new MerchantGroupType(
                UUID.randomUUID(), "future-tier", "Future tier", null, true, 30, now, now));
        MerchantGroup group = repository.saveGroup(new MerchantGroup(
                UUID.randomUUID(), type.id(), "future-high", "Future high", null, true, now, now));
        MerchantGroupMembership membership = repository.saveMembership(new MerchantGroupMembership(
                UUID.randomUUID(), "502118", group.id(), type.id(), future, null, now, "alice", null, null));

        assertThat(repository.findOverlappingMembership("502118", type.id(), Instant.parse("2026-05-27T10:00:00Z")))
                .contains(membership);
    }

    @Test
    void replaceMembershipClosesPreviousAndInsertsNextAtomically() {
        Instant now = Instant.parse("2026-05-27T09:00:00Z");
        Instant replacementStart = now.plusSeconds(3600);
        MerchantGroupType type = repository.saveType(new MerchantGroupType(
                UUID.randomUUID(), "replacement-tier", "Replacement tier", null, true, 40, now, now));
        MerchantGroup groupA = repository.saveGroup(new MerchantGroup(
                UUID.randomUUID(), type.id(), "standard", "Standard", null, true, now, now));
        MerchantGroup groupB = repository.saveGroup(new MerchantGroup(
                UUID.randomUUID(), type.id(), "premium", "Premium", null, true, now, now));
        MerchantGroupMembership existing = repository.saveMembership(new MerchantGroupMembership(
                UUID.randomUUID(), "502118", groupA.id(), type.id(), now, null, now, "alice", null, null));
        MerchantGroupMembership next = new MerchantGroupMembership(
                UUID.randomUUID(), "502118", groupB.id(), type.id(), replacementStart, null,
                replacementStart, "bob", null, null);

        MerchantGroupMembership inserted = repository.replaceMembership(
                existing.id(), replacementStart, replacementStart, "bob", next);

        assertThat(inserted).isEqualTo(next);
        assertThat(repository.findActiveMembership("502118", type.id(), replacementStart.plusSeconds(60)))
                .contains(next);
        assertThat(repository.findActiveMembership("502118", type.id(), replacementStart.minusSeconds(60)))
                .contains(existing.close(replacementStart, replacementStart, "bob"));
    }
}
