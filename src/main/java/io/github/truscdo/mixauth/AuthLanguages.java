package io.github.truscdo.mixauth;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 受支持的本地化语言，作为唯一事实来源（single source of truth）。
 * <p>
 * 纯常量 + 纯函数，不依赖任何其他类：
 * <ul>
 * <li>配置层（{@link AuthServerConfig}）用它做 default_language 的合法性校验；</li>
 * <li>本地化层（AuthTranslations）用它做语言 normalize 与前缀回退。</li>
 * </ul>
 * 配置层与本地化层都只依赖本类，从而避免二者之间的循环依赖。
 */
public final class AuthLanguages {
    // Language code → locale prefix for auto-detection fallback.
    // To add a language: create <code>.json in assets/mixauth/lang/, then add an
    // entry here.
    private static final Map<String, String> LANGUAGES = createLanguages();

    /** 受支持的语言代码集合（如 zh_cn、en_us）。 */
    public static final Set<String> SUPPORTED_LANGUAGES = LANGUAGES.keySet();

    private AuthLanguages() {
    }

    private static Map<String, String> createLanguages() {
        Map<String, String> languages = new LinkedHashMap<>();
        languages.put("zh_cn", "zh");
        languages.put("en_us", "en");
        languages.put("es_es", "es");
        languages.put("pt_br", "pt");
        languages.put("ru_ru", "ru");
        return Collections.unmodifiableMap(languages);
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

    /** 语言代码 → locale 前缀映射（用于客户端语言自动检测回退）。 */
    public static Map<String, String> languagePrefixes() {
        return LANGUAGES;
    }
}
