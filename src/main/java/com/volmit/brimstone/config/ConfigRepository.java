package com.volmit.brimstone.config;

import art.arcane.volmlib.util.config.TomlCodec;
import art.arcane.volmlib.util.io.AtomicFileIO;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

public final class ConfigRepository {
    private final File configFile;

    public ConfigRepository(File dataFolder) {
        configFile = new File(dataFolder, "config.toml");
    }

    public PreparedConfig load() throws IOException {
        if (!configFile.exists()) {
            return save(new BrimstoneConfig());
        }
        return parse(read());
    }

    public String read() throws IOException {
        if (!configFile.isFile() || Files.isSymbolicLink(configFile.toPath()) || configFile.length() > 2097152) {
            throw new IOException("Configuration must be a regular file smaller than 2 MiB");
        }
        return Files.readString(configFile.toPath());
    }

    public PreparedConfig parse(String content) throws IOException {
        try {
            BrimstoneConfig source = TomlCodec.fromToml(content, BrimstoneConfig.class);
            return new PreparedConfig(source, RuntimeConfig.from(source), content);
        } catch (RuntimeException exception) {
            throw new IOException("Invalid Brimstone configuration", exception);
        }
    }

    public PreparedConfig save(BrimstoneConfig source) throws IOException {
        try {
            RuntimeConfig.from(source);
        } catch (RuntimeException exception) {
            throw new IOException("Invalid Brimstone configuration", exception);
        }
        return saveContent(TomlCodec.toToml(source, "brimstone"));
    }

    public PreparedConfig saveContent(String content) throws IOException {
        PreparedConfig prepared = parse(content);
        Files.createDirectories(configFile.toPath().getParent());
        AtomicFileIO.writeString(configFile.toPath(), content);
        return prepared;
    }

    public PreparedConfig defaults() {
        BrimstoneConfig source = new BrimstoneConfig();
        return new PreparedConfig(source, RuntimeConfig.from(source), TomlCodec.toToml(source, "brimstone"));
    }

    public File configFile() {
        return configFile;
    }

    public record PreparedConfig(BrimstoneConfig source, RuntimeConfig runtime, String serialized) {
        public PreparedConfig {
            source = source.copy();
        }

        @Override
        public BrimstoneConfig source() {
            return source.copy();
        }
    }
}
