package ru.copperside.paylimits.management.limitrule.application.port.out;

import ru.copperside.paylimits.management.limitrule.domain.LimitRule;
import ru.copperside.paylimits.management.limitrule.domain.RuleDictionaries;
import ru.copperside.paylimits.management.limitrule.domain.RuleManifest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

public interface RuleManifestRepository {

    List<LimitRule> listActiveRulesForCompilation();

    RuleDictionaries getRuleDictionaries();

    RuleManifest saveNextManifest(Function<Integer, RuleManifest> manifestFactory);

    Optional<RuleManifest> findLatestManifest();

    Optional<RuleManifest> findManifest(UUID id);
}
