package com.epam.aidial.enrichment.observability.loglevel;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

/**
 * Reads the level file in DM's format, {@code {"loggerLevels": {"<logger>": {defaultLevel, configuredLevel,
 * validTill}}}}. The bytes are hashed before parsing, so an unparsable file still has an identity to report once.
 */
@Component
@RequiredArgsConstructor
public class FileLogLevelSource implements LogLevelSource {

    private final LogLevelProperties properties;
    private final JsonMapper jsonMapper;

    @Override
    public String location() {
        return properties.path().toString();
    }

    @Override
    public Optional<LogLevelSnapshot> read() {
        Path path = properties.path();
        byte[] content;
        try {
            content = Files.readAllBytes(path);
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String hash = sha256(content);
        try {
            LevelFile file = jsonMapper.readValue(content, LevelFile.class);
            if (file == null) {
                return Optional.of(new LogLevelSnapshot(hash, null, "empty document"));
            }
            Map<String, LoggerLevelEntry> levels = file.loggerLevels() == null ? Map.of() : file.loggerLevels();
            return Optional.of(new LogLevelSnapshot(hash, levels, null));
        } catch (JacksonException e) {
            return Optional.of(new LogLevelSnapshot(hash, null, e.getOriginalMessage()));
        }
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record LevelFile(Map<String, LoggerLevelEntry> loggerLevels) {
    }
}
