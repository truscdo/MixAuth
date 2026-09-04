package io.github.truscdo.mixauth.mixin;

import io.github.truscdo.mixauth.login.LoginContext;
import io.github.truscdo.mixauth.login.LoginContextCarrier;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.Objects;

@Mixin(Connection.class)
abstract class ConnectionMixin implements LoginContextCarrier {
    @Unique
    private LoginContext mixAuth$loginContext;

    @Override
    public synchronized boolean mixAuth$publishLoginContext(LoginContext context) {
        Objects.requireNonNull(context, "context");
        if (this.mixAuth$loginContext != null) {
            return false;
        }
        this.mixAuth$loginContext = context;
        return true;
    }

    @Override
    public synchronized LoginContext mixAuth$peekLoginContext() {
        return this.mixAuth$loginContext;
    }

    @Override
    public synchronized LoginContext mixAuth$takeLoginContext() {
        LoginContext context = this.mixAuth$loginContext;
        this.mixAuth$loginContext = null;
        return context;
    }

    @Override
    public synchronized void mixAuth$clearLoginContext() {
        this.mixAuth$loginContext = null;
    }
}
