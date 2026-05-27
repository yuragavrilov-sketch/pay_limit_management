package ru.copperside.paylimits.management.merchantgroup.application;

public record CreateGroupTypeCommand(String code, String name, String description, int sortOrder) {
}
