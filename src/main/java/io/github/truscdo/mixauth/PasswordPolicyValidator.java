package io.github.truscdo.mixauth;

import java.util.ArrayList;
import java.util.List;

public final class PasswordPolicyValidator {

    public enum Error {
        TOO_SHORT,
        TOO_LONG,
        BLACKLISTED;
    }

    public record ValidationResult(boolean valid, List<Error> errors) {
    }

    private PasswordPolicyValidator() {
    }

    public static ValidationResult validate(String password) {
        List<Error> errors = new ArrayList<>(3);
        int minLength = AuthServerConfig.minPasswordLength();
        int maxLength = AuthServerConfig.maxPasswordLength();

        if (password.length() < minLength) {
            errors.add(Error.TOO_SHORT);
        }

        if (password.length() > maxLength) {
            errors.add(Error.TOO_LONG);
        }

        if (PasswordBlacklistLoader.isBlacklisted(password)) {
            errors.add(Error.BLACKLISTED);
        }

        return new ValidationResult(errors.isEmpty(), errors);
    }
}
