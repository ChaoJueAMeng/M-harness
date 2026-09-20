package com.mharness.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/**
 * 把运行时诊断写到 {@code ~/.m-harness/logs/m-harness.log}。失败时静默，避免日志再把主循环打崩。
 */
public final class HarnessLog {
    private static final long MAX_BYTES = 2_000_000;

    private HarnessLog() {
    }

    public static Path logFile() {
        return HarnessConfig.globalConfigDir().resolve("logs").resolve("m-harness.log");
    }

    public static void info(String message) {
        write("INFO", message, null);
    }

    public static void warn(String message) {
        write("WARN", message, null);
    }

    public static void warn(String message, Throwable error) {
        write("WARN", message, error);
    }

    public static void error(String message, Throwable error) {
        write("ERROR", message, error);
    }

    private static synchronized void write(String level, String message, Throwable error) {
        try {
            Path file = logFile();
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            rotateIfNeeded(file);
            String line = Instant.now() + " [" + level + "] " + (message == null ? "" : message);
            if (error != null) {
                line += " : " + error.getClass().getSimpleName() + " " + error.getMessage();
            }
            Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // logging must never fail the agent
        }
    }

    private static void rotateIfNeeded(Path file) throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) < MAX_BYTES) {
            return;
        }
        Path bak = file.resolveSibling("m-harness.log.1");
        Files.deleteIfExists(bak);
        Files.move(file, bak);
    }
}
