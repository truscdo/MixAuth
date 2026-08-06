package io.github.truscdo.mixauth.validation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import io.github.truscdo.mixauth.LogUtil;
import org.slf4j.Logger;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * 解析 Mojang API 返回的 JSON profile 数据。
 * 纯函数、无状态，与握手/网络逻辑解耦，便于独立测试。
 */
public final class MojangProfileParser {
    private static final Logger LOGGER = LogUtil.getLogger();

    private MojangProfileParser() {
    }

    /** 解析 profile JSON；结构不合法时返回 null。 */
    public static GameProfile parseGameProfile(String body, String fallbackUsername) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonElement idElement = root.get("id");
            if (idElement == null) {
                return null;
            }

            UUID uuid = parseUndashedUuid(idElement.getAsString());
            String profileName = Optional.ofNullable(root.get("name"))
                    .map(JsonElement::getAsString)
                    .filter(name -> !name.isBlank())
                    .orElse(fallbackUsername);

            GameProfile profile = new GameProfile(uuid, profileName);
            JsonArray properties = root.getAsJsonArray("properties");
            if (properties != null) {
                for (JsonElement propertyElement : properties) {
                    JsonObject propertyObject = propertyElement.getAsJsonObject();
                    String name = propertyObject.get("name").getAsString();
                    String value = propertyObject.get("value").getAsString();
                    JsonElement signatureElement = propertyObject.get("signature");
                    Property property = signatureElement == null || signatureElement.isJsonNull()
                            ? new Property(name, value)
                            : new Property(name, value, signatureElement.getAsString());
                    profile.getProperties().put(name, property);
                }
            }

            return profile;
        } catch (RuntimeException runtimeException) {
            LOGGER.error("Failed to parse authenticated profile for {}", fallbackUsername, runtimeException);
            return null;
        }
    }

    /** 将无连字符的 32 位 UUID 字符串转为标准带连字符格式。 */
    public static UUID parseUndashedUuid(String value) {
        String normalized = value.toLowerCase(Locale.ROOT).replace("-", "");
        if (normalized.length() != 32) {
            throw new IllegalArgumentException("Unexpected UUID length: " + value);
        }

        String dashed = normalized.substring(0, 8)
                + "-" + normalized.substring(8, 12)
                + "-" + normalized.substring(12, 16)
                + "-" + normalized.substring(16, 20)
                + "-" + normalized.substring(20);
        return UUID.fromString(dashed);
    }
}
