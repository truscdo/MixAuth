package io.github.truscdo.mixauth;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class PasswordPolicyValidator {

    /**
     * BCrypt 的硬性输入上限（UTF-8 字节）。超过该字节数的输入会被 BCrypt 静默截断，
     * 导致不同密码可能产生相同哈希，因此任何超限密码都必须在此拒绝。
     */
    public static final int MAX_BCRYPT_INPUT_BYTES = 72;

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
        return validate(password, AuthServerConfig.minPasswordLength(), AuthServerConfig.maxPasswordLength());
    }

    /**
     * 带显式长度参数的校验重载，便于在无 Minecraft 环境的单元测试中直接调用。
     *
     * @param password  待校验密码（非空）
     * @param minLength 最小字符长度
     * @param maxLength 最大字符长度
     */
    public static ValidationResult validate(String password, int minLength, int maxLength) {
        List<Error> errors = new ArrayList<>(4);

        if (password.length() < minLength) {
            errors.add(Error.TOO_SHORT);
        }

        // BCrypt 按 UTF-8 字节截断：字符长度合法但字节数超限的多字节密码同样必须拒绝。
        int byteLength = password.getBytes(StandardCharsets.UTF_8).length;
        if (password.length() > maxLength || byteLength > MAX_BCRYPT_INPUT_BYTES) {
            errors.add(Error.TOO_LONG);
        }

        if (PasswordBlacklistLoader.isBlacklisted(password)) {
            errors.add(Error.BLACKLISTED);
        }

        return new ValidationResult(errors.isEmpty(), errors);
    }
}
