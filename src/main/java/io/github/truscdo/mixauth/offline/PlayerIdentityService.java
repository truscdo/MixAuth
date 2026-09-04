package io.github.truscdo.mixauth.offline;

import com.mojang.authlib.GameProfile;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class PlayerIdentityService {
    private PlayerIdentityService() {
    }

    public static UUID resolvePlayerUuid(String username) {
        return resolveServerGeneratedOfflineUuid(username);
    }

    public static GameProfile createOfflineProfile(String username) {
        return new GameProfile(resolvePlayerUuid(username), username);
    }

    private static UUID resolveServerGeneratedOfflineUuid(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Missing username for server-generated offline UUID");
        }

        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
    }
}
