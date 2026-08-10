package io.github.truscdo.mixauth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PasswordHasher} 的单元测试。
 *
 * <p>
 * 纯 JUnit 环境（无 Minecraft 依赖）。覆盖：hash/verify 往返、盐随机性、
 * cost 编码、错误密码、null/空/非法哈希 fail-closed、多字节密码 UTF-8 处理。
 * </p>
 */
@DisplayName("PasswordHasher — BCrypt 哈希与校验")
class PasswordHasherTest {

    /** 测试用 work factor：取配置允许的最小值，兼顾真实性与速度。 */
    private static final int COST = 6;

    @Nested
    @DisplayName("hash → verify 往返")
    class RoundTrip {

        @Test
        void correctPasswordVerifies() {
            String hash = PasswordHasher.hash("correct horse battery staple", COST);
            assertTrue(PasswordHasher.verify("correct horse battery staple", hash));
        }

        @Test
        void wrongPasswordRejected() {
            String hash = PasswordHasher.hash("password-a", COST);
            assertFalse(PasswordHasher.verify("password-b", hash));
        }

        @Test
        void emptyPasswordDoesNotMatchHashOfNonEmpty() {
            String hash = PasswordHasher.hash("non-empty", COST);
            assertFalse(PasswordHasher.verify("", hash));
        }
    }

    @Nested
    @DisplayName("哈希特征")
    class HashCharacteristics {

        @Test
        void hashEncodesRequestedCost() {
            // BCrypt 哈希格式：$2a$<两位补零 cost>$...
            String expectedPrefix = "$2a$" + String.format("%02d", COST) + "$";
            assertTrue(PasswordHasher.hash("password", COST).startsWith(expectedPrefix));
        }

        @Test
        void samePasswordProducesDifferentSalt() {
            String hashA = PasswordHasher.hash("password", COST);
            String hashB = PasswordHasher.hash("password", COST);
            assertNotEquals(hashA, hashB);
            // 两次哈希均可验证同一密码
            assertTrue(PasswordHasher.verify("password", hashA));
            assertTrue(PasswordHasher.verify("password", hashB));
        }
    }

    @Nested
    @DisplayName("fail-closed 行为")
    class FailClosed {

        @Test
        void nullHashRejected() {
            assertFalse(PasswordHasher.verify("password", null));
        }

        @Test
        void blankHashRejected() {
            assertFalse(PasswordHasher.verify("password", "   "));
        }

        @Test
        void malformedHashRejectedWithoutException() {
            assertFalse(PasswordHasher.verify("password", "not-a-bcrypt-hash"));
        }
    }

    @Nested
    @DisplayName("多字节密码（UTF-8）")
    class Multibyte {

        @Test
        void cjkPasswordRoundTrips() {
            String password = "中文密码测试";
            String hash = PasswordHasher.hash(password, COST);
            assertTrue(PasswordHasher.verify(password, hash));
        }

        @Test
        void emojiPasswordRoundTrips() {
            String password = "密码\uD83D\uDE00安全";
            String hash = PasswordHasher.hash(password, COST);
            assertTrue(PasswordHasher.verify(password, hash));
        }

        @Test
        void passwordAt72BytesRoundTrips() {
            // 24 个中文字符 = 72 字节（BCrypt 上限边界内）
            String password = "中".repeat(24);
            assertEquals(72, password.getBytes(StandardCharsets.UTF_8).length);
            String hash = PasswordHasher.hash(password, COST);
            assertTrue(PasswordHasher.verify(password, hash));
        }
    }
}
