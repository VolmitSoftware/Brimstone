package com.volmit.brimstone;

import art.arcane.volmlib.util.config.BukkitConfigEditor;
import art.arcane.volmlib.util.diagnostics.BukkitDebugDump;
import art.arcane.volmlib.util.diagnostics.DebugDumpContributor;
import art.arcane.volmlib.util.localization.BukkitLanguageSwitcher;
import art.arcane.volmlib.util.localization.LanguageUpstreamSync;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.plugin.ComponentMessenger;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.volmlib.util.update.BukkitUpdateService;
import com.volmit.brimstone.command.CommandService;
import com.volmit.brimstone.config.ConfigHotReloadService;
import com.volmit.brimstone.config.ConfigRepository;
import com.volmit.brimstone.config.ConfigService;
import com.volmit.brimstone.config.RuntimeConfig;
import com.volmit.brimstone.diagnostics.EffectDiagnostics;
import com.volmit.brimstone.gameplay.TntService;
import com.volmit.brimstone.integration.BrimstoneIntegrationService;
import com.volmit.brimstone.localization.LanguageService;
import com.volmit.brimstone.metrics.MetricsService;
import com.volmit.brimstone.presentation.BrimstoneTheme;
import com.volmit.brimstone.presentation.BrimstoneSplash;
import io.github.slimjar.app.builder.SpigotApplicationBuilder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class Brimstone extends JavaPlugin {
    private final Object configurationLock = new Object();
    private final Set<String> pendingLanguageInstalls = ConcurrentHashMap.newKeySet();
    private ConfigService configuration;
    private LanguageService language;
    private TntService tnt;
    private BukkitConfigEditor configEditor;
    private BukkitLanguageSwitcher languageSwitcher;
    private BukkitDebugDump debugDump;
    private MetricsService metrics;
    private BukkitUpdateService updates;
    private BrimstoneIntegrationService integration;
    private ConfigHotReloadService hotReload;
    private volatile boolean stopping;

    public Brimstone() {
        SupportedRuntime.requireSupported(getServer().getBukkitVersion(), Runtime.version().feature());
        getLogger().info("Loading libraries...");
        new SpigotApplicationBuilder(this).build();
        getLogger().info("Libraries loaded.");
    }

    @Override
    public void onEnable() {
        try {
            configuration = new ConfigService(getDataFolder());
            ConfigRepository.PreparedConfig prepared = configuration.prepareFromDisk();
            configuration.install(prepared);
            metrics = new MetricsService(this);
            metrics.reconfigure(configuration.runtime().metricsEnabled());
            updates = BukkitUpdateService.register(this, new BukkitUpdateService.Options(
                    "VolmitSoftware", "Brimstone", "brimstone.update", () -> configuration.runtime().updateNotifications()));
            language = new LanguageService(getDataFolder(), getLogger());
            try {
                language.install(language.prepare(configuration.runtime().language()));
            } catch (IOException | RuntimeException exception) {
                getLogger().log(Level.WARNING, "Configured language could not load; using English", exception);
                language.install(language.englishFallback(configuration.runtime().language()));
            }
            language.initializeSelections(() -> configuration.runtime().language(), (locale, snapshot) -> {
                synchronized (configurationLock) {
                    if (stopping) {
                        throw new IOException("Brimstone is stopping");
                    }
                    configuration.update(config -> config.general.language = locale);
                    language.install(new LanguageService.PreparedLanguage(locale, language.languageFile(locale), snapshot));
                    hotReload.requestReconfigure();
                }
            });
            tnt = new TntService(this, configuration::runtime, (player, key) -> send(player, key));
            tnt.register();
            integration = new BrimstoneIntegrationService(getDescription().getVersion(), this::integrationSnapshot, tnt::diagnostics);
            integration.register(this);
            hotReload = new ConfigHotReloadService(configuration, language, getLogger(), this::reloadFromDisk,
                    this::notifyHotReload);
            configEditor = BukkitConfigEditor.register(this, new BukkitConfigEditor.Options(
                    configuration::loadEditorDocument,
                    edit -> {
                        synchronized (configurationLock) {
                            if (stopping) {
                                throw new IOException("Brimstone is stopping");
                            }
                            ConfigRepository.PreparedConfig candidate = configuration.prepareEditorEdit(edit);
                            LanguageService.PreparedLanguage selected = language.prepare(candidate.runtime().language());
                            configuration.commitPrepared(candidate);
                            language.install(selected);
                            tnt.updateRecipes();
                            metrics.reconfigure(configuration.runtime().metricsEnabled());
                            updates.reconfigure();
                            hotReload.requestReconfigure();
                            requestConfiguredLanguage();
                            return configuration.loadEditorDocument();
                        }
                    },
                    new BukkitConfigEditor.Presentation("brimstone.config", BrimstoneTheme.theme(), language.directorResolver())));
            languageSwitcher = BukkitLanguageSwitcher.register(this, language.selections(),
                    new BukkitLanguageSwitcher.Options("brimstone", "brimstone.config", BrimstoneTheme.theme(),
                            language.directorResolver(), language.editorOptions(), null, language.updateAction(),
                            language.displayNames()));
            debugDump = BukkitDebugDump.create(this, new BukkitDebugDump.Options(
                    () -> configuration.runtime().debugUploadEnabled(), this::captureDebugReport,
                    new BukkitDebugDump.Presentation("/brimstone debug dump", "/brimstone debug", BrimstoneTheme.theme(),
                            (key, args) -> language.render(getServer().getConsoleSender(), key, args))));
            new CommandService(this).register();
            hotReload.start();
            language.sourceFailure().ifPresent(failure -> getLogger().log(Level.WARNING,
                    "Language source is unavailable; built-in English and installed language files remain active", failure));
            language.startUpstreamCheck();
            requestConfiguredLanguage();
            if (configuration.runtime().splashScreen()) {
                BrimstoneSplash.print(this);
            }
            integration.markReady();
            getLogger().info("Brimstone ready: " + configuration.runtime().definitions().size() + " TNT types.");
        } catch (Exception exception) {
            getLogger().log(Level.SEVERE, "Brimstone could not enable", exception);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        stopping = true;
        if (integration != null) {
            integration.close();
        }
        if (updates != null) {
            updates.close();
        }
        if (metrics != null) {
            metrics.close();
        }
        if (hotReload != null) {
            hotReload.close();
        }
        synchronized (configurationLock) {
            if (configEditor != null) {
                configEditor.close();
            }
            if (languageSwitcher != null) {
                languageSwitcher.close();
            }
            if (debugDump != null) {
                debugDump.close();
            }
            if (tnt != null) {
                tnt.shutdown();
            }
            if (language != null) {
                language.close();
            }
        }
        HandlerList.unregisterAll(this);
    }

    public void send(CommandSender sender, String key) {
        send(sender, key, MessageArgs.empty());
    }

    public void send(CommandSender sender, String key, MessageArgs arguments) {
        Runnable deliver = () -> ComponentMessenger.send(sender, language.render(sender, key, arguments));
        if (sender instanceof Player player) {
            FoliaScheduler.runEntity(this, player, deliver);
        } else {
            deliver.run();
        }
    }

    public ConfigService configuration() {
        return configuration;
    }

    public LanguageService language() {
        return language;
    }

    public TntService tnt() {
        return tnt;
    }

    public BukkitConfigEditor configEditor() {
        return configEditor;
    }

    public BukkitLanguageSwitcher languageSwitcher() {
        return languageSwitcher;
    }

    public BukkitDebugDump debugDump() {
        return debugDump;
    }

    private BrimstoneIntegrationService.RuntimeSnapshot integrationSnapshot() {
        return new BrimstoneIntegrationService.RuntimeSnapshot(!stopping && tnt.ready(), tnt.activeCount(),
                tnt.secondaryCount(), configuration.runtime().definitions().size(), tnt.registeredRecipeCount());
    }

    private DebugDumpContributor.Report captureDebugReport() {
        RuntimeConfig snapshot = configuration.runtime();
        int active = tnt.activeCount();
        int secondary = tnt.secondaryCount();
        boolean metricsInitialized = metrics.initialized();
        EffectDiagnostics.Snapshot counters = tnt.diagnostics();
        String suiteHeartbeat = integration.heartbeat().toString();
        String languageSource = language.sourceDescription();
        Optional<LanguageUpstreamSync.Status> languageStatus = language.upstreamStatus();
        String languageRevision = languageStatus.flatMap(LanguageUpstreamSync.Status::revision).orElse("none");
        String languageChecked = languageStatus.filter(status -> status.checkedMillis() > 0L)
                .map(status -> Instant.ofEpochMilli(status.checkedMillis()).toString()).orElse("never");
        String languageError = languageStatus.flatMap(LanguageUpstreamSync.Status::lastError).orElse("none");
        return () -> "Definitions: " + snapshot.definitions().keySet()
                + "\nActive TNT: " + active + "\nSecondary effects: " + secondary
                + "\nEntity limits: " + snapshot.limits() + "\nEffect settings: " + snapshot.effects()
                + "\nbStats setting: " + (snapshot.metricsEnabled() ? "enabled" : "disabled")
                + "\nbStats integration: " + (metricsInitialized ? "initialized" : "not initialized")
                + "\nCrafting: " + snapshot.craftingEnabled()
                + "\nUpdate notifications: " + (snapshot.updateNotifications() ? "enabled" : "disabled")
                + "\nUpdate repository: VolmitSoftware/Brimstone (provisional)"
                + "\nLanguage source: " + languageSource + "\nLanguage revision: " + languageRevision
                + "\nLanguage last check: " + languageChecked + "\nLanguage last error: " + languageError
                + "\nCooldown ticks: " + snapshot.cooldownTicks() + "\nLanguage: " + snapshot.language()
                + "\nSuite heartbeat: " + suiteHeartbeat
                + "\nSession launch attempts: " + counters.launchAttempts()
                + "\nSession launch successes: " + counters.launchSuccesses()
                + "\nLaunch rejections: " + counters.launchRejections()
                + "\nQuota rejections: " + counters.quotaRejections()
                + "\nSecondary suppressions: " + counters.secondarySuppressions()
                + "\nParticles requested: " + counters.particlesRequested()
                + "\nParticles granted: " + counters.particlesGranted()
                + "\nParticles suppressed: " + counters.particlesSuppressed();
    }

    private boolean reloadFromDisk() {
        synchronized (configurationLock) {
            if (stopping) {
                return false;
            }
            try {
                ConfigRepository.PreparedConfig candidate = configuration.prepareFromDisk();
                List<LanguageService.PreparedLanguage> prepared = language.prepareReload(candidate.runtime().language());
                configuration.install(candidate);
                language.installReload(prepared, candidate.runtime().language());
                tnt.updateRecipes();
                metrics.reconfigure(configuration.runtime().metricsEnabled());
                updates.reconfigure();
                hotReload.requestReconfigure();
                requestConfiguredLanguage();
                return true;
            } catch (IOException | RuntimeException exception) {
                getLogger().log(Level.SEVERE, "Rejected Brimstone file changes; the last known good settings remain active", exception);
                return false;
            }
        }
    }

    private void notifyHotReload(boolean applied) {
        String key = applied ? "runtime.hot_reload.success" : "runtime.hot_reload.failed";
        FoliaScheduler.runGlobal(this, () -> {
            for (Player player : getServer().getOnlinePlayers()) {
                if (player.hasPermission("brimstone.config")) {
                    FoliaScheduler.runEntity(this, player, () ->
                            ComponentMessenger.sendActionBar(player, language.render(player, key, MessageArgs.empty())));
                }
            }
        });
    }

    private void requestConfiguredLanguage() {
        String requested = configuration.runtime().language();
        if (language.languageFile(requested).isFile() || !language.isRepositoryLocale(requested)
                || !pendingLanguageInstalls.add(requested)) {
            return;
        }
        language.requestInstall(requested).whenComplete((content, failure) -> {
            pendingLanguageInstalls.remove(requested);
            if (failure != null) {
                getLogger().log(Level.WARNING, "Could not install Brimstone language " + requested
                        + "; English remains active", failure);
                return;
            }
            if (!stopping && requested.equals(configuration.runtime().language())) {
                hotReload.reloadNow();
            }
        });
    }
}
