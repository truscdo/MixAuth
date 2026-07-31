package io.github.truscdo.mixauth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PasswordPolicyValidator} 的单元测试。
 *
 * <p>
 * 通过带显式长度参数的 {@link PasswordPolicyValidator#validate(String, int, int)}
 * 重载进行测试，避免触发 {@link AuthServerConfig} 静态初始化（其依赖 Minecraft 环境）。
 * 覆盖：字符长度边界、UTF-8 字节长度（BCrypt 72 字节上限）、黑名单命中。
 * </p>
 */
@DisplayName("PasswordPolicyValidator — 密码策略校验")
class PasswordPolicyValidatorTest {

    /** 参考默认配置值（与 AuthServerConfig 保持一致，仅用于测试）。 */
    private static final int MIN_LENGTH = 1;
    private static final int MAX_LENGTH = 72;

    /** 「中」：UTF-8 占 3 字节。 */
    private static final String CJK = "中";
    /** 😀：UTF-8 占 4 字节。 */
    private static final String EMOJI = "\uD83D\uDE00";

    @Nested
    @DisplayName("字符长度边界")
    class CharacterLength {

        @Test
        void passwordAtMinLengthIsValid() {
            assertTrue(PasswordPolicyValidator.validate("a", MIN_LENGTH, MAX_LENGTH).valid());
        }

        @Test
        void passwordBelowMinLengthIsRejected() {
            PasswordPolicyValidator.ValidationResult result = PasswordPolicyValidator.validate("", MIN_LENGTH,
                    MAX_LENGTH);
            assertFalse(result.valid());
            assertTrue(result.errors().contains(PasswordPolicyValidator.Error.TOO_SHORT));
        }

        @Test
        void passwordAtMaxCharacterLengthIsValid() {
            assertTrue(PasswordPolicyValidator.validate("a".repeat(MAX_LENGTH), MIN_LENGTH, MAX_LENGTH).valid());
        }

        @Test
        void passwordAboveMaxCharacterLengthIsRejected() {
            PasswordPolicyValidator.ValidationResult result = PasswordPolicyValidator
                    .validate("a".repeat(MAX_LENGTH + 1), MIN_LENGTH, MAX_LENGTH);
            assertFalse(result.valid());
            assertTrue(result.errors().contains(PasswordPolicyValidator.Error.TOO_LONG));
        }
    }

    @Nested
    @DisplayName("UTF-8 字节长度 — BCrypt 72 字节上限")
    class ByteLength {

        @Test
        void cjkPasswordAt72BytesIsValid() {
            // 24 个中文字符 = 72 字节，字符数 24 在限制内
            String password = CJK.repeat(24);
            assertEquals(72, password.getBytes(StandardCharsets.UTF_8).length);
            assertTrue(PasswordPolicyValidator.validate(password, MIN_LENGTH, MAX_LENGTH).valid());
        }

        @Test
        void cjkPasswordOver72BytesIsRejected() {
            // 25 个中文字符 = 75 字节 > 72；字符数 25 <= 72 也必须按字节拒绝
            String password = CJK.repeat(25);
            PasswordPolicyValidator.ValidationResult result = PasswordPolicyValidator.validate(password, MIN_LENGTH,
                    MAX_LENGTH);
            assertFalse(result.valid());
            assertTrue(result.errors().contains(PasswordPolicyValidator.Error.TOO_LONG));
        }

        @Test
        void emojiPasswordOver72BytesIsRejected() {
            // 19 个 emoji = 76 字节 > 72
            String password = EMOJI.repeat(19);
            PasswordPolicyValidator.ValidationResult result = PasswordPolicyValidator.validate(password, MIN_LENGTH,
                    MAX_LENGTH);
            assertFalse(result.valid());
            assertTrue(result.errors().contains(PasswordPolicyValidator.Error.TOO_LONG));
        }

        @Test
        void mixedMultibytePasswordOver72BytesIsRejected() {
            // 11 个中文 (33B) + 10 个 emoji (40B) = 73 字节 > 72
            String password = CJK.repeat(11) + EMOJI.repeat(10);
            PasswordPolicyValidator.ValidationResult result = PasswordPolicyValidator.validate(password, MIN_LENGTH,
                    MAX_LENGTH);
            assertFalse(result.valid());
            assertTrue(result.errors().contains(PasswordPolicyValidator.Error.TOO_LONG));
        }
    }

    @Nested
    @DisplayName("密码黑名单")
    class Blacklist {

        @Test
        void blacklistedPasswordIsRejected() throws Exception {
            injectBlacklist(Set.of("password", "123456"));
            try {
                PasswordPolicyValidator.ValidationResult result = PasswordPolicyValidator.validate("123456", MIN_LENGTH,
                        MAX_LENGTH);
                assertFalse(result.valid());
                assertTrue(result.errors().contains(PasswordPolicyValidator.Error.BLACKLISTED));
            } finally {
                injectBlacklist(Set.of());
            }
        }

        @Test
        void nonBlacklistedPasswordNotFlagged() throws Exception {
            injectBlacklist(Set.of("password"));
            try {
                PasswordPolicyValidator.ValidationResult result = PasswordPolicyValidator.validate("not-in-list",
                        MIN_LENGTH, MAX_LENGTH);
                assertFalse(result.errors().contains(PasswordPolicyValidator.Error.BLACKLISTED));
            } finally {
                injectBlacklist(Set.of());
            }
        }

        private static void injectBlacklist(Set<String> entries) throws Exception {
            Field field = PasswordBlacklistLoader.class.getDeclaredField("blacklist");
            field.setAccessible(true);
            field.set(null, entries);
        }
    }
}
