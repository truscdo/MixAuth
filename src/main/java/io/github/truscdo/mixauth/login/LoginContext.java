package io.github.truscdo.mixauth.login;

import io.github.truscdo.mixauth.online.OnlineAuthService;

import java.util.Objects;
import java.util.UUID;

public record LoginContext(
        OnlineAuthService.LoginMode mode,
        UUID requestedProfileId,
        UUID authenticatedProfileId,
        String authenticatedUsername,
        long createdAtMillis) {

    public LoginContext {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(requestedProfileId, "requestedProfileId");
        Objects.requireNonNull(authenticatedProfileId, "authenticatedProfileId");
        Objects.requireNonNull(authenticatedUsername, "authenticatedUsername");
        if (authenticatedUsername.isBlank()) {
            throw new IllegalArgumentException("authenticatedUsername must not be blank");
        }
    }
}
