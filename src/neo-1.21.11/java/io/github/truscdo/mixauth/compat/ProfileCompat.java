package io.github.truscdo.mixauth.compat;

import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;

import java.util.List;
import java.util.UUID;

/**
 * 1.21.11 版本特定实现：authlib {@code GameProfile} record 化，
 * {@code getId()/getName()/getProperties()} 改名为
 * {@code id()/name()/properties()}。
 * 由 build.gradle 按 {@code minecraft_version} 选择本源集参与编译。
 */
public final class ProfileCompat {
    private ProfileCompat() {
    }

    public static UUID uuid(GameProfile profile) {
        return profile.id();
    }

    public static String name(GameProfile profile) {
        return profile.name();
    }

    public static PropertyMap properties(GameProfile profile) {
        return profile.properties();
    }

    /**
     * 构造带属性的 {@code GameProfile}（1.21.11 版本特定实现）。
     *
     * <p>
     * authlib 7.0.61 中 {@code GameProfile} 的 properties 一律不可变
     * （2 参构造用 {@code PropertyMap.EMPTY}；{@code PropertyMap(Multimap)}
     * 构造内部 {@code ImmutableMultimap.copyOf}），因此不能像 6.x 那样先构造再
     * 逐条 put——否则 {@code MojangProfileParser.parseGameProfile} 的 put 抛 UOE
     * 被吞 → 返回 null → 在线验证全断。本实现先构建可变 Guava Multimap，
     * 再经 {@code PropertyMap(Multimap)}（不可变拷贝）走 3 参构造一次性成形。
     */
    public static GameProfile createProfile(UUID uuid, String name, List<Property> properties) {
        Multimap<String, Property> backing = LinkedHashMultimap.create();
        for (Property property : properties) {
            backing.put(property.name(), property);
        }
        return new GameProfile(uuid, name, new PropertyMap(backing));
    }
}
