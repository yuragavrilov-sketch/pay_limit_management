package ru.copperside.paylimits.management.merchantgroup.application;

public record PatchGroupTypeCommand(String name, String description, Boolean enabled, Integer sortOrder) {
}
