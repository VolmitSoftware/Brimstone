package com.volmit.brimstone.config;

import art.arcane.volmlib.util.config.ConfigEditorDocument;
import art.arcane.volmlib.util.config.TomlDocumentEditor;
import java.io.File;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class ConfigService {
    private final ConfigRepository repository;
    private final AtomicReference<ConfigRepository.PreparedConfig> current;
    private volatile BiConsumer<File, String> selfWriteListener;

    public ConfigService(File dataFolder) {
        repository = new ConfigRepository(dataFolder);
        current = new AtomicReference<>(repository.defaults());
    }

    public synchronized ConfigRepository.PreparedConfig prepareFromDisk() throws IOException {
        return repository.load();
    }

    public synchronized void install(ConfigRepository.PreparedConfig prepared) {
        current.set(Objects.requireNonNull(prepared));
    }

    public synchronized RuntimeConfig update(Consumer<BrimstoneConfig> editor) throws IOException {
        BrimstoneConfig source = editableCopy();
        editor.accept(source);
        installedWrite(repository.save(source));
        return runtime();
    }

    public synchronized ConfigEditorDocument loadEditorDocument() throws IOException {
        return ConfigEditorDocument.fromTomlWithDefaults(repository.read(), repository.defaults().serialized());
    }

    public synchronized ConfigEditorDocument saveEditorDocument(ConfigEditorDocument.Edit edit) throws IOException {
        commitPrepared(prepareEditorEdit(edit));
        return loadEditorDocument();
    }

    public synchronized ConfigRepository.PreparedConfig prepareEditorEdit(ConfigEditorDocument.Edit edit) throws IOException {
        ConfigEditorDocument document = loadEditorDocument();
        if (!document.value(edit.path()).equals(edit.expected())) {
            throw new IOException("This setting changed while the editor was open; reopen it and try again");
        }
        String content = TomlDocumentEditor.set(document.source(), edit.path(), edit.value());
        return repository.parse(content);
    }

    public synchronized void commitPrepared(ConfigRepository.PreparedConfig prepared) throws IOException {
        installedWrite(repository.saveContent(prepared.serialized()));
    }

    private void installedWrite(ConfigRepository.PreparedConfig prepared) {
        install(prepared);
        BiConsumer<File, String> listener = selfWriteListener;
        if (listener != null) {
            listener.accept(configFile(), prepared.serialized());
        }
    }

    public RuntimeConfig runtime() {
        return current.get().runtime();
    }

    public BrimstoneConfig editableCopy() {
        return current.get().source();
    }

    public File configFile() {
        return repository.configFile();
    }

    public File dataFolder() {
        return configFile().getParentFile();
    }

    public void setSelfWriteListener(BiConsumer<File, String> listener) {
        selfWriteListener = listener;
    }
}
