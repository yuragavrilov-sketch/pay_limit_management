package ru.copperside.paylimits.management.merchantgroup.application;

public record PatchGroupCommand(String name, String description, Boolean enabled) {
}
