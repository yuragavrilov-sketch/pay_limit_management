package ru.copperside.paylimits.management.runtimeconfig.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeManifestPayload;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public class RuntimeManifestCanonicalJson {

    private final ObjectMapper objectMapper;

    public RuntimeManifestCanonicalJson() {
        this.objectMapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .build();
    }

    public byte[] bytes(RuntimeManifestPayload payload) {
        try {
            return objectMapper.writeValueAsBytes(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize runtime manifest payload", e);
        }
    }

    public String checksum(RuntimeManifestPayload payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return "sha256:" + HexFormat.of().formatHex(digest.digest(bytes(payload)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Cannot calculate runtime manifest checksum", e);
        }
    }
}
