package com.example.auth;

import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class PasswordBlacklistLoader {
    private static final Logger LOGGER = LogUtil.getLogger();
    private static final String BUILT_IN_RESOURCE = "/auth_password_blacklist.txt";

    private static Set<String> blacklist = Set.of();

    private PasswordBlacklistLoader() {
    }

    static void init() {
        Path externalFile = resolveBlacklistPath();
        if (!Files.isRegularFile(externalFile)) {
            ensureParentDir(externalFile);
            if (copyBuiltInTo(externalFile)) {
                LOGGER.info("Created password blacklist file: {}", externalFile);
            } else {
                LOGGER.warn("Failed to create password blacklist file, falling back to built-in resource.");
                blacklist = loadFromResource(BUILT_IN_RESOURCE);
                LOGGER.info("Loaded built-in password blacklist ({} entries)", blacklist.size());
                return;
            }
        }
        blacklist = loadFromFile(externalFile);
        LOGGER.info("Loaded password blacklist from: {} ({} entries)", externalFile, blacklist.size());
    }

    public static boolean isBlacklisted(String password) {
        if (password == null) {
            return false;
        }
        return blacklist.contains(password.toLowerCase(Locale.ROOT));
    }

    public static int size() {
        return blacklist.size();
    }

    private static Path resolveBlacklistPath() {
        String configuredPath = AuthServerConfig.passwordBlacklistPath();
        if (configuredPath == null || configuredPath.isBlank()) {
            return Path.of("auth", "password_blacklist.txt").normalize();
        }
        return Path.of(configuredPath).normalize();
    }

    private static void ensureParentDir(Path filePath) {
        try {
            Path parent = filePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException ioException) {
            LOGGER.error("Failed to create parent directory for: {}", filePath, ioException);
        }
    }

    private static boolean copyBuiltInTo(Path targetPath) {
        try (InputStream inputStream = PasswordBlacklistLoader.class.getResourceAsStream(BUILT_IN_RESOURCE)) {
            if (inputStream == null) {
                LOGGER.error("Built-in password blacklist resource not found: {}", BUILT_IN_RESOURCE);
                return false;
            }
            Files.copy(inputStream, targetPath, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException ioException) {
            LOGGER.error("Failed to copy built-in password blacklist to: {}", targetPath, ioException);
            return false;
        }
    }

    private static Set<String> loadFromResource(String resourcePath) {
        InputStream inputStream = PasswordBlacklistLoader.class.getResourceAsStream(resourcePath);
        if (inputStream == null) {
            LOGGER.warn("Built-in password blacklist resource not found: {}", resourcePath);
            return Set.of();
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            return readLines(reader);
        } catch (IOException ioException) {
            LOGGER.error("Failed to read built-in password blacklist", ioException);
            return Set.of();
        }
    }

    private static Set<String> loadFromFile(Path filePath) {
        try (BufferedReader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            return readLines(reader);
        } catch (IOException ioException) {
            LOGGER.error("Failed to read password blacklist: {}", filePath, ioException);
            return Set.of();
        }
    }

    private static Set<String> readLines(BufferedReader reader) throws IOException {
        Set<String> lines = new HashSet<>();
        String line;
        while ((line = reader.readLine()) != null) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            lines.add(trimmed.toLowerCase(Locale.ROOT));
        }
        return Set.copyOf(lines);
    }
}
