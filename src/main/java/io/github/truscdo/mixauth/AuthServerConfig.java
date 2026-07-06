package io.github.truscdo.mixauth;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

import java.time.Duration;

public final class AuthServerConfig {
    private static final long MILLIS_PER_SECOND = 1_000L;
    private static final long MILLIS_PER_MINUTE = 60L * MILLIS_PER_SECOND;
    private static final long MILLIS_PER_HOUR = 60L * MILLIS_PER_MINUTE;

    static final ServerValues VALUES;
    static final ModConfigSpec SPEC;

    static {
        Pair<ServerValues, ModConfigSpec> pair = new ModConfigSpec.Builder().configure(ServerValues::new);
        VALUES = pair.getLeft();
        SPEC = pair.getRight();
    }

    private AuthServerConfig() {
    }

    public static String databasePath() {
        return VALUES.databasePath.get();
    }

    public static int maxLoginAttempts() {
        return VALUES.maxLoginAttempts.get();
    }

    public static long tempBlockMillis() {
        return VALUES.tempBlockMinutes.get() * MILLIS_PER_MINUTE;
    }

    public static long trustedLoginWindowMillis() {
        return VALUES.trustedLoginWindowHours.get() * MILLIS_PER_HOUR;
    }

    public static long loginTimeoutMillis() {
        return VALUES.loginTimeoutMinutes.get() * MILLIS_PER_MINUTE;
    }

    public static long promptIntervalMillis() {
        return VALUES.promptIntervalSeconds.get() * MILLIS_PER_SECOND;
    }

    public static int bcryptCost() {
        return VALUES.bcryptCost.get();
    }

    public static int minPasswordLength() {
        return VALUES.minPasswordLength.get();
    }

    public static int maxPasswordLength() {
        return VALUES.maxPasswordLength.get();
    }

    public static String passwordBlacklistPath() {
        return VALUES.passwordBlacklistPath.get();
    }

    public static String defaultLanguage() {
        return AuthTranslations.normalizeSupportedLanguage(VALUES.defaultLanguage.get());
    }

    public static boolean autoDetectPlayerLanguage() {
        return VALUES.autoDetectPlayerLanguage.get();
    }

    public static Duration mojangConnectTimeout() {
        return Duration.ofSeconds(VALUES.mojangConnectTimeoutSeconds.get());
    }

    public static Duration mojangRequestTimeout() {
        return Duration.ofSeconds(VALUES.mojangRequestTimeoutSeconds.get());
    }

    public static Duration pendingHandshakeTtl() {
        return Duration.ofSeconds(VALUES.pendingHandshakeTtlSeconds.get());
    }

    private static boolean isNonBlankString(Object value) {
        return value instanceof String stringValue && !stringValue.isBlank();
    }

    private static boolean isSupportedLanguage(Object value) {
        return value instanceof String stringValue && AuthTranslations.isSupportedLanguage(stringValue);
    }

    static final class ServerValues {
        final ModConfigSpec.ConfigValue<String> databasePath;
        final ModConfigSpec.IntValue maxLoginAttempts;
        final ModConfigSpec.LongValue tempBlockMinutes;
        final ModConfigSpec.LongValue trustedLoginWindowHours;
        final ModConfigSpec.LongValue loginTimeoutMinutes;
        final ModConfigSpec.LongValue promptIntervalSeconds;
        final ModConfigSpec.IntValue bcryptCost;
        final ModConfigSpec.IntValue minPasswordLength;
        final ModConfigSpec.IntValue maxPasswordLength;
        final ModConfigSpec.ConfigValue<String> passwordBlacklistPath;
        final ModConfigSpec.IntValue mojangConnectTimeoutSeconds;
        final ModConfigSpec.IntValue mojangRequestTimeoutSeconds;
        final ModConfigSpec.IntValue pendingHandshakeTtlSeconds;
        final ModConfigSpec.ConfigValue<String> defaultLanguage;
        final ModConfigSpec.BooleanValue autoDetectPlayerLanguage;

        private ServerValues(ModConfigSpec.Builder builder) {
            builder.push("database");

            databasePath = builder
                    .comment(
                            "H2 database base path. Relative paths are resolved from the server root; H2 creates <path>.mv.db.")
                    .define("path", "auth/auth", AuthServerConfig::isNonBlankString);

            builder.pop();
            builder.push("offline_login");

            maxLoginAttempts = builder
                    .comment("Maximum password attempts before the account is temporarily blocked.")
                    .defineInRange("max_login_attempts", 3, 1, 100);
            tempBlockMinutes = builder
                    .comment("Temporary block duration, in minutes, after too many failed password attempts.")
                    .defineInRange("temporary_block_minutes", 5L, 1L, 7L * 24L * 60L);
            trustedLoginWindowHours = builder
                    .comment("Passwordless offline login window, in hours, for the same offline identity UUID and IP.")
                    .defineInRange("trusted_login_window_hours", 24L, 0L, 30L * 24L);
            loginTimeoutMinutes = builder
                    .comment(
                            "How long an already-registered offline player can stay pending before being disconnected.")
                    .defineInRange("login_timeout_minutes", 5L, 1L, 24L * 60L);
            promptIntervalSeconds = builder
                    .comment(
                            "How often to repeat the login or register prompt while a player is pending authentication.")
                    .defineInRange("prompt_interval_seconds", 5L, 1L, 300L);
            bcryptCost = builder
                    .comment("BCrypt work factor used when hashing offline passwords.")
                    .defineInRange("bcrypt_cost", 12, 4, 31);
            minPasswordLength = builder
                    .comment("Minimum password length. BCrypt input is limited to 72 bytes.")
                    .defineInRange("min_password_length", 1, 1, 72);
            maxPasswordLength = builder
                    .comment("Maximum password length. BCrypt truncates input beyond 72 bytes.")
                    .defineInRange("max_password_length", 72, 1, 72);
            passwordBlacklistPath = builder
                    .comment(
                            "Path to an external password blacklist file, one password per line. Lines starting with # are skipped. If the file does not exist, the built-in blacklist is copied to this path on first load. Relative paths are resolved from the server root.")
                    .define("password_blacklist_path", "auth/password_blacklist.txt");

            builder.pop();
            builder.push("online_validation");

            mojangConnectTimeoutSeconds = builder
                    .comment("HTTP connect timeout, in seconds, for Mojang session validation requests.")
                    .defineInRange("connect_timeout_seconds", 10, 1, 120);
            mojangRequestTimeoutSeconds = builder
                    .comment("HTTP request timeout, in seconds, for Mojang session validation requests.")
                    .defineInRange("request_timeout_seconds", 10, 1, 120);
            pendingHandshakeTtlSeconds = builder
                    .comment("How long a pending online handshake may remain valid before it is discarded.")
                    .defineInRange("pending_handshake_ttl_seconds", 120, 5, 600);

            builder.pop();
            builder.push("localization");

            defaultLanguage = builder
                    .comment(
                            "Default language used when player locale auto-detection is disabled or no supported locale is available.")
                    .define("default_language", "en_us", AuthServerConfig::isSupportedLanguage);
            autoDetectPlayerLanguage = builder
                    .comment("Whether to use the player's reported client language when it matches a supported locale.")
                    .define("auto_detect_player_language", true);

            builder.pop();
        }
    }
}