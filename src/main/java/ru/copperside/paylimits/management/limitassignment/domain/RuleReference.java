package ru.copperside.paylimits.management.limitassignment.domain;

import java.util.UUID;

public record RuleReference(UUID id, boolean active) {
}
