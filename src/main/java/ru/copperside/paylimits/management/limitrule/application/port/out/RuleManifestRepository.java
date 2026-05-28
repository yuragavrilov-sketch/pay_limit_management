package ru.copperside.paylimits.management.limitrule.application.port.out;

import ru.copperside.paylimits.management.limitrule.domain.LimitRule;
import ru.copperside.paylimits.management.limitrule.domain.RuleDictionaries;
import ru.copperside.paylimits.management.limitrule.domain.RuleManifest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RuleManifestRepository {

    List<LimitRule> listActiveRulesForCompilation();

    RuleDictionaries getRuleDictionaries();

    RuleManifest saveCompiledManifest(CompiledManifestFactory factory);

    Optional<RuleManifest> findLatestManifest();

    Optional<RuleManifest> findManifest(UUID id);

    @FunctionalInterface
    interface CompiledManifestFactory {

        RuleManifest create(int version, List<LimitRule> activeRules, RuleDictionaries dictionaries);
    }
}
