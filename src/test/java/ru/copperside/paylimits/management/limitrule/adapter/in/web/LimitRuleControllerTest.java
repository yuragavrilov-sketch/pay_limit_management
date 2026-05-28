package ru.copperside.paylimits.management.limitrule.adapter.in.web;

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
import ru.copperside.paylimits.management.limitrule.application.LimitRuleService;
import ru.copperside.paylimits.management.limitrule.application.port.out.LimitRuleRepository;
import ru.copperside.paylimits.management.limitrule.domain.AttributeSelectorType;
import ru.copperside.paylimits.management.limitrule.domain.DictionaryItem;
import ru.copperside.paylimits.management.limitrule.domain.LimitRule;
import ru.copperside.paylimits.management.limitrule.domain.LimitTargetType;
import ru.copperside.paylimits.management.limitrule.domain.OperationDirection;
import ru.copperside.paylimits.management.limitrule.domain.OperationSelectorType;
import ru.copperside.paylimits.management.limitrule.domain.OperationType;
import ru.copperside.paylimits.management.limitrule.domain.RuleDictionaries;
import ru.copperside.paylimits.management.limitrule.domain.RuleMetric;
import ru.copperside.paylimits.management.limitrule.domain.RulePeriod;
import ru.copperside.paylimits.management.limitrule.domain.RuleSelector;
import ru.copperside.paylimits.management.limitrule.domain.RuleStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
@Import(LimitRuleControllerTest.TestSupport.class)
class LimitRuleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FakeRepository repository;

    @BeforeEach
    void setUp() {
        repository.clear();
    }

    @Test
    void listsSeededOperationTypes() throws Exception {
        repository.addOperationType("SBP_C2B", OperationDirection.IN, true);

        mockMvc.perform(get("/internal/v1/limit-management/operation-types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].code").value("SBP_C2B"))
                .andExpect(jsonPath("$.data[0].sortOrder").value(10));
    }

    @Test
    void returnsRuleDictionaries() throws Exception {
        repository.addOperationType("SBP_C2B", OperationDirection.IN, true);

        mockMvc.perform(get("/internal/v1/limit-management/rule-dictionaries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operationFamilies[0].code").value("CARD"))
                .andExpect(jsonPath("$.data.operationTypes[0].code").value("SBP_C2B"))
                .andExpect(jsonPath("$.data.operationSelectorTypes[0]").value("ANY"))
                .andExpect(jsonPath("$.data.attributeSelectorTypes[0]").value("NONE"))
                .andExpect(jsonPath("$.error").value(nullValue()));
    }

    @Test
    void createsOperationType() throws Exception {
        mockMvc.perform(post("/internal/v1/limit-management/operation-types")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "code": "SBP_C2B",
                                  "name": "SBP C2B",
                                  "familyCode": "SBP",
                                  "direction": "IN"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value("SBP_C2B"))
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.error").value(nullValue()));
    }

    @Test
    void rejectsBlankOperationTypeCode() throws Exception {
        mockMvc.perform(post("/internal/v1/limit-management/operation-types")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "code": "",
                                  "name": "SBP C2B",
                                  "familyCode": "SBP",
                                  "direction": "IN"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void patchesOperationType() throws Exception {
        OperationType type = repository.addOperationType("SBP_C2B", OperationDirection.IN, true);

        mockMvc.perform(patch("/internal/v1/limit-management/operation-types/{typeId}", type.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "SBP C2B updated",
                                  "familyCode": "FAST_PAYMENTS",
                                  "direction": "ALL",
                                  "enabled": false
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("SBP C2B updated"))
                .andExpect(jsonPath("$.data.familyCode").value("FAST_PAYMENTS"))
                .andExpect(jsonPath("$.data.direction").value("ALL"))
                .andExpect(jsonPath("$.data.enabled").value(false));
    }

    @Test
    void createsDraftRule() throws Exception {
        mockMvc.perform(post("/internal/v1/limit-management/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "code": "RULE_SBP_PHONE_DAY",
                                  "name": "SBP phone daily amount",
                                  "operationSelector": { "type": "FAMILY", "value": "SBP" },
                                  "direction": "IN",
                                  "attributeSelector": { "type": "PAYMENT_SYSTEM", "value": "MIR" },
                                  "targetType": "PHONE",
                                  "metric": "AMOUNT",
                                  "period": "DAY",
                                  "currency": "RUB"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value("RULE_SBP_PHONE_DAY"))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.operationSelector.type").value("FAMILY"))
                .andExpect(jsonPath("$.data.operationSelector.value").value("SBP"))
                .andExpect(jsonPath("$.data.attributeSelector.type").value("PAYMENT_SYSTEM"))
                .andExpect(jsonPath("$.data.attributeSelector.value").value("MIR"))
                .andExpect(jsonPath("$.data.targetType").value("PHONE"))
                .andExpect(jsonPath("$.data.currency").value("RUB"))
                .andExpect(jsonPath("$.data.enabled").value(false));
    }

    @Test
    void rejectsInvalidRuleSelector() throws Exception {
        mockMvc.perform(post("/internal/v1/limit-management/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "code": "RULE_UNKNOWN",
                                  "name": "Unknown selector",
                                  "operationSelector": { "type": "FAMILY", "value": "UNKNOWN" },
                                  "direction": "IN",
                                  "attributeSelector": { "type": "NONE", "value": null },
                                  "targetType": "PHONE",
                                  "metric": "AMOUNT",
                                  "period": "DAY",
                                  "currency": "RUB"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("RULE_SELECTOR_INVALID"));
    }

    @Test
    void listsRules() throws Exception {
        repository.addDraftRule("RULE_SBP_C2B_DAY");

        mockMvc.perform(get("/internal/v1/limit-management/rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].code").value("RULE_SBP_C2B_DAY"));
    }

    @Test
    void getsRule() throws Exception {
        LimitRule draft = repository.addDraftRule("RULE_SBP_C2B_DAY");

        mockMvc.perform(get("/internal/v1/limit-management/rules/{ruleId}", draft.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(draft.id().toString()))
                .andExpect(jsonPath("$.data.code").value("RULE_SBP_C2B_DAY"));
    }

    @Test
    void patchesDraftRule() throws Exception {
        LimitRule draft = repository.addDraftRule("RULE_SBP_C2B_DAY");

        mockMvc.perform(patch("/internal/v1/limit-management/rules/{ruleId}", draft.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Updated monthly count",
                                  "operationSelector": { "type": "ANY", "value": null },
                                  "direction": "ALL",
                                  "attributeSelector": { "type": "NONE", "value": null },
                                  "targetType": "ANY",
                                  "metric": "COUNT",
                                  "period": "MONTH",
                                  "currency": null
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Updated monthly count"))
                .andExpect(jsonPath("$.data.operationSelector.type").value("ANY"))
                .andExpect(jsonPath("$.data.targetType").value("ANY"))
                .andExpect(jsonPath("$.data.metric").value("COUNT"))
                .andExpect(jsonPath("$.data.period").value("MONTH"))
                .andExpect(jsonPath("$.data.currency").value(nullValue()));
    }

    @Test
    void activatesDraftRule() throws Exception {
        LimitRule draft = repository.addDraftRule("RULE_SBP_C2B_DAY");

        mockMvc.perform(post("/internal/v1/limit-management/rules/{ruleId}/activate", draft.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.enabled").value(true));
    }

    @Test
    void disablesActiveRule() throws Exception {
        LimitRule active = repository.addActiveRule("RULE_SBP_C2B_DAY");

        mockMvc.perform(post("/internal/v1/limit-management/rules/{ruleId}/disable", active.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISABLED"))
                .andExpect(jsonPath("$.data.enabled").value(false));
    }

    @Test
    void createsNewRuleVersion() throws Exception {
        LimitRule active = repository.addActiveRule("RULE_SBP_C2B_DAY");

        mockMvc.perform(post("/internal/v1/limit-management/rules/{ruleId}/new-version", active.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value("RULE_SBP_C2B_DAY"))
                .andExpect(jsonPath("$.data.version").value(2))
                .andExpect(jsonPath("$.data.status").value("DRAFT"));
    }

    @Test
    void mapsMissingRuleToProblem() throws Exception {
        mockMvc.perform(get("/internal/v1/limit-management/rules/{ruleId}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RULE_NOT_FOUND"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestSupport {

        @Bean
        @Primary
        FakeRepository fakeLimitRuleRepository() {
            return new FakeRepository();
        }

        @Bean("testLimitRuleService")
        @Primary
        LimitRuleService limitRuleService(FakeRepository repository, java.time.Clock clock) {
            return new LimitRuleService(repository, clock);
        }
    }

    static class FakeRepository implements LimitRuleRepository {

        private final List<OperationType> operationTypes = new ArrayList<>();
        private final List<LimitRule> rules = new ArrayList<>();
        private final Set<String> operationFamilies = Set.of("CARD", "SBP");
        private final Set<String> paymentSystems = Set.of("MIR", "VISA");

        FakeRepository clear() {
            operationTypes.clear();
            rules.clear();
            return this;
        }

        OperationType addOperationType(String code, OperationDirection direction, boolean enabled) {
            OperationType type = new OperationType(
                    UUID.randomUUID(),
                    code,
                    code,
                    "SBP",
                    direction,
                    enabled,
                    10,
                    Instant.parse("2026-05-27T09:00:00Z"),
                    Instant.parse("2026-05-27T09:00:00Z")
            );
            operationTypes.add(type);
            return type;
        }

        LimitRule addDraftRule(String code) {
            return addRule(code, 1, RuleStatus.DRAFT);
        }

        LimitRule addActiveRule(String code) {
            return addRule(code, 1, RuleStatus.ACTIVE);
        }

        private LimitRule addRule(String code, int version, RuleStatus status) {
            LimitRule rule = new LimitRule(
                    UUID.randomUUID(),
                    code,
                    version,
                    code,
                    new RuleSelector<>(OperationSelectorType.FAMILY, "SBP"),
                    OperationDirection.IN,
                    new RuleSelector<>(AttributeSelectorType.NONE, null),
                    LimitTargetType.PHONE,
                    RuleMetric.AMOUNT,
                    RulePeriod.DAY,
                    "RUB",
                    status,
                    Instant.parse("2026-05-27T09:00:00Z"),
                    Instant.parse("2026-05-27T09:00:00Z"),
                    status == RuleStatus.ACTIVE ? Instant.parse("2026-05-27T09:00:00Z") : null,
                    status == RuleStatus.DISABLED ? Instant.parse("2026-05-27T09:00:00Z") : null
            );
            rules.add(rule);
            return rule;
        }

        @Override
        public List<OperationType> listOperationTypes() {
            return List.copyOf(operationTypes);
        }

        @Override
        public RuleDictionaries getRuleDictionaries() {
            return new RuleDictionaries(
                    dictionaryItems(operationFamilies),
                    listOperationTypes(),
                    dictionaryItems(paymentSystems),
                    dictionaryItems(Set.of("RU")),
                    dictionaryItems(Set.of("TKB")),
                    dictionaryItems(Set.of("220220")),
                    dictionaryItems(Set.of("DEBIT", "CREDIT")),
                    dictionaryItems(Set.of("STANDARD", "GOLD")),
                    Arrays.asList(OperationDirection.values()),
                    Arrays.asList(OperationSelectorType.values()),
                    Arrays.asList(AttributeSelectorType.values()),
                    Arrays.asList(LimitTargetType.values()),
                    Arrays.asList(RuleMetric.values()),
                    Arrays.asList(RulePeriod.values())
            );
        }

        @Override
        public Optional<OperationType> findOperationType(UUID id) {
            return operationTypes.stream().filter(type -> type.id().equals(id)).findFirst();
        }

        @Override
        public Optional<OperationType> findOperationTypeByCode(String code) {
            return operationTypes.stream().filter(type -> type.code().equals(code)).findFirst();
        }

        @Override
        public boolean operationFamilyExists(String code) {
            return operationFamilies.contains(code);
        }

        @Override
        public boolean attributeValueExists(AttributeSelectorType type, String code) {
            return switch (type) {
                case NONE -> code == null;
                case PAYMENT_SYSTEM -> paymentSystems.contains(code);
                case ISSUER_COUNTRY -> "RU".equals(code);
                case BANK -> "TKB".equals(code);
                case BIN -> "220220".equals(code);
                case CARD_TYPE -> Set.of("DEBIT", "CREDIT").contains(code);
                case CARD_LEVEL -> Set.of("STANDARD", "GOLD").contains(code);
            };
        }

        @Override
        public OperationType saveOperationType(OperationType type) {
            operationTypes.add(type);
            return type;
        }

        @Override
        public OperationType updateOperationType(OperationType type) {
            operationTypes.replaceAll(existing -> existing.id().equals(type.id()) ? type : existing);
            return type;
        }

        @Override
        public boolean hasActiveRulesForOperationTypeCode(String operationTypeCode) {
            return rules.stream()
                    .filter(rule -> rule.status() == RuleStatus.ACTIVE)
                    .map(LimitRule::operationSelector)
                    .anyMatch(selector -> selector.type() == OperationSelectorType.TYPE
                            && operationTypeCode.equals(selector.value()));
        }

        @Override
        public List<LimitRule> listRules() {
            return List.copyOf(rules);
        }

        @Override
        public Optional<LimitRule> findRule(UUID id) {
            return rules.stream().filter(rule -> rule.id().equals(id)).findFirst();
        }

        @Override
        public Optional<LimitRule> findDraftByCode(String code) {
            return rules.stream()
                    .filter(rule -> rule.code().equals(code))
                    .filter(rule -> rule.status() == RuleStatus.DRAFT)
                    .findFirst();
        }

        @Override
        public Optional<LimitRule> findActiveByCode(String code) {
            return rules.stream()
                    .filter(rule -> rule.code().equals(code))
                    .filter(rule -> rule.status() == RuleStatus.ACTIVE)
                    .findFirst();
        }

        @Override
        public int nextVersion(String code) {
            return rules.stream()
                    .filter(rule -> rule.code().equals(code))
                    .map(LimitRule::version)
                    .max(Comparator.naturalOrder())
                    .orElse(0) + 1;
        }

        @Override
        public LimitRule saveRule(LimitRule rule) {
            rules.add(rule);
            return rule;
        }

        @Override
        public LimitRule updateRule(LimitRule rule) {
            rules.replaceAll(existing -> existing.id().equals(rule.id()) ? rule : existing);
            return rule;
        }

        private List<DictionaryItem> dictionaryItems(Set<String> codes) {
            return codes.stream()
                    .sorted()
                    .map(code -> new DictionaryItem(
                            code,
                            code,
                            true,
                            10,
                            Instant.parse("2026-05-27T09:00:00Z"),
                            Instant.parse("2026-05-27T09:00:00Z")
                    ))
                    .toList();
        }
    }
}
