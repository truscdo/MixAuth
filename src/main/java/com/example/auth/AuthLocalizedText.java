package com.example.auth;

public record AuthLocalizedText(
        String translationKey,
        Object[] args
) {
    public AuthLocalizedText {
        args = args == null ? new Object[0] : args.clone();
    }

    public static AuthLocalizedText of(String translationKey, Object... args) {
        return new AuthLocalizedText(translationKey, args);
    }

    public boolean isMissing() {
        return this.translationKey == null || this.translationKey.isBlank();
    }

    public String textForConfiguredLanguage() {
        return AuthTranslations.textForConfiguredLanguage(this.translationKey, this.args);
    }

    @Override
    public Object[] args() {
        return this.args.clone();
    }
}