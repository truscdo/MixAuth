package io.github.truscdo.mixauth.loginchain.testmock;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

final class MockIdentity {
    private MockIdentity() {
    }

    static UUID yggdrasilUuid(String username) {
        return UUID.nameUUIDFromBytes(
                ("YggdrasilTest:" + username).getBytes(StandardCharsets.UTF_8));
    }

    static String noDashes(UUID uuid) {
        return uuid.toString().replace("-", "");
    }

    static String normalizeUuid(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.replace("-", "").toLowerCase();
        if (normalized.length() != 32 || !normalized.matches("[0-9a-f]{32}")) {
            return null;
        }
        return normalized;
    }
}
