package ru.copperside.paylimits.management.merchantgroup.application;

import ru.copperside.paylimits.management.merchantgroup.application.port.out.MerchantGroupRepository;
import ru.copperside.paylimits.management.merchantgroup.domain.MerchantGroup;
import ru.copperside.paylimits.management.merchantgroup.domain.MerchantGroupMembership;
import ru.copperside.paylimits.management.merchantgroup.domain.MerchantGroupProblemException;
import ru.copperside.paylimits.management.merchantgroup.domain.MerchantGroupType;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

public class MerchantGroupService {

    private final MerchantGroupRepository repository;
    private final Clock clock;

    public MerchantGroupService(MerchantGroupRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public MerchantGroupType createType(CreateGroupTypeCommand command) {
        Instant now = Instant.now(clock);
        return repository.saveType(new MerchantGroupType(
                UUID.randomUUID(),
                requireText(command.code(), "code"),
                requireText(command.name(), "name"),
                blankToNull(command.description()),
                true,
                command.sortOrder(),
                now,
                now
        ));
    }

    public MerchantGroup createGroup(CreateGroupCommand command) {
        MerchantGroupType type = repository.findType(command.typeId())
                .orElseThrow(() -> problem("GROUP_TYPE_NOT_FOUND", "Group type not found"));
        if (!type.enabled()) {
            throw problem("GROUP_TYPE_DISABLED", "Group type is disabled");
        }
        Instant now = Instant.now(clock);
        return repository.saveGroup(new MerchantGroup(
                UUID.randomUUID(),
                type.id(),
                requireText(command.code(), "code"),
                requireText(command.name(), "name"),
                blankToNull(command.description()),
                true,
                now,
                now
        ));
    }

    public MerchantGroupMembership assignMembership(AssignMembershipCommand command) {
        MerchantGroup group = repository.findGroup(command.groupId())
                .orElseThrow(() -> problem("GROUP_NOT_FOUND", "Group not found"));
        MerchantGroupType type = repository.findType(group.typeId())
                .orElseThrow(() -> problem("GROUP_TYPE_NOT_FOUND", "Group type not found"));
        if (!type.enabled()) {
            throw problem("GROUP_TYPE_DISABLED", "Group type is disabled");
        }
        if (!group.enabled()) {
            throw problem("GROUP_DISABLED", "Group is disabled");
        }

        String merchantId = requireText(command.merchantId(), "merchantId");
        String actor = requireText(command.actor(), "actor");
        Instant validFrom = command.validFrom();
        Instant now = Instant.now(clock);

        repository.findActiveMembership(merchantId, type.id(), validFrom)
                .filter(existing -> !existing.groupId().equals(group.id()))
                .ifPresent(existing -> repository.closeMembership(existing.id(), validFrom, now, actor));

        return repository.saveMembership(new MerchantGroupMembership(
                UUID.randomUUID(),
                merchantId,
                group.id(),
                type.id(),
                validFrom,
                null,
                now,
                actor,
                null,
                null
        ));
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw problem("VALIDATION_ERROR", field + " must not be blank");
        }
        return value.trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private MerchantGroupProblemException problem(String code, String message) {
        return new MerchantGroupProblemException(code, message);
    }
}
