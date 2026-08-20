package io.github.truscdo.mixauth.validation;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import io.github.truscdo.mixauth.compat.ProfileCompat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link MojangProfileParser} 的纯 JUnit 测试（版本无关，跨版本跑）。
 *
 * <p>
 * 本测试是真实服务器登录链验证的纯函数层（不触网、不起服务）：直接对
 * {@code parseGameProfile} 喂带<b>非空 properties</b> 的 Mojang profile JSON
 * （如真实 textures 形状，含 value/signature），断言返回非 null 且 uuid/name
 * 正确。该解析结果是后续预检查/握手集成验证的前提，故优先在本层落地。
 * </p>
 *
 * <p>
 * 复现背景：1.21.11（authlib 7.0.61）的 {@code GameProfile} 2 参构造返回不可变
 * {@code PropertyMap}，{@code properties().put(...)} 抛
 * {@link UnsupportedOperationException}
 * → 被 {@code parseGameProfile} 的 {@code catch (RuntimeException)} 吞掉 → 返回 null
 * →
 * {@code MojangClient} 判 malformed → 在线验证全断。空 properties 数组不进入 put 循环、
 * UOE 不触发、bug 复现不出来，因此测试必须带非空 properties。
 * </p>
 *
 * @see MojangProfileParser
 * @see ProfileCompat
 */
@DisplayName("MojangProfileParser — Mojang profile JSON 解析")
class MojangProfileParserTest {

    private static final String USERNAME = "TestPlayer";
    private static final String UUID_UNDASHED = "853c80ef3c3749fdaa49938b674adae6";
    private static final String UUID_DASHED = "853c80ef-3c37-49fd-aa49-938b674adae6";

    /** 带非空 properties 的真实 Mojang profile 形状 JSON（textures 属性 + signature）。 */
    private static final String PROFILE_JSON_WITH_PROPERTIES = """
            {
              "id": "853c80ef3c3749fdaa49938b674adae6",
              "name": "TestPlayer",
              "properties": [
                {
                  "name": "textures",
                  "value": "eyJ0aW1lc3RhbXAiOjE3MjE4MjQwMDAwMDAsInByb2ZpbGVJZCI6Ijg1M2M4MGVmM2MzNzQ5ZmRhYTQ5OTM4YjY3NGFkYWU2IiwicHJvZmlsZU5hbWUiOiJUZXN0UGxheWVyIiwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlL2FiY2RlZiJ9fX0=",
                  "signature": "aGVsbG93b3JsZA=="
                }
              ]
            }
            """;

    /** 无 properties 数组的 profile JSON（模拟精简响应；空数组等价，不触发 put 循环）。 */
    private static final String PROFILE_JSON_NO_PROPERTIES = """
            {
              "id": "853c80ef3c3749fdaa49938b674adae6",
              "name": "TestPlayer"
            }
            """;

    // ================================================================ 解析

    @Test
    @DisplayName("带非空 properties 的 profile → 返回非 null，uuid/name 正确")
    void parseWithProperties() {
        GameProfile profile = MojangProfileParser.parseGameProfile(PROFILE_JSON_WITH_PROPERTIES, USERNAME);

        // 1.21.11 不可变 PropertyMap 故障时此处返回 null（UOE 被吞）
        assertNotNull(profile, () -> "parseGameProfile 应成功解析（1.21.11 不可变 PropertyMap 故障会返回 null）");
        assertEquals(UUID.fromString(UUID_DASHED), ProfileCompat.uuid(profile));
        assertEquals(USERNAME, ProfileCompat.name(profile));
    }

    @Test
    @DisplayName("带非空 properties 的 profile → textures 属性完整保留")
    void parseKeepsProperties() {
        GameProfile profile = MojangProfileParser.parseGameProfile(PROFILE_JSON_WITH_PROPERTIES, USERNAME);
        assertNotNull(profile);

        Collection<Property> textures = ProfileCompat.properties(profile).get("textures");
        assertNotNull(textures, "textures 属性应被解析保留");
        assertFalse(textures.isEmpty(), "textures 属性应被解析保留");
        Property texture = textures.iterator().next();
        assertEquals("textures", texture.name());
        assertTrue(texture.hasSignature(), "signature 应被解析到 Property");
    }

    @Test
    @DisplayName("无 properties 数组 → 仍返回非 null 且 uuid/name 正确")
    void parseWithoutProperties() {
        GameProfile profile = MojangProfileParser.parseGameProfile(PROFILE_JSON_NO_PROPERTIES, USERNAME);

        assertNotNull(profile);
        assertEquals(UUID.fromString(UUID_DASHED), ProfileCompat.uuid(profile));
        assertEquals(USERNAME, ProfileCompat.name(profile));
    }

    @Test
    @DisplayName("缺少 name → 回退到 fallback 用户名")
    void parseFallsBackToUsername() {
        String json = """
                {
                  "id": "853c80ef3c3749fdaa49938b674adae6"
                }
                """;
        GameProfile profile = MojangProfileParser.parseGameProfile(json, USERNAME);

        assertNotNull(profile);
        assertEquals(USERNAME, ProfileCompat.name(profile));
    }

    @Test
    @DisplayName("结构不合法（缺 id / 非 JSON）→ 返回 null")
    void parseMalformedReturnsNull() {
        assertNull(MojangProfileParser.parseGameProfile("{\"name\":\"NoId\"}", USERNAME));
        assertNull(MojangProfileParser.parseGameProfile("not-json-at-all", USERNAME));
    }

    // ================================================================ UUID

    @Test
    @DisplayName("parseUndashedUuid：无连字符 32 位 → 标准带连字符")
    void undashedUuid() {
        assertEquals(UUID.fromString(UUID_DASHED), MojangProfileParser.parseUndashedUuid(UUID_UNDASHED));
        assertEquals(UUID.fromString(UUID_DASHED), MojangProfileParser.parseUndashedUuid(UUID_DASHED));
    }

    @Test
    @DisplayName("parseUndashedUuid：长度非法 → IllegalArgumentException")
    void undashedUuidInvalidLength() {
        assertThrows(IllegalArgumentException.class,
                () -> MojangProfileParser.parseUndashedUuid("123"));
    }
}
