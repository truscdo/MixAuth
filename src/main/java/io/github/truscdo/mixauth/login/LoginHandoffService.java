package io.github.truscdo.mixauth.login;

import com.mojang.authlib.GameProfile;
import io.github.truscdo.mixauth.compat.ProfileCompat;
import io.github.truscdo.mixauth.offline.OfflineAuthService;
import io.github.truscdo.mixauth.online.OnlineAuthService;
import net.minecraft.network.Connection;

import java.util.Objects;
import java.util.UUID;

public final class LoginHandoffService {
    private LoginHandoffService() {
    }

    public static boolean completeOnlineLogin(
            Connection connection,
            UUID requestedProfileId,
            GameProfile authenticatedProfile) {
        ProfileIdentity identity = validate(connection, requestedProfileId, authenticatedProfile);
        OnlineAuthService.recordOnlineLogin(authenticatedProfile);
        return publish(connection, requestedProfileId, identity, OnlineAuthService.LoginMode.ONLINE);
    }

    public static boolean completeOfflineLogin(
            Connection connection,
            UUID requestedProfileId,
            GameProfile canonicalOfflineProfile) {
        ProfileIdentity identity = validate(connection, requestedProfileId, canonicalOfflineProfile);
        OfflineAuthService.recordOfflineLogin(canonicalOfflineProfile, requestedProfileId);
        return publish(connection, requestedProfileId, identity, OnlineAuthService.LoginMode.OFFLINE);
    }

    private static ProfileIdentity validate(
            Connection connection,
            UUID requestedProfileId,
            GameProfile authenticatedProfile) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(requestedProfileId, "requestedProfileId");
        Objects.requireNonNull(authenticatedProfile, "authenticatedProfile");

        UUID authenticatedProfileId = ProfileCompat.uuid(authenticatedProfile);
        String authenticatedUsername = ProfileCompat.name(authenticatedProfile);
        if (authenticatedProfileId == null || authenticatedUsername == null || authenticatedUsername.isBlank()) {
            throw new IllegalArgumentException("Authenticated profile must have a UUID and username");
        }
        return new ProfileIdentity(authenticatedProfileId, authenticatedUsername);
    }

    private static boolean publish(
            Connection connection,
            UUID requestedProfileId,
            ProfileIdentity identity,
            OnlineAuthService.LoginMode mode) {
        return LoginContexts.publish(connection, new LoginContext(
                mode,
                requestedProfileId,
                identity.uuid(),
                identity.username(),
                System.currentTimeMillis()));
    }

    private record ProfileIdentity(UUID uuid, String username) {
    }
}
