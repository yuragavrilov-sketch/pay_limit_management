package ru.copperside.paylimits.management.limitrule.adapter.in.web;

import ru.copperside.paylimits.management.limitrule.domain.CounterpartyType;

import java.util.Arrays;
import java.util.List;

public record CounterpartyTypeResponse(String code, String name) {
    public static List<CounterpartyTypeResponse> all() {
        return Arrays.stream(CounterpartyType.values())
                .map(type -> new CounterpartyTypeResponse(type.name(), localize(type)))
                .toList();
    }

    private static String localize(CounterpartyType type) {
        return switch (type) {
            case CARD -> "Карта";
            case PHONE -> "Телефон";
            case ACCOUNT -> "Счёт";
        };
    }
}
