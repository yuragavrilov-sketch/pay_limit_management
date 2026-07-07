package ru.copperside.paylimits.management.runtimeconfig.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import ru.copperside.paylimits.management.runtimeconfig.domain.RuntimeManifestPayload;
import ru.copperside.paylimits.management.runtimeconfig.domain.wire.ManifestDocumentV2;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Canonical serialization + checksum for the runtime manifest.
 *
 * <p>The <strong>checksum</strong> is computed over the engine-facing {@link ManifestDocumentV2}
 * (tech-spec §4.3 shape), NOT over the internal payload — so what engine can recompute from the GET
 * document byte-for-byte is exactly what management hashed. Canonical form: Jackson keys sorted
 * alphabetically at every level, ISO-8601 instants, explicit nulls serialized (deterministic), value
 * = {@code "sha256:" + hex}.
 *
 * <p>{@link #payloadBytes(RuntimeManifestPayload)} serializes the internal payload verbatim and is
 * used only for the {@code payload_json} storage column (which retains the full internal model for
 * faithful read-back / rollback); it is never the hashed representation.
 */
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

    /**
     * Serializes the internal payload verbatim — used for the {@code payload_json} storage column,
     * never for the checksum.
     */
    public byte[] payloadBytes(RuntimeManifestPayload payload) {
        try {
            return objectMapper.writeValueAsBytes(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize runtime manifest payload", e);
        }
    }

    /** Canonical bytes of the engine-facing §4.3 document — the exact input to the checksum. */
    public byte[] documentBytes(ManifestDocumentV2 document) {
        try {
            return objectMapper.writeValueAsBytes(document);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize runtime manifest document", e);
        }
    }

    public byte[] documentBytes(RuntimeManifestPayload payload) {
        return documentBytes(ManifestDocumentV2Mapper.toDocument(payload));
    }

    public String checksum(ManifestDocumentV2 document) {
        return sha256(documentBytes(document));
    }

    public String checksum(RuntimeManifestPayload payload) {
        return checksum(ManifestDocumentV2Mapper.toDocument(payload));
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return "sha256:" + HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Cannot calculate runtime manifest checksum", e);
        }
    }
}
