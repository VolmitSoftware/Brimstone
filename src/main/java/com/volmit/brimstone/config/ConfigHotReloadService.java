package com.volmit.brimstone.config;

import art.arcane.volmlib.util.config.ConfigFileSupport;
import art.arcane.volmlib.util.hotload.ConfigHotloadEngine;
import com.volmit.brimstone.localization.LanguageService;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ConfigHotReloadService implements AutoCloseable {
    private static final long MAXIMUM_WATCHED_BYTES = 2L * 1024L * 1024L;

    private final ConfigService configuration;
    private final LanguageService language;
    private final Logger logger;
    private final BooleanSupplier reload;
    private final Consumer<Boolean> notifier;
    private final ConfigHotloadEngine engine;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean reconfigureRequested = new AtomicBoolean();
    private List<File> configuredFiles = List.of();

    public ConfigHotReloadService(ConfigService configuration, LanguageService language, Logger logger,
                                  BooleanSupplier reload, Consumer<Boolean> notifier) {
        this.configuration = configuration;
        this.language = language;
        this.logger = logger;
        this.reload = reload;
        this.notifier = notifier;
        engine = new ConfigHotloadEngine(this::isManagedFile, this::knownFiles, this::readFile,
                content -> content == null ? null : ConfigFileSupport.normalize(content));
        executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "Brimstone-HotReload");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        configureEngine();
        configuration.setSelfWriteListener(engine::noteSelfWrite);
        language.setSelfWriteListener(engine::noteSelfWrite);
        scheduleNext(configuration.runtime().hotReloadPollMillis());
    }

    public void requestReconfigure() {
        reconfigureRequested.set(true);
    }

    public void reloadNow() {
        if (closed.get()) {
            return;
        }
        try {
            executor.execute(reload::getAsBoolean);
        } catch (RejectedExecutionException exception) {
            if (!closed.get()) {
                logger.log(Level.SEVERE, "Brimstone could not schedule a configuration reload", exception);
            }
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        configuration.setSelfWriteListener(null);
        language.setSelfWriteListener(null);
        engine.clear();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            logger.log(Level.WARNING, "Interrupted while stopping the Brimstone configuration watcher", exception);
        }
    }

    private void scheduleNext(long delayMillis) {
        if (closed.get()) {
            return;
        }
        try {
            executor.schedule(this::poll, Math.max(250L, delayMillis), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException exception) {
            if (!closed.get()) {
                logger.log(Level.SEVERE, "Brimstone configuration watcher could not reschedule", exception);
            }
        }
    }

    private void poll() {
        try {
            if (reconfigureRequested.getAndSet(false)) {
                configureEngine();
            }
            Set<ConfigHotloadEngine.StableContentSnapshot> snapshots = engine.pollTouchedSnapshots();
            if (!snapshots.isEmpty()) {
                apply(snapshots);
            }
        } catch (Throwable exception) {
            logger.log(Level.SEVERE, "Brimstone configuration watcher failed", exception);
        } finally {
            scheduleNext(configuration.runtime().hotReloadPollMillis());
        }
    }

    private void apply(Set<ConfigHotloadEngine.StableContentSnapshot> snapshots) {
        boolean enabled = configuration.runtime().hotReloadEnabled();
        AtomicBoolean attempted = new AtomicBoolean();
        AtomicReference<Boolean> result = new AtomicReference<>();
        for (ConfigHotloadEngine.StableContentSnapshot snapshot : snapshots) {
            engine.processSnapshotChange(snapshot, ignored -> {
                attempted.set(true);
                if (!enabled) {
                    return true;
                }
                Boolean existing = result.get();
                if (existing != null) {
                    return existing;
                }
                boolean applied = reload.getAsBoolean();
                result.set(applied);
                return applied;
            }, null);
        }
        if (!attempted.get() || !enabled) {
            return;
        }
        boolean applied = Boolean.TRUE.equals(result.get());
        if (applied) {
            logger.info("Applied Brimstone configuration and language file changes.");
        }
        if (configuration.runtime().hotReloadNotifyOperators()) {
            notifier.accept(applied);
        }
    }

    private void configureEngine() {
        RuntimeConfig config = configuration.runtime();
        List<File> files = knownFiles();
        if (files.equals(configuredFiles)) {
            engine.updateTiming(config.hotReloadPollMillis(), config.hotReloadCooldownMillis());
            return;
        }
        engine.configure(config.hotReloadPollMillis(), config.hotReloadCooldownMillis(), files,
                List.of(configuration.dataFolder(), language.languageDirectory()));
        configuredFiles = files;
    }

    private List<File> knownFiles() {
        List<File> files = new ArrayList<>(language.files());
        files.add(configuration.configFile());
        return List.copyOf(files);
    }

    private boolean isManagedFile(File file) {
        return file != null && (file.getAbsoluteFile().equals(configuration.configFile().getAbsoluteFile())
                || language.isLanguageFile(file));
    }

    private String readFile(File file) {
        if (file == null) {
            return null;
        }
        Path path = file.toPath().toAbsolutePath().normalize();
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || file.length() > MAXIMUM_WATCHED_BYTES) {
            return null;
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            return null;
        }
    }
}
