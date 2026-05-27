package ru.copperside.paylimits.management.merchantgroup.adapter.in.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import ru.copperside.paylimits.management.merchantgroup.application.MerchantGroupService;
import ru.copperside.paylimits.management.merchantgroup.application.port.out.MerchantGroupRepository;
import ru.copperside.paylimits.management.merchantgroup.domain.MerchantGroup;
import ru.copperside.paylimits.management.merchantgroup.domain.MerchantGroupMembership;
import ru.copperside.paylimits.management.merchantgroup.domain.MerchantGroupType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.autoconfigure.exclude="
        + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
        + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration")
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(MerchantGroupControllerTest.TestSupport.class)
class MerchantGroupControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FakeRepository repository;

    @BeforeEach
    void setUp() {
        repository.clear();
    }

    @Test
    void createsGroupType() throws Exception {
        mockMvc.perform(post("/internal/v1/limit-management/merchant-group-types")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "code": "risk-tier",
                                  "name": "Risk tier",
                                  "description": "Risk segmentation",
                                  "sortOrder": 10
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value("risk-tier"))
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.error").value(nullValue()));
    }

    @Test
    void rejectsBlankGroupTypeCode() throws Exception {
        mockMvc.perform(post("/internal/v1/limit-management/merchant-group-types")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "code": "",
                                  "name": "Risk tier",
                                  "sortOrder": 10
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void listsGroupTypes() throws Exception {
        repository.addType("risk-tier", true);

        mockMvc.perform(get("/internal/v1/limit-management/merchant-group-types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].code").value("risk-tier"));
    }

    @Test
    void patchesGroupType() throws Exception {
        MerchantGroupType type = repository.addType("risk-tier", true);

        mockMvc.perform(patch("/internal/v1/limit-management/merchant-group-types/{typeId}", type.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Risk tier",
                                  "description": "Updated",
                                  "enabled": false,
                                  "sortOrder": 20
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false));
    }

    @Test
    void createsGroup() throws Exception {
        MerchantGroupType type = repository.addType("risk-tier", true);

        mockMvc.perform(post("/internal/v1/limit-management/merchant-groups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "typeId": "%s",
                                  "code": "risk-high",
                                  "name": "High risk",
                                  "description": "High risk merchants"
                                }
                                """.formatted(type.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value("risk-high"));
    }

    @Test
    void listsGroupsByType() throws Exception {
        MerchantGroupType type = repository.addType("risk-tier", true);
        repository.addGroup(type.id(), "risk-high", true);

        mockMvc.perform(get("/internal/v1/limit-management/merchant-groups?typeId={typeId}", type.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].typeId").value(type.id().toString()));
    }

    @Test
    void patchesGroup() throws Exception {
        MerchantGroupType type = repository.addType("risk-tier", true);
        MerchantGroup group = repository.addGroup(type.id(), "risk-high", true);

        mockMvc.perform(patch("/internal/v1/limit-management/merchant-groups/{groupId}", group.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "High risk updated",
                                  "description": "Updated",
                                  "enabled": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("High risk updated"));
    }

    @Test
    void assignsMembership() throws Exception {
        MerchantGroupType type = repository.addType("risk-tier", true);
        MerchantGroup group = repository.addGroup(type.id(), "risk-high", true);

        mockMvc.perform(post("/internal/v1/limit-management/merchant-group-memberships")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "merchantId": "502118",
                                  "groupId": "%s",
                                  "validFrom": "2026-05-27T09:00:00Z"
                                }
                                """.formatted(group.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.merchantId").value("502118"));
    }

    @Test
    void listsMemberships() throws Exception {
        MerchantGroupType type = repository.addType("risk-tier", true);
        MerchantGroup group = repository.addGroup(type.id(), "risk-high", true);
        repository.addMembership("502118", group.id(), type.id(), Instant.parse("2026-05-27T09:00:00Z"), null);

        mockMvc.perform(get("/internal/v1/limit-management/merchant-group-memberships?merchantId=502118&state=all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].merchantId").value("502118"));
    }

    @Test
    void closesMembership() throws Exception {
        MerchantGroupType type = repository.addType("risk-tier", true);
        MerchantGroup group = repository.addGroup(type.id(), "risk-high", true);
        MerchantGroupMembership membership = repository.addMembership(
                "502118",
                group.id(),
                type.id(),
                Instant.parse("2026-05-27T09:00:00Z"),
                null
        );

        mockMvc.perform(post("/internal/v1/limit-management/merchant-group-memberships/{membershipId}/close", membership.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "validTo": "2026-05-27T15:00:00Z"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.validTo").value("2026-05-27T15:00:00Z"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestSupport {

        @Bean
        @Primary
        FakeRepository fakeRepository() {
            return new FakeRepository();
        }

        @Bean
        @Primary
        MerchantGroupService merchantGroupService(FakeRepository repository, java.time.Clock clock) {
            return new MerchantGroupService(repository, clock);
        }
    }

    static class FakeRepository implements MerchantGroupRepository {

        private final List<MerchantGroupType> types = new ArrayList<>();
        private final List<MerchantGroup> groups = new ArrayList<>();
        private final List<MerchantGroupMembership> memberships = new ArrayList<>();

        FakeRepository clear() {
            types.clear();
            groups.clear();
            memberships.clear();
            return this;
        }

        MerchantGroupType addType(String code, boolean enabled) {
            MerchantGroupType type = new MerchantGroupType(
                    UUID.randomUUID(),
                    code,
                    code,
                    null,
                    enabled,
                    10,
                    Instant.parse("2026-05-27T09:00:00Z"),
                    Instant.parse("2026-05-27T09:00:00Z")
            );
            types.add(type);
            return type;
        }

        MerchantGroup addGroup(UUID typeId, String code, boolean enabled) {
            MerchantGroup group = new MerchantGroup(
                    UUID.randomUUID(),
                    typeId,
                    code,
                    code,
                    null,
                    enabled,
                    Instant.parse("2026-05-27T09:00:00Z"),
                    Instant.parse("2026-05-27T09:00:00Z")
            );
            groups.add(group);
            return group;
        }

        MerchantGroupMembership addMembership(String merchantId, UUID groupId, UUID groupTypeId, Instant validFrom, Instant validTo) {
            MerchantGroupMembership membership = new MerchantGroupMembership(
                    UUID.randomUUID(),
                    merchantId,
                    groupId,
                    groupTypeId,
                    validFrom,
                    validTo,
                    Instant.parse("2026-05-27T09:00:00Z"),
                    "test",
                    null,
                    null
            );
            memberships.add(membership);
            return membership;
        }

        @Override
        public List<MerchantGroupType> listTypes() {
            return List.copyOf(types);
        }

        @Override
        public MerchantGroupType saveType(MerchantGroupType type) {
            types.add(type);
            return type;
        }

        @Override
        public MerchantGroupType updateType(MerchantGroupType type) {
            types.replaceAll(existing -> existing.id().equals(type.id()) ? type : existing);
            return type;
        }

        @Override
        public Optional<MerchantGroupType> findType(UUID typeId) {
            return types.stream().filter(type -> type.id().equals(typeId)).findFirst();
        }

        @Override
        public List<MerchantGroup> listGroups(UUID typeId) {
            return groups.stream()
                    .filter(group -> typeId == null || group.typeId().equals(typeId))
                    .toList();
        }

        @Override
        public MerchantGroup saveGroup(MerchantGroup group) {
            groups.add(group);
            return group;
        }

        @Override
        public MerchantGroup updateGroup(MerchantGroup group) {
            groups.replaceAll(existing -> existing.id().equals(group.id()) ? group : existing);
            return group;
        }

        @Override
        public Optional<MerchantGroup> findGroup(UUID groupId) {
            return groups.stream().filter(group -> group.id().equals(groupId)).findFirst();
        }

        @Override
        public List<MerchantGroupMembership> listMemberships(String merchantId, UUID typeId, UUID groupId, String state, Instant at) {
            return memberships.stream()
                    .filter(membership -> merchantId == null || membership.merchantId().equals(merchantId))
                    .filter(membership -> typeId == null || membership.groupTypeId().equals(typeId))
                    .filter(membership -> groupId == null || membership.groupId().equals(groupId))
                    .toList();
        }

        @Override
        public Optional<MerchantGroupMembership> findMembership(UUID membershipId) {
            return memberships.stream().filter(membership -> membership.id().equals(membershipId)).findFirst();
        }

        @Override
        public Optional<MerchantGroupMembership> findActiveMembership(String merchantId, UUID groupTypeId, Instant at) {
            return memberships.stream()
                    .filter(membership -> membership.merchantId().equals(merchantId))
                    .filter(membership -> membership.groupTypeId().equals(groupTypeId))
                    .filter(membership -> !membership.validFrom().isAfter(at))
                    .filter(membership -> membership.validTo() == null || membership.validTo().isAfter(at))
                    .findFirst();
        }

        @Override
        public Optional<MerchantGroupMembership> findOverlappingMembership(String merchantId, UUID groupTypeId, Instant validFrom) {
            return memberships.stream()
                    .filter(membership -> membership.merchantId().equals(merchantId))
                    .filter(membership -> membership.groupTypeId().equals(groupTypeId))
                    .filter(membership -> membership.validTo() == null || membership.validTo().isAfter(validFrom))
                    .findFirst();
        }

        @Override
        public MerchantGroupMembership closeMembership(UUID membershipId, Instant validTo, Instant closedAt, String closedBy) {
            final MerchantGroupMembership[] closed = new MerchantGroupMembership[1];
            memberships.replaceAll(membership -> membership.id().equals(membershipId)
                    ? (closed[0] = membership.close(validTo, closedAt, closedBy))
                    : membership);
            return closed[0];
        }

        @Override
        public MerchantGroupMembership saveMembership(MerchantGroupMembership membership) {
            memberships.add(membership);
            return membership;
        }

        @Override
        public MerchantGroupMembership replaceMembership(
                UUID membershipId,
                Instant validTo,
                Instant closedAt,
                String closedBy,
                MerchantGroupMembership membership
        ) {
            closeMembership(membershipId, validTo, closedAt, closedBy);
            return saveMembership(membership);
        }
    }
}
