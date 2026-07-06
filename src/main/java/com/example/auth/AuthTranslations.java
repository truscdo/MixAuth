package com.example.auth;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.IllegalFormatException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class AuthTranslations {
    // Language code → locale prefix for auto-detection fallback.
    // To add a language: create <code>.json in assets/auth/lang/, then add an entry
    // here.
    private static final Map<String, String> LANGUAGES = new LinkedHashMap<>();

    static {
        LANGUAGES.put("zh_cn", "zh");
        LANGUAGES.put("en_us", "en");
    }

    private static final Logger LOGGER = LogUtil.getLogger();
    private static final Set<String> SUPPORTED_LANGUAGES = LANGUAGES.keySet();

    private AuthTranslations() {
    }

    public static boolean isSupportedLanguage(String value) {
        return normalizeSupportedLanguage(value) != null;
    }

    public static String normalizeSupportedLanguage(String value) {
        if (value == null) {
            return null;
        }

        String normalized = value.strip().toLowerCase(Locale.ROOT).replace('-', '_');
        return SUPPORTED_LANGUAGES.contains(normalized) ? normalized : null;
    }

    public static String resolveLanguage(ServerPlayer player) {
        if (player == null || !AuthServerConfig.autoDetectPlayerLanguage()) {
            return configuredLanguage();
        }

        return resolveReportedLanguage(player.getLanguage());
    }

    public static String resolveLanguage(CommandSourceStack source) {
        if (source.getEntity() instanceof ServerPlayer player) {
            return resolveLanguage(player);
        }

        return configuredLanguage();
    }

    public static Component componentForPlayer(ServerPlayer player, String key, Object... args) {
        return Component.literal(textForLanguage(resolveLanguage(player), key, args));
    }

    public static Component componentForSource(CommandSourceStack source, String key, Object... args) {
        return Component.literal(textForLanguage(resolveLanguage(source), key, args));
    }

    public static Component componentForConfiguredLanguage(String key, Object... args) {
        return Component.literal(textForConfiguredLanguage(key, args));
    }

    public static String textForSource(CommandSourceStack source, String key, Object... args) {
        return textForLanguage(resolveLanguage(source), key, args);
    }

    public static String textForConfiguredLanguage(String key, Object... args) {
        return textForLanguage(configuredLanguage(), key, args);
    }

    public static String textForLanguage(String language, String key, Object... args) {
        String template = resolveTemplate(language, key);
        if (args == null || args.length == 0) {
            return template;
        }

        try {
            return template.formatted(args);
        } catch (IllegalFormatException illegalFormatException) {
            LOGGER.error("Failed to format auth translation {} for language {}", key, language, illegalFormatException);
            return template;
        }
    }

    public static String formatDuration(String language, long durationMillis) {
        long totalSeconds = Math.max(1L, (durationMillis + 999L) / 1000L);
        long days = totalSeconds / (24L * 60L * 60L);
        totalSeconds %= 24L * 60L * 60L;
        long hours = totalSeconds / (60L * 60L);
        totalSeconds %= 60L * 60L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        if (days > 0L) {
            return hours > 0L
                    ? textForLanguage(language, "auth.duration.days_hours", days, hours)
                    : textForLanguage(language, "auth.duration.days_only", days);
        }
        if (hours > 0L) {
            return minutes > 0L
                    ? textForLanguage(language, "auth.duration.hours_minutes", hours, minutes)
                    : textForLanguage(language, "auth.duration.hours_only", hours);
        }
        if (minutes > 0L && seconds > 0L) {
            return textForLanguage(language, "auth.duration.minutes_seconds", minutes, seconds);
        }
        if (minutes > 0L) {
            return textForLanguage(language, "auth.duration.minutes_only", minutes);
        }
        return textForLanguage(language, "auth.duration.seconds_only", totalSeconds);
    }

    private static String configuredLanguage() {
        return AuthServerConfig.defaultLanguage();
    }

    private static String resolveReportedLanguage(String reportedLanguage) {
        String normalized = normalizeSupportedLanguage(reportedLanguage);
        if (normalized != null) {
            return normalized;
        }

        if (reportedLanguage != null) {
            String normalizedReportedLanguage = reportedLanguage.strip().toLowerCase(Locale.ROOT).replace('-', '_');
            for (var entry : LANGUAGES.entrySet()) {
                if (normalizedReportedLanguage.startsWith(entry.getValue())) {
                    return entry.getKey();
                }
            }
        }

        return configuredLanguage();
    }

    private static String resolveTemplate(String language, String key) {
        String normalizedLanguage = normalizeSupportedLanguage(language);
        Map<String, String> languageMap = normalizedLanguage == null
                ? TranslationHolder.TRANSLATIONS.get(configuredLanguage())
                : TranslationHolder.TRANSLATIONS.get(normalizedLanguage);
        String template = languageMap == null ? null : languageMap.get(key);
        if (template != null) {
            return template;
        }

        LOGGER.warn("Missing auth translation key {} for language {}", key, language);
        return key;
    }

    private static Map<String, Map<String, String>> loadTranslations() {
        Map<String, Map<String, String>> translations = new HashMap<>();
        for (String langCode : SUPPORTED_LANGUAGES) {
            translations.put(langCode, loadLanguageFile(langCode));
        }
        return Map.copyOf(translations);
    }

    private static Map<String, String> loadLanguageFile(String language) {
        String resourcePath = "assets/" + AuthMod.MODID + "/lang/" + language + ".json";
        try (InputStream inputStream = AuthTranslations.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                throw new IllegalStateException("Missing auth language resource: " + resourcePath);
            }

            try (InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
                JsonObject rootObject = JsonParser.parseReader(reader).getAsJsonObject();
                Map<String, String> translations = new HashMap<>();
                for (Map.Entry<String, JsonElement> entry : rootObject.entrySet()) {
                    translations.put(entry.getKey(), entry.getValue().getAsString());
                }
                return Map.copyOf(translations);
            }
        } catch (IOException | IllegalStateException runtimeException) {
            throw new IllegalStateException("Failed to load auth language resource " + resourcePath, runtimeException);
        }
    }

    private static final class TranslationHolder {
        private static final Map<String, Map<String, String>> TRANSLATIONS = loadTranslations();

        private TranslationHolder() {
        }
    }
}