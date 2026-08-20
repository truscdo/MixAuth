package io.github.truscdo.mixauth.compat;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;

import java.util.List;
import java.util.UUID;

/**
 * 版本无关的 {@code GameProfile} 访问适配器（主目录基础版，覆盖 1.21.0 / 1.21.1 ~ 1.21.10）。
 *
 * <p>
 * 1.21.11 起 authlib 的 {@code GameProfile} record
 * 化：{@code getId()/getName()/getProperties()}
 * 改名为 {@code id()/name()/properties()}。本类把全部调用点收敛为版本无关的静态方法，
 * 1.21.11 覆盖实现由 {@code src/neo-1.21.11} 承载（build.gradle 按
 * {@code minecraft_version}
 * 选择源集，并在 1.21.11 分支排除本文件以避免重复类）。
 */
public final class ProfileCompat {
    private ProfileCompat() {
    }

    public static UUID uuid(GameProfile profile) {
        return profile.getId();
    }

    public static String name(GameProfile profile) {
        return profile.getName();
    }

    public static PropertyMap properties(GameProfile profile) {
        return profile.getProperties();
    }

    /**
     * 构造带属性的 {@code GameProfile}（主目录基础版，1.21.0 / 1.21.1 ~ 1.21.10）。
     *
     * <p>
     * authlib 6.x 的 2 参构造返回<b>可变</b> {@code PropertyMap}，逐条 put 即可；
     * 1.21.11（authlib 7.0.61）的 properties 不可变、不能逐条 put，其覆盖实现
     * 改为先构建可变 Multimap 再走 3 参构造（见 src/neo-1.21.11）。调用方
     * {@code MojangProfileParser.parseGameProfile} 应先收集好全部属性再一次性传入。
     */
    public static GameProfile createProfile(UUID uuid, String name, List<Property> properties) {
        GameProfile profile = new GameProfile(uuid, name);
        PropertyMap map = profile.getProperties();
        for (Property property : properties) {
            map.put(property.name(), property);
        }
        return profile;
    }
}
