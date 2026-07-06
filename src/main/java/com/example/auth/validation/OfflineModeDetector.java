package com.example.auth.validation;

import com.example.auth.LogUtil;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 离线模式 UUID 检测器。
 *
 * <p>
 * 根据客户端提供的用户名和 UUID，判断该 UUID 是否属于离线模式。
 * 检测算法覆盖以下场景：
 * </p>
 *
 * <ul>
 * <li><b>标准离线 UUID</b> — 使用
 * {@code UUID.nameUUIDFromBytes("OfflinePlayer:" + username)} 重算对比（确定性）</li>
 * <li><b>PCL 专有离线 UUID</b> — 结构特征 + 用户名字长验证</li>
 * <li><b>未知类型</b> — 非标准离线 / 非 PCL，需进一步查验</li>
 * </ul>
 *
 * <p>
 * 返回两种定性结果：
 * </p>
 * <ul>
 * <li>{@link DetectionResult#CONFIRMED CONFIRMED}（确认）— 确定性判为离线模式 UUID</li>
 * <li>{@link DetectionResult#NEEDS_VERIFICATION NEEDS_VERIFICATION}（待查验）—
 * 无法本地确认，需走 API 或其他途径进一步验证</li>
 * </ul>
 */
public final class OfflineModeDetector {
    private static final Logger LOGGER = LogUtil.getLogger();
    private static final Pattern HEX32 = Pattern.compile("^[0-9a-f]{32}$");

    private OfflineModeDetector() {
    }

    /**
     * 检测定性结果。
     */
    public enum DetectionResult {
        /**
         * 确认是离线模式 UUID（本地确定性判断）。
         */
        CONFIRMED,
        /**
         * 无法本地确认，需要进一步查验（如查询 Mojang API）。
         */
        NEEDS_VERIFICATION
    }

    /**
     * UUID 类型详细分类。
     */
    public enum UuidType {
        /** 标准离线 UUID，{@code UUID.nameUUIDFromBytes("OfflinePlayer:" + username)} 匹配 */
        STANDARD_OFFLINE,
        /** PCL 专有离线 UUID（结构特征 + 用户名字长匹配） */
        PCL_OFFLINE,

        /** 无法识别的 UUID 类型（非标准离线、非 PCL） */
        UNKNOWN
    }

    /**
     * 检测结果详情。
     *
     * @param result  定性结果（确认 / 带查验）
     * @param type    UUID 类型详细分类
     */
    public record CheckResult(
            DetectionResult result,
            UuidType type) {
        /**
         * 快速判断此结果是否为"确认"。
         *
         * @return 当且仅当 {@code result == CONFIRMED}
         */
        public boolean isConfirmed() {
            return result == DetectionResult.CONFIRMED;
        }

        /**
         * 快速判断此结果是否为"带查验"。
         *
         * @return 当且仅当 {@code result == NEEDS_VERIFICATION}
         */
        public boolean needsVerification() {
            return result == DetectionResult.NEEDS_VERIFICATION;
        }
    }

    /**
     * 检测给定的用户名和 UUID 是否为离线模式。
     *
     * @param username 客户端提供的用户名
     * @param uuid     客户端提供的 UUID
     * @return 检测结果（{@link CheckResult}），包含定性结论和详细分类
     */
    public static CheckResult check(String username, UUID uuid) {
        // 参数基本校验
        if (username == null || username.isBlank()) {
            // 用户名为空，无法检测
            return new CheckResult(
                    DetectionResult.NEEDS_VERIFICATION,
                    UuidType.UNKNOWN);
        }
        if (uuid == null) {
            // UUID 为空，无法检测
            return new CheckResult(
                    DetectionResult.NEEDS_VERIFICATION,
                    UuidType.UNKNOWN);
        }

        String uuidStr = normalize(uuid);
        if (uuidStr == null) {
            // UUID 格式无效
            return new CheckResult(
                    DetectionResult.NEEDS_VERIFICATION,
                    UuidType.UNKNOWN);
        }

        // 1. 方案一：重算标准离线 UUID 对比 ★ 确定性
        CheckResult standardResult = checkStandardOffline(username, uuid, uuidStr);
        if (standardResult != null) {
            return standardResult;
        }

        // 2. 方案二：PCL 专有 UUID 检测
        CheckResult pclResult = checkPclOffline(username, uuidStr);
        if (pclResult != null) {
            return pclResult;
        }

        // 3. 兜底：未通过以上检测，需要进一步查验
        return new CheckResult(
                DetectionResult.NEEDS_VERIFICATION,
                UuidType.UNKNOWN);
    }

    // ========== 内部检测方法 ==========

    /**
     * 标准离线 UUID 检测：重算 {@code UUID.nameUUIDFromBytes("OfflinePlayer:" + username)}
     * 并比对。
     *
     * @return 如果匹配则返回 CONFIRMED + STANDARD_OFFLINE，否则返回 {@code null}
     */
    private static CheckResult checkStandardOffline(String username, UUID uuid, String uuidStr) {
        try {
            UUID expected = UUID.nameUUIDFromBytes(
                    ("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
            if (expected.equals(uuid)) {
                LOGGER.debug("Standard offline UUID confirmed for user '{}'", username);
                // 标准离线 UUID：UUID.nameUUIDFromBytes("OfflinePlayer:" + username) 匹配
                return new CheckResult(
                        DetectionResult.CONFIRMED,
                        UuidType.STANDARD_OFFLINE);
            }

            // 额外提供标准化后的字符串对比日志
            String expectedStr = normalize(expected);
            if (expectedStr != null && expectedStr.equals(uuidStr)) {
                // 理论上不会走到这里（equals 已匹配），但保留以覆盖极端情况
                LOGGER.debug("Standard offline UUID confirmed (string match) for user '{}'", username);
                // 标准离线 UUID：重算结果与客户端提供的 UUID 字符串一致
                return new CheckResult(
                        DetectionResult.CONFIRMED,
                        UuidType.STANDARD_OFFLINE);
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to recompute standard offline UUID for '{}'", username, e);
        }
        return null;
    }

    /**
     * PCL 专有离线 UUID 检测：结构特征 + 用户名字长验证。
     *
     * <p>
     * PCL UUID 结构（文档第 8.8 节）：
     * </p>
     * 
     * <pre>
     *   chars[0-11] + "3" + chars[13-15] + "9" + chars[17-31]
     *   前 12 位 + '3' = 用户名长度 hex（补 '0' 至 16 位）
     *   后 15 位（索引 17-31）为哈希 hex
     * </pre>
     *
     * @return 如果结构匹配且用户名字长一致，返回 CONFIRMED + PCL_OFFLINE；否则 {@code null}
     */
    private static CheckResult checkPclOffline(String username, String uuidStr) {
        // 特征 1：版本位必须为 '3'
        if (uuidStr.charAt(12) != '3') {
            return null;
        }
        // 特征 2：变体位必须为 '9'（PCL 固定写 '9'）
        if (uuidStr.charAt(16) != '9') {
            return null;
        }

        // 特征 3：重构用户名长度（恢复被 '3' 替换掉的原始 char[12] 为 '0'）
        // 拼接 chars[0-12) + '0' + chars[13-16) → 16 位 hex
        String lenHex = uuidStr.substring(0, 12) + "0" + uuidStr.substring(13, 16);
        int reconstructedLength;
        try {
            reconstructedLength = Integer.parseInt(lenHex, 16);
        } catch (NumberFormatException e) {
            LOGGER.warn("Failed to parse PCL length field from UUID: {}", lenHex);
            return null;
        }

        // Minecraft 用户名字长范围：3 ~ 16
        if (reconstructedLength < 3 || reconstructedLength > 16) {
            return null;
        }

        // 验证用户名长度是否匹配
        if (reconstructedLength != username.length()) {
            // 结构像 PCL 但长度不匹配 → 标记为 NEEDS_VERIFICATION
            LOGGER.debug("UUID structure resembles PCL but length mismatch: expected={}, actual={}",
                    reconstructedLength, username.length());
            // UUID 结构符合 PCL 特征，但推导的用户名长度与提供的用户名长度不匹配，无法确认
            return new CheckResult(
                    DetectionResult.NEEDS_VERIFICATION,
                    UuidType.UNKNOWN);
        }

        // 结构特征 + 长度都匹配 → 确认是 PCL 专有离线 UUID
        LOGGER.debug("PCL offline UUID confirmed for user '{}' (length={})", username, reconstructedLength);
        // PCL 专有离线 UUID：结构特征匹配，用户名字长一致
        return new CheckResult(
                DetectionResult.CONFIRMED,
                UuidType.PCL_OFFLINE);
    }

    // ========== 工具方法 ==========

    /**
     * 将 UUID 标准化为 32 位小写无连字符 hex 字符串。
     *
     * @return 标准化后的字符串，如果格式无效则返回 {@code null}
     */
    private static String normalize(UUID uuid) {
        String raw = uuid.toString().replace("-", "").toLowerCase(Locale.ROOT);
        if (raw.length() != 32 || !HEX32.matcher(raw).matches()) {
            return null;
        }
        return raw;
    }
}
