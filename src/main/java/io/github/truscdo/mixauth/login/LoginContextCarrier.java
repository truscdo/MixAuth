package io.github.truscdo.mixauth.login;

public interface LoginContextCarrier {
    boolean mixAuth$publishLoginContext(LoginContext context);

    LoginContext mixAuth$peekLoginContext();

    LoginContext mixAuth$takeLoginContext();

    void mixAuth$clearLoginContext();
}
