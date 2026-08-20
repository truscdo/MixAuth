package io.github.truscdo.mixauth.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link OfflineModeDetector} 的单元测试。
 *
 * <p>
 * 测试数据覆盖四类场景：
 * 标准离线 UUID（{@code UUID.nameUUIDFromBytes("OfflinePlayer:" + username)}
 * 重算对比，含带/不带连字符两种输入格式）；
 * PCL 专有离线 UUID（版本位 3 + 变体位 9 结构特征 + 用户名字长验证）；
 * 正版 UUID（v4 随机，版本位 4 → 需进一步查验）；
 * 以及边界/异常（null / blank / 空用户名、null UUID 等 → 需进一步查验）。
 * </p>
 *
 * @see OfflineModeDetector
 */
@DisplayName("OfflineModeDetector — 离线模式 UUID 检测")
class OfflineModeDetectorTest {

    // ========================================================================
    // 辅助工具方法
    // ========================================================================

    private static void assertConfirmedStandard(String username, String uuidStr) {
        OfflineModeDetector.CheckResult result = OfflineModeDetector.check(username, UUID.fromString(uuidStr));
        assertTrue(result.isConfirmed(), () -> "应为 CONFIRMED，但得到: " + result);
        assertEquals(OfflineModeDetector.UuidType.STANDARD_OFFLINE, result.type(),
                () -> "应为 STANDARD_OFFLINE，但得到: " + result.type());
    }

    private static void assertConfirmedPcl(String username, String uuidStr) {
        OfflineModeDetector.CheckResult result = OfflineModeDetector.check(username, UUID.fromString(uuidStr));
        assertTrue(result.isConfirmed(), () -> "应为 CONFIRMED，但得到: " + result);
        assertEquals(OfflineModeDetector.UuidType.PCL_OFFLINE, result.type(),
                () -> "应为 PCL_OFFLINE，但得到: " + result.type());
    }

    private static void assertNeedsVerification(String username, String uuidStr) {
        OfflineModeDetector.CheckResult result = OfflineModeDetector.check(username, UUID.fromString(uuidStr));
        assertTrue(result.needsVerification(), () -> "应为 NEEDS_VERIFICATION，但得到: " + result);
        assertEquals(OfflineModeDetector.UuidType.UNKNOWN, result.type(),
                () -> "应为 UNKNOWN，但得到: " + result.type());
    }

    // ========================================================================
    // Nested: 标准离线 UUID（第五节 1.1 表）
    // ========================================================================

    @Nested
    @DisplayName("标准离线 UUID — UUID.nameUUIDFromBytes(\"OfflinePlayer:\" + name)")
    class StandardOffline {

        static Stream<Arguments> standardOfflineData() {
            // @formatter:off
            return Stream.of(
                    Arguments.of("Alex",        "36532b5e-c442-3dbb-a24c-c7e55d0f979a"),
                    Arguments.of("Steve",       "5627dd98-e6be-3c21-b8a8-e92344183641"),
                    Arguments.of("Notch",       "b50ad385-829d-3141-a216-7e7d7539ba7f"),
                    Arguments.of("Herobrine",   "25966168-dc9c-360c-8f32-ed022bfa1070"),
                    Arguments.of("jeb_",        "a762f560-4fce-3236-812a-b80efff0b62b"),
                    Arguments.of("Dinnerbone",  "4d258a81-2358-3084-8166-05b9faccad80"),
                    Arguments.of("Grum",        "24eeb186-b908-3cab-bb31-7affca83e7c9"),
                    Arguments.of("MHF_Steve",   "a91a45dc-530a-3f14-b7bb-2af8ea68a41f"),
                    Arguments.of("Player",      "a01e3843-e521-3998-958a-f459800e4d11"),
                    Arguments.of("test",        "530fa97a-357f-3c19-94d3-0c5c65c18fe8"),
                    Arguments.of("Steve_123",   "77cae0ff-71ba-39ba-b80c-74428607dfcc"),
                    Arguments.of("TestPlayer",  "bb77495a-a740-3169-a238-69654c8bd2c1")
            );
            // @formatter:on
        }

        @ParameterizedTest(name = "[{index}] username=\"{0}\"")
        @MethodSource("standardOfflineData")
        @DisplayName("确认标准离线 UUID（用户名 + UUID 均匹配）")
        void confirmStandardOffline(String username, String uuidStr) {
            assertConfirmedStandard(username, uuidStr);
        }

        @ParameterizedTest(name = "[{index}] username=\"{0}\"")
        @MethodSource("standardOfflineData")
        @DisplayName("无连符 32 位格式也应正确识别")
        void confirmStandardOfflineNoDash(String username, String uuidStr) {
            // 无连符的 UUID 字符串
            String noDash = uuidStr.replace("-", "");
            // 手动构造 UUID（UUID.fromString 在某些 Java 版本不接受纯 32 位 hex）
            long msb = new java.math.BigInteger(noDash.substring(0, 16), 16).longValue();
            long lsb = new java.math.BigInteger(noDash.substring(16), 16).longValue();
            UUID uuid = new UUID(msb, lsb);
            OfflineModeDetector.CheckResult result = OfflineModeDetector.check(username, uuid);
            assertTrue(result.isConfirmed(), () -> "无连符格式也应 CONFIRMED，但得到: " + result);
            assertEquals(OfflineModeDetector.UuidType.STANDARD_OFFLINE, result.type());
        }
    }

    // ========================================================================
    // Nested: 标准离线 — 错误用户名应返回 NEEDS_VERIFICATION
    // ========================================================================

    @Nested
    @DisplayName("标准离线 UUID — 用户名不匹配")
    class StandardOfflineMismatch {

        static Stream<Arguments> mismatchData() {
            // 拿 Steve 的 UUID 配不同用户名
            String steveUuid = "5627dd98-e6be-3c21-b8a8-e92344183641";
            return Stream.of(
                    Arguments.of("Alex", steveUuid),
                    Arguments.of("Notch", steveUuid),
                    Arguments.of("Player", steveUuid),
                    Arguments.of("test", steveUuid));
        }

        @ParameterizedTest(name = "[{index}] username=\"{0}\" with Steve's UUID")
        @MethodSource("mismatchData")
        @DisplayName("标准离线 UUID 但用户名不匹配 → NEEDS_VERIFICATION")
        void mismatchUsername(String username, String uuidStr) {
            assertNeedsVerification(username, uuidStr);
        }
    }

    // ========================================================================
    // Nested: PCL 专有离线 UUID（第五节 2.3 表）
    // ========================================================================

    @Nested
    @DisplayName("PCL 专有离线 UUID — GetStableHashCode 算法")
    class PclOffline {

        static Stream<Arguments> pclData() {
            // @formatter:off
            return Stream.of(
                    Arguments.of("Alex",        "00000000-0000-3004-998f-501a96f4269a"),
                    Arguments.of("Steve",       "00000000-0000-3005-998f-503099dcf29b"),
                    Arguments.of("Notch",       "00000000-0000-3005-998f-503098015b54"),
                    Arguments.of("Herobrine",   "00000000-0000-3009-9b3a-be36132d0408"),
                    Arguments.of("jeb_",        "00000000-0000-3004-998f-501a96e12b18"),
                    Arguments.of("Dinnerbone",  "00000000-0000-300a-9d86-fb6bd1a63e76"),
                    Arguments.of("Grum",        "00000000-0000-3004-998f-501a96f74447"),
                    Arguments.of("Player",      "00000000-0000-3006-998f-555b7570e899"),
                    Arguments.of("test",        "00000000-0000-3004-998f-501a96ee52dc"),
                    Arguments.of("Steve_123",   "00000000-0000-3009-9b3a-a5bf82b2c754"),
                    Arguments.of("TestPlayer",  "00000000-0000-300a-9d84-e7824369704f")
            );
            // @formatter:on
        }

        @ParameterizedTest(name = "[{index}] username=\"{0}\"")
        @MethodSource("pclData")
        @DisplayName("确认 PCL 专有离线 UUID（结构特征 + 用户名字长匹配）")
        void confirmPclOffline(String username, String uuidStr) {
            assertConfirmedPcl(username, uuidStr);
        }
    }

    // ========================================================================
    // Nested: PCL — 用户名长度不匹配
    // ========================================================================

    @Nested
    @DisplayName("PCL 专有离线 UUID — 用户名长度不匹配")
    class PclOfflineMismatch {

        static Stream<Arguments> pclMismatchData() {
            // Alex 的 PCL UUID 长度字段 = 0x004 = 4，但使用不同长度的用户名
            String alexPclUuid = "00000000-0000-3004-998f-501a96f4269a";
            return Stream.of(
                    Arguments.of("AlexX", alexPclUuid), // 长度 5
                    Arguments.of("Al", alexPclUuid) // 长度 2（非法用户名但 UUID 结构匹配）
            );
        }

        @ParameterizedTest(name = "[{index}] username=\"{0}\"")
        @MethodSource("pclMismatchData")
        @DisplayName("PCL 结构 UUID 但用户名长度不匹配 → NEEDS_VERIFICATION")
        void mismatchLength(String username, String uuidStr) {
            OfflineModeDetector.CheckResult result = OfflineModeDetector.check(username, UUID.fromString(uuidStr));
            assertTrue(result.needsVerification(),
                    () -> "长度不匹配应为 NEEDS_VERIFICATION，但得到: " + result);
            // 不是 CONFIRMED 的 PCL_OFFLINE（因为长度不匹配判定为 UNKNOWN）
            assertNotEquals(OfflineModeDetector.UuidType.PCL_OFFLINE, result.type());
        }
    }

    // ========================================================================
    // Nested: 正版 UUID（第五节 3.1 表 + 3.2 模拟）
    // ========================================================================

    @Nested
    @DisplayName("正版 UUID — v4 随机 UUID 应判定为 NEEDS_VERIFICATION")
    class PremiumUuid {

        static Stream<Arguments> premiumData() {
            // @formatter:off
            return Stream.of(
                    // Mojang 公开账号（正版）
                    Arguments.of("jeb_",        "853c80ef-3c37-49fd-aa49-938b674adae6"),
                    Arguments.of("Notch",       "069a79f4-44e9-4726-a5be-fca90e38aaf5"),
                    Arguments.of("Dinnerbone",  "b6e2c956-1e0b-46a7-abe3-895a2ac3b1b0"),
                    Arguments.of("Grum",        "b0ec5ca4-28a8-4792-b8aa-e6629bb2789b"),
                    // 随机生成的正版模拟 UUID
                    Arguments.of("模拟-1",       "d83f1337-90dd-459b-9be7-e8262e3725d3"),
                    Arguments.of("模拟-2",       "385f94fd-8d98-4e27-96b0-d7794c757ef6"),
                    Arguments.of("模拟-3",       "8e0fa39e-439e-4443-b22b-f8f47bfe924e")
            );
            // @formatter:on
        }

        @ParameterizedTest(name = "[{index}] username=\"{0}\"")
        @MethodSource("premiumData")
        @DisplayName("v4 UUID 不是离线模式 → NEEDS_VERIFICATION + UNKNOWN")
        void confirmNeedsVerification(String username, String uuidStr) {
            assertNeedsVerification(username, uuidStr);
        }

        @Test
        @DisplayName("正版 UUID 第 13 位应为 '4'（v4 标识）")
        void premiumUuidVersionIs4() {
            // 验证正版 UUID 的版本位确实是 '4'
            String[] premiumUuids = {
                    "853c80ef-3c37-49fd-aa49-938b674adae6",
                    "069a79f4-44e9-4726-a5be-fca90e38aaf5",
                    "b6e2c956-1e0b-46a7-abe3-895a2ac3b1b0",
                    "b0ec5ca4-28a8-4792-b8aa-e6629bb2789b"
            };
            for (String uuid : premiumUuids) {
                assertEquals('4', uuid.charAt(14), // 带连符格式第14位是版本位
                        () -> uuid + " 的版本位应为 '4'");
            }
        }
    }

    // ========================================================================
    // Nested: 边界 / 异常情况
    // ========================================================================

    @Nested
    @DisplayName("边界与异常情况")
    class EdgeCases {

        @Test
        @DisplayName("null userName → NEEDS_VERIFICATION")
        void nullUsername() {
            OfflineModeDetector.CheckResult result = OfflineModeDetector.check(null,
                    UUID.fromString("36532b5e-c442-3dbb-a24c-c7e55d0f979a"));
            assertTrue(result.needsVerification());
            assertEquals(OfflineModeDetector.UuidType.UNKNOWN, result.type());
        }

        @Test
        @DisplayName("null UUID → NEEDS_VERIFICATION")
        void nullUuid() {
            OfflineModeDetector.CheckResult result = OfflineModeDetector.check("Alex", null);
            assertTrue(result.needsVerification());
            assertEquals(OfflineModeDetector.UuidType.UNKNOWN, result.type());
        }

        @Test
        @DisplayName("blank userName → NEEDS_VERIFICATION")
        void blankUsername() {
            OfflineModeDetector.CheckResult result = OfflineModeDetector.check("  ",
                    UUID.fromString("36532b5e-c442-3dbb-a24c-c7e55d0f979a"));
            assertTrue(result.needsVerification());
            assertEquals(OfflineModeDetector.UuidType.UNKNOWN, result.type());
        }

        @Test
        @DisplayName("空字符串 userName → NEEDS_VERIFICATION")
        void emptyUsername() {
            OfflineModeDetector.CheckResult result = OfflineModeDetector.check("",
                    UUID.fromString("36532b5e-c442-3dbb-a24c-c7e55d0f979a"));
            assertTrue(result.needsVerification());
            assertEquals(OfflineModeDetector.UuidType.UNKNOWN, result.type());
        }
    }

    // ========================================================================
    // Nested: CheckResult 记录的方法测试
    // ========================================================================

    @Nested
    @DisplayName("CheckResult 记录行为")
    class CheckResultBehavior {

        @Test
        @DisplayName("isConfirmed() 与 needsVerification() 互斥")
        void mutuallyExclusive() {
            var confirmed = new OfflineModeDetector.CheckResult(
                    OfflineModeDetector.DetectionResult.CONFIRMED,
                    OfflineModeDetector.UuidType.STANDARD_OFFLINE);
            assertTrue(confirmed.isConfirmed());
            assertFalse(confirmed.needsVerification());

            var needsVerify = new OfflineModeDetector.CheckResult(
                    OfflineModeDetector.DetectionResult.NEEDS_VERIFICATION,
                    OfflineModeDetector.UuidType.UNKNOWN);
            assertFalse(needsVerify.isConfirmed());
            assertTrue(needsVerify.needsVerification());
        }
    }

    // ========================================================================
    // Nested: 混合场景 — 快速区分指南（第 4 节）
    // ========================================================================

    @Nested
    @DisplayName("快速区分指南（第4节）")
    class QuickGuide {

        @Test
        @DisplayName("标准离线: 版本位=3, 变体=由MD5决定")
        void standardOfflineVersionAndVariant() {
            // Alex 标准离线: 36532b5e-c442-3dbb-a24c-c7e55d0f979a
            // 无连符: 36532b5ec4423dbba24cc7e55d0f979a
            // 第12位(0-based)=3, 第16位(0-based)=a
            String uuid = "36532b5e-c442-3dbb-a24c-c7e55d0f979a".replace("-", "");
            assertEquals('3', uuid.charAt(12), "版本位应为 3");
            // 变体位由 MD5 决定，Alex 的是 'a'
            assertTrue("89ab".indexOf(uuid.charAt(16)) >= 0,
                    "标准离线变体位应为 8/9/a/b 之一");
        }

        @Test
        @DisplayName("PCL: 版本位=3, 变体=9（硬编码）")
        void pclVersionAndVariant() {
            // Alex PCL: 00000000-0000-3004-998f-501a96f4269a
            // 无连符: 0000000000003004998f501a96f4269a
            String uuid = "00000000-0000-3004-998f-501a96f4269a".replace("-", "");
            assertEquals('3', uuid.charAt(12), "版本位应为 3");
            assertEquals('9', uuid.charAt(16), "PCL 变体位硬编码为 9");
        }

        @Test
        @DisplayName("正版: 版本位=4")
        void premiumVersion4() {
            String uuid = "853c80ef-3c37-49fd-aa49-938b674adae6".replace("-", "");
            assertEquals('4', uuid.charAt(12), "正版 UUID 版本位应为 4");
        }
    }
}
