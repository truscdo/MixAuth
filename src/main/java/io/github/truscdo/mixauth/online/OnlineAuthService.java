package io.github.truscdo.mixauth.online;

import com.mojang.authlib.GameProfile;
import io.github.truscdo.mixauth.KnownPlayerService;
import io.github.truscdo.mixauth.compat.ProfileCompat;
import io.github.truscdo.mixauth.LogUtil;
import org.slf4j.Logger;

import java.util.UUID;

public final class OnlineAuthService {
    private static final Logger LOGGER = LogUtil.getLogger();

    private OnlineAuthService() {
    }

    public static void recordOnlineLogin(GameProfile profile) {
        UUID onlineUuid = ProfileCompat.uuid(profile);
        if (onlineUuid == null) {
            throw new IllegalStateException("Authenticated online profile is missing its UUID");
        }

        KnownPlayerService.recordKnownPlayer(onlineUuid, ProfileCompat.name(profile), LoginMode.ONLINE);
        LOGGER.info("Recorded online login for {}", ProfileCompat.name(profile));
    }

    public enum LoginMode {
        ONLINE,
        OFFLINE;
    }
}
