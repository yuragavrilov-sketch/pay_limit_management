package ru.copperside.paylimits.management.limitrule.domain;

public record RuleSelector<T extends Enum<T>>(T type, String value) {
}
