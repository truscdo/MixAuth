package io.github.truscdo.mixauth.login;

import net.minecraft.network.Connection;

import java.util.Objects;

public final class LoginContexts {
    private LoginContexts() {
    }

    public static boolean publish(Connection connection, LoginContext context) {
        return carrier(connection).mixAuth$publishLoginContext(Objects.requireNonNull(context, "context"));
    }

    public static LoginContext peek(Connection connection) {
        return carrier(connection).mixAuth$peekLoginContext();
    }

    public static LoginContext take(Connection connection) {
        return carrier(connection).mixAuth$takeLoginContext();
    }

    public static void clear(Connection connection) {
        carrier(connection).mixAuth$clearLoginContext();
    }

    private static LoginContextCarrier carrier(Connection connection) {
        Objects.requireNonNull(connection, "connection");
        if (!(connection instanceof LoginContextCarrier loginContextCarrier)) {
            throw new IllegalStateException("Connection is missing the MixAuth LoginContext carrier");
        }
        return loginContextCarrier;
    }
}
