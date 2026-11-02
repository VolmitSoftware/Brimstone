package com.volmit.brimstone.localization;

import art.arcane.volmlib.util.director.DirectorTextResolver;
import art.arcane.volmlib.util.io.AtomicFileIO;
import art.arcane.volmlib.util.localization.BukkitLanguageSwitcher;
import art.arcane.volmlib.util.localization.GitHubLanguageSource;
import art.arcane.volmlib.util.localization.LanguageCatalogMerge;
import art.arcane.volmlib.util.localization.LanguageDirectory;
import art.arcane.volmlib.util.localization.LanguageFileEditor;
import art.arcane.volmlib.util.localization.LanguageFileHeader;
import art.arcane.volmlib.util.localization.LanguageMeta;
import art.arcane.volmlib.util.localization.LanguageReferenceRenderer;
import art.arcane.volmlib.util.localization.LanguageSourceManifest;
import art.arcane.volmlib.util.localization.LanguageUpstreamStore;
import art.arcane.volmlib.util.localization.LanguageUpstreamSync;
import art.arcane.volmlib.util.localization.LocaleOverlay;
import art.arcane.volmlib.util.localization.LocalizationCandidate;
import art.arcane.volmlib.util.localization.LocalizationManager;
import art.arcane.volmlib.util.localization.LocalizationSnapshot;
import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.volmlib.util.localization.MessageArgumentKind;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.MessageCatalog;
import art.arcane.volmlib.util.localization.MessageKey;
import art.arcane.volmlib.util.localization.PluralSelector;
import art.arcane.volmlib.util.localization.PluginLanguageEditor;
import art.arcane.volmlib.util.localization.PluginLanguageService;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.localization.TextValue;
import art.arcane.volmlib.util.localization.TomlLanguageEditor;
import art.arcane.volmlib.util.localization.TomlLanguageParser;
import art.arcane.volmlib.util.localization.VolmitLocales;
import art.arcane.volmlib.util.plugin.ComponentText;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class LanguageService implements AutoCloseable {
    public static final String MANIFEST_RESOURCE = "brimstone-language-source.properties";
    private static final int MAXIMUM_BASE_DEPTH = 4;
    private static final MessageCatalog CATALOG = BrimstoneMessages.catalog();
    private static final MiniMessage MARKUP = MiniMessage.miniMessage();
    private final LanguageDirectory directory;
    private final File languageDirectory;
    private final Logger logger;
    private final LanguageSourceManifest manifest;
    private final LanguageUpstreamSync sync;
    private final Throwable sourceFailure;
    private final LocalizationManager manager = new LocalizationManager(LocalizationCandidate.english(CATALOG, PluralSelector.oneOther()));
    private volatile PreparedLanguage active;
    private volatile PluginLanguageService selections;
    private volatile BiConsumer<File, String> selfWriteListener;
    private volatile List<String> availableLocales;
    private volatile Map<String, LanguageMeta> metas = Map.of();

    public LanguageService(File dataFolder, Logger logger) {
        this(dataFolder, logger, GitHubLanguageSource.API_ROOT, GitHubLanguageSource.RAW_ROOT, LanguageUpstreamSync.MINIMUM_CHECK_INTERVAL);
    }

    LanguageService(File dataFolder, Logger logger, URI apiRoot, URI rawRoot, Duration minimumCheckInterval) {
        languageDirectory = new File(dataFolder, "languages");
        directory = new LanguageDirectory(languageDirectory);
        this.logger = logger;
        LanguageSourceManifest loadedManifest = null;
        LanguageUpstreamSync loadedSync = null;
        Throwable failure = null;
        try {
            loadedManifest = LanguageSourceManifest.load(LanguageService.class.getClassLoader(), MANIFEST_RESOURCE);
            GitHubLanguageSource source = new GitHubLanguageSource(loadedManifest, "Brimstone", apiRoot, rawRoot);
            loadedSync = new LanguageUpstreamSync(new LanguageUpstreamSync.Options("Brimstone", CATALOG, directory,
                    loadedManifest, source, new LanguageUpstreamStore(directory, loadedManifest),
                    () -> LanguageReferenceRenderer.render(CATALOG, header(CATALOG.englishLocale())),
                    this::validateContent, this::commitUpstream, this::noteUpstreamWrite, this::applyUpstream, logger),
                    minimumCheckInterval);
        } catch (RuntimeException exception) {
            failure = exception;
        }
        manifest = loadedManifest;
        sync = loadedSync;
        sourceFailure = failure;
        LinkedHashSet<String> initial = new LinkedHashSet<>();
        initial.add(CATALOG.englishLocale());
        if (manifest != null) {
            initial.addAll(manifest.locales());
        }
        availableLocales = sorted(initial);
    }

    public synchronized PreparedLanguage prepare(String locale) throws IOException {
        String canonical = canonicalLocale(locale);
        directory.prepare();
        ensureEnglish();
        File file = languageFile(canonical);
        Path path = file.toPath();
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(path))) {
            throw new IOException("Language must be a regular file: " + file);
        }
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (isRepositoryLocale(canonical)) {
                refreshAvailableLocales();
                return englishFallback(canonical);
            }
            createCustomLanguage(canonical);
        }
        PreparedLanguage prepared = parse(canonical, LanguageDirectory.readContent(path));
        refreshAvailableLocales();
        return prepared;
    }

    public synchronized void install(PreparedLanguage prepared) {
        manager.install(prepared.snapshot());
        active = prepared;
        if (selections != null) {
            selections.cache(prepared.locale(), prepared.snapshot());
        }
    }

    public synchronized PluginLanguageService initializeSelections(Supplier<String> defaultLocale,
                                                                   PluginLanguageService.DefaultSelection selection) {
        if (selections == null) {
            selections = new PluginLanguageService(new PluginLanguageService.Options(
                    directory.root().resolve("language-preferences.properties"), this::availableLocales,
                    defaultLocale, manager::snapshot, this::selectionSnapshot, selection, logger));
        }
        return selections;
    }

    public PluginLanguageService selections() {
        if (selections == null) {
            throw new IllegalStateException("Language selections have not been initialized");
        }
        return selections;
    }

    public PluginLanguageEditor.Options editorOptions() {
        return new PluginLanguageEditor.Options(this::selectionSnapshot, this::saveMessage);
    }

    public BukkitLanguageSwitcher.LanguageUpdateAction updateAction() {
        return this::startUpstreamCheck;
    }

    public BukkitLanguageSwitcher.LocaleDisplayNames displayNames() {
        return locale -> metas.getOrDefault(locale, LanguageMeta.empty()).name().orElse(null);
    }

    public CompletableFuture<LanguageUpstreamSync.SyncReport> startUpstreamCheck() {
        if (sync == null) {
            return CompletableFuture.completedFuture(LanguageUpstreamSync.SyncReport.failed(
                    sourceDescription(), System.currentTimeMillis()));
        }
        return sync.checkAsync().thenApply(this::logUpstreamReport);
    }

    public CompletableFuture<String> requestInstall(String locale) {
        String canonical = canonicalLocale(locale);
        if (languageFile(canonical).isFile()) {
            try {
                return CompletableFuture.completedFuture(directory.read(canonical));
            } catch (IOException exception) {
                return CompletableFuture.failedFuture(exception);
            }
        }
        if (sync == null || !isRepositoryLocale(canonical)) {
            return CompletableFuture.failedFuture(new IOException("Language " + canonical
                    + " is not available from " + sourceDescription()));
        }
        return sync.installAsync(canonical);
    }

    public Optional<Throwable> sourceFailure() {
        return Optional.ofNullable(sourceFailure);
    }

    public String sourceDescription() {
        return manifest == null ? "unavailable" : manifest.repository() + "@" + manifest.branch();
    }

    public Optional<LanguageUpstreamSync.Status> upstreamStatus() {
        return sync == null ? Optional.empty() : Optional.of(sync.status());
    }

    public boolean isRepositoryLocale(String locale) {
        return manifest != null && manifest.contains(locale);
    }

    public synchronized List<PreparedLanguage> prepareReload(String locale) throws IOException {
        List<PreparedLanguage> prepared = new ArrayList<>();
        String required = canonicalLocale(locale);
        prepared.add(prepare(required));
        for (String installed : directory.scan()) {
            if (!installed.equals(required)) {
                prepared.add(prepare(installed));
            }
        }
        return List.copyOf(prepared);
    }

    public synchronized void installReload(List<PreparedLanguage> prepared, String locale) {
        String required = canonicalLocale(locale);
        PreparedLanguage defaultLanguage = prepared.stream().filter(language -> language.locale().equals(required))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Default language is not prepared: " + required));
        if (selections != null) {
            selections.invalidate();
            prepared.forEach(language -> selections.cache(language.locale(), language.snapshot()));
        }
        install(defaultLanguage);
    }

    public PreparedLanguage englishFallback(String locale) {
        String canonical = canonicalLocale(locale);
        return new PreparedLanguage(canonical, languageFile(canonical),
                LocalizationSnapshot.create(LocalizationCandidate.english(CATALOG, PluralSelector.oneOther())));
    }

    public ComponentText render(CommandSender sender, String key, MessageArgs arguments) {
        return render(sender, (TextKey) CATALOG.require(key), arguments);
    }

    public ComponentText render(CommandSender sender, TextKey key, MessageArgs arguments) {
        LocalizationSnapshot snapshot = selections == null ? manager.snapshot()
                : sender instanceof Player player ? selections.snapshot(player.getUniqueId()) : selections.snapshot();
        TextKey definition = (TextKey) CATALOG.require(key.id());
        if (definition.placeholders().contains("prefix")) {
            if (arguments.names().contains("prefix")) {
                throw new IllegalArgumentException("The prefix message argument is managed by Brimstone");
            }
            MessageArgs.Builder withPrefix = MessageArgs.builder();
            arguments.arguments().values().forEach(withPrefix::add);
            arguments = withPrefix.trusted("prefix", snapshot.resolve((TextKey) CATALOG.require(BrimstoneMessages.PREFIX),
                    MessageArgs.empty()).template()).build();
        }
        String template = snapshot.resolve(definition, arguments).template();
        TagResolver.Builder tags = TagResolver.builder();
        int index = 0;
        for (MessageArgument argument : arguments.arguments().values()) {
            String tag = "brimstone_argument_" + index++;
            Component value = argument.kind() == MessageArgumentKind.UNTRUSTED
                    ? Component.text(String.valueOf(argument.value()))
                    : MARKUP.deserialize(ComponentText.normalizeMarkup(String.valueOf(argument.value())));
            tags.resolver(Placeholder.component(tag, value));
            template = template.replace("{" + argument.name() + "}", "<" + tag + ">");
        }
        return ComponentText.component(MARKUP.deserialize(ComponentText.normalizeMarkup(template), tags.build()));
    }

    public ComponentText render(CommandSender sender, String key) {
        return render(sender, key, MessageArgs.empty());
    }

    public DirectorTextResolver directorResolver() {
        return (key, arguments) -> render(null, key, arguments).miniMessage();
    }

    public List<String> availableLocales() {
        return availableLocales;
    }

    public String localeDisplayName(String locale) {
        String normalized = locale == null ? "" : locale.trim();
        return metas.getOrDefault(normalized, LanguageMeta.empty()).name()
                .or(() -> VolmitLocales.displayName(normalized)).orElse(normalized);
    }

    public LanguageMeta meta(String locale) {
        return metas.getOrDefault(locale == null ? "" : locale.trim(), LanguageMeta.empty());
    }

    public File languageDirectory() {
        return languageDirectory;
    }

    public File languageFile(String locale) {
        return directory.languageFile(canonicalLocale(locale)).toFile();
    }

    public File activeFile() {
        return active == null ? languageFile(CATALOG.englishLocale()) : active.file();
    }

    public List<File> files() {
        try {
            List<File> installed = new ArrayList<>();
            for (String locale : directory.scan()) {
                installed.add(directory.languageFile(locale).toFile());
            }
            return List.copyOf(installed);
        } catch (IOException exception) {
            logger.log(Level.WARNING, "Could not list Brimstone language files", exception);
            return List.of();
        }
    }

    public boolean isLanguageFile(File file) {
        return directory.isLanguageFile(file);
    }

    public void setSelfWriteListener(BiConsumer<File, String> listener) {
        selfWriteListener = listener;
    }

    public static String canonicalLocale(String locale) {
        String required = LanguageDirectory.requireLocale(locale);
        for (String known : VolmitLocales.all()) {
            if (LanguageDirectory.sameLocale(known, required)) {
                return known;
            }
        }
        return required;
    }

    @Override
    public synchronized void close() {
        if (sync != null) {
            sync.close();
        }
        if (selections != null) {
            selections.close();
        }
        selfWriteListener = null;
    }

    private PreparedLanguage parse(String locale, String content) throws IOException {
        List<LocaleOverlay> overlays = new ArrayList<>();
        overlays.add(overlay(locale, TomlLanguageParser.parseValidText(content, CATALOG)));
        overlays.addAll(baseOverlays(locale, content));
        try {
            return new PreparedLanguage(locale, languageFile(locale), LocalizationSnapshot.create(
                    new LocalizationCandidate(CATALOG, overlays, PluralSelector.oneOther())));
        } catch (RuntimeException exception) {
            throw new IOException("Invalid language: " + locale, exception);
        }
    }

    private LocaleOverlay overlay(String locale, Map<String, String> values) {
        LocaleOverlay.Builder overlay = LocaleOverlay.builder("Brimstone:" + locale, locale);
        values.forEach(overlay::text);
        return overlay.build();
    }

    private List<LocaleOverlay> baseOverlays(String locale, String content) throws IOException {
        List<LocaleOverlay> overlays = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        visited.add(canonicalLocale(locale));
        LanguageMeta meta = metaFromContent(locale, content);
        int depth = 0;
        while (meta.base().isPresent() && depth < MAXIMUM_BASE_DEPTH) {
            String base = canonicalLocale(meta.base().get());
            if (!visited.add(base) || base.equals(CATALOG.englishLocale())) {
                break;
            }
            Path baseFile = directory.languageFile(base);
            if (!Files.isRegularFile(baseFile, LinkOption.NOFOLLOW_LINKS)) {
                break;
            }
            String baseContent = LanguageDirectory.readContent(baseFile);
            overlays.add(overlay(base, TomlLanguageParser.parseValidText(baseContent, CATALOG)));
            meta = metaFromContent(base, baseContent);
            depth++;
        }
        return overlays;
    }

    private LanguageMeta metaFromContent(String locale, String content) {
        try {
            return LanguageMeta.parse(content).withoutSelfBase(locale);
        } catch (IOException exception) {
            return LanguageMeta.empty();
        }
    }

    private void ensureEnglish() throws IOException {
        if (sync == null) {
            File english = languageFile(CATALOG.englishLocale());
            if (!english.exists()) {
                String content = LanguageReferenceRenderer.render(CATALOG, header(CATALOG.englishLocale()));
                AtomicFileIO.writeString(english.toPath(), content);
                noteSelfWrite(english, content);
            }
            return;
        }
        try {
            sync.mergeEnglish().ifPresent(result -> logger.info("Updated Brimstone language "
                    + CATALOG.englishLocale() + " from the built-in catalog: " + result.summary() + "."));
        } catch (IOException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IOException("Could not refresh the English language file", exception);
        }
    }

    private void createCustomLanguage(String locale) throws IOException {
        File target = languageFile(locale);
        String content = LanguageReferenceRenderer.render(CATALOG, header(locale), LanguageMeta.of(locale, CATALOG.englishLocale()));
        AtomicFileIO.writeString(target.toPath(), content);
        noteSelfWrite(target, content);
    }

    private void refreshAvailableLocales() throws IOException {
        LinkedHashSet<String> choices = new LinkedHashSet<>();
        choices.add(CATALOG.englishLocale());
        if (manifest != null) {
            choices.addAll(manifest.locales());
        }
        List<String> installed = directory.scan();
        choices.addAll(installed);
        Map<String, LanguageMeta> discovered = new LinkedHashMap<>();
        for (String locale : installed) {
            try {
                LanguageMeta meta = directory.meta(locale);
                if (!meta.isEmpty()) {
                    discovered.put(locale, meta);
                }
            } catch (IOException exception) {
                logger.log(Level.FINE, "Could not read Brimstone language metadata for " + locale, exception);
            }
        }
        metas = Map.copyOf(discovered);
        availableLocales = sorted(choices);
    }

    private static List<String> sorted(Set<String> locales) {
        List<String> ordered = new ArrayList<>(locales);
        ordered.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(ordered);
    }

    private LocalizationSnapshot selectionSnapshot(String locale) throws Exception {
        String canonical = canonicalLocale(locale);
        if (!directory.exists(canonical) && isRepositoryLocale(canonical)) {
            if (sync == null) {
                throw new IOException("Language " + canonical + " is not installed and the language source is "
                        + sourceDescription());
            }
            logger.info("Installing Brimstone language " + canonical + " from " + sourceDescription() + "...");
            sync.install(canonical);
            logger.info("Installed Brimstone language " + canonical + " to "
                    + languageFile(canonical).toPath().toAbsolutePath().normalize() + ".");
        }
        return prepare(canonical).snapshot();
    }

    private synchronized LocalizationSnapshot saveMessage(PluginLanguageEditor.Edit edit) throws IOException {
        PreparedLanguage before = prepare(edit.locale());
        MessageKey key = CATALOG.require(edit.key());
        if (!before.snapshot().value(key).equals(edit.expected()) || !(edit.value() instanceof TextValue value)) {
            throw new IOException("This message changed while the editor was open; reopen it and try again");
        }
        if (!before.file().isFile()) {
            throw new IOException("Language file is not installed: " + before.locale());
        }
        PreparedLanguage prepared = LanguageFileEditor.update(before.file().toPath(), original -> {
            PreparedLanguage latest = parse(before.locale(), original);
            if (!latest.snapshot().value(key).equals(edit.expected())) {
                throw new IOException("This message changed while the editor was open; reopen it and try again");
            }
            String content = TomlLanguageEditor.upsertText(original, edit.key(), value.template()).content();
            return new LanguageFileEditor.Prepared<>(content, parse(before.locale(), content));
        });
        if (active != null && active.locale().equals(prepared.locale())) {
            install(prepared);
        }
        if (selections != null) {
            selections.cache(prepared.locale(), prepared.snapshot());
        }
        noteSelfWrite(prepared.file(), Files.readString(prepared.file().toPath()));
        return prepared.snapshot();
    }

    private void validateContent(String locale, String content) throws IOException {
        parse(locale, content);
    }

    private <T> T commitUpstream(LanguageUpstreamSync.CommitAction<T> action) throws IOException {
        PluginLanguageService activeSelections = selections;
        return activeSelections == null ? action.apply() : activeSelections.commitUpdate(action::apply);
    }

    private void noteUpstreamWrite(Path file, String content) {
        noteSelfWrite(file.toFile(), content);
    }

    private void noteSelfWrite(File file, String content) {
        BiConsumer<File, String> listener = selfWriteListener;
        if (listener != null) {
            listener.accept(file, content);
        }
    }

    private void applyUpstream(String locale, String content, LanguageCatalogMerge.MergeResult result) throws IOException {
        PreparedLanguage prepared = parse(locale, content);
        refreshAvailableLocales();
        PluginLanguageService activeSelections = selections;
        if (activeSelections != null) {
            activeSelections.cache(locale, prepared.snapshot());
        }
        PreparedLanguage current = active;
        if (current != null && current.locale().equals(locale)) {
            manager.install(prepared.snapshot());
            active = prepared;
        }
    }

    private LanguageUpstreamSync.SyncReport logUpstreamReport(LanguageUpstreamSync.SyncReport report) {
        String revision = report.revision().map(sha -> sha.substring(0, Math.min(7, sha.length()))).orElse("unknown");
        for (String locale : report.changedLocales()) {
            LanguageCatalogMerge.MergeResult result = report.merged().get(locale);
            String source = locale.equals(CATALOG.englishLocale()) ? "the built-in catalog" : sourceDescription() + " (" + revision + ")";
            logger.info("Updated Brimstone language " + locale + " from " + source + ": " + result.summary() + ".");
        }
        if (report.coolingDown()) {
            logger.info("Brimstone language source " + sourceDescription() + " was checked recently; the next check is allowed in "
                    + report.cooldownRemaining() + ".");
        } else if (report.failure().isEmpty() && report.changedLocales().isEmpty() && report.failures().isEmpty()
                && report.revision().isPresent()) {
            logger.info("Brimstone languages match " + sourceDescription() + " (" + revision + ").");
        }
        return report;
    }

    private static List<String> header(String locale) {
        return LanguageFileHeader.render(new LanguageFileHeader.Options("Brimstone", locale,
                List.of("runtime.prefix supplies the styled plugin name for {prefix}. Chat templates add the gray › separator.",
                        "Remove the leading {prefix}&r &7› &7 from a message to hide its name and separator."),
                List.of("Colors and styles: &0-&f, &k-&r.", "RGB colors: &#RRGGBB, &xRRGGBB, &x&R&R&G&G&B&B, [RRGGBB].",
                        "MiniMessage supports custom formatting. Put a backslash before & or [ to display it literally."),
                Map.ofEntries(
                        Map.entry("active", "Active owned TNT count"),
                        Map.entry("added", "Messages added by a language update"),
                        Map.entry("after", "Value after the change"),
                        Map.entry("amount", "Number of launchers given"),
                        Map.entry("argument", "Unexpected command argument"),
                        Map.entry("before", "Value before the change"),
                        Map.entry("command", "Command path"),
                        Map.entry("count", "Number of entries"),
                        Map.entry("group", "Message category name"),
                        Map.entry("id", "TNT type identifier"),
                        Map.entry("kept", "Local edits kept by a language update"),
                        Map.entry("key", "Parameter or message key"),
                        Map.entry("line", "Message line number"),
                        Map.entry("locale", "Locale identifier"),
                        Map.entry("maximum", "Maximum accepted value"),
                        Map.entry("number", "Entry number"),
                        Map.entry("page", "Current page"),
                        Map.entry("pages", "Total pages"),
                        Map.entry("parameter", "Parameter name"),
                        Map.entry("path", "Local report path"),
                        Map.entry("permission", "Required permission"),
                        Map.entry("personal", "Personal locale when it differs from the server default"),
                        Map.entry("player", "Player name"),
                        Map.entry("plugin", "Plugin name"),
                        Map.entry("prefix", "Styled plugin name from runtime.prefix; the separator is part of the message"),
                        Map.entry("reason", "Failure reason"),
                        Map.entry("remaining", "Time until the next language check is allowed"),
                        Map.entry("secondary", "Secondary effect count"),
                        Map.entry("section", "Language editor section"),
                        Map.entry("step", "Editor step"),
                        Map.entry("target", "Language selection target"),
                        Map.entry("type", "Parameter type"),
                        Map.entry("types", "Configured TNT types"),
                        Map.entry("updated", "Messages changed by a language update"),
                        Map.entry("url", "Public report URL"),
                        Map.entry("value", "Current, default, or raw value"),
                        Map.entry("variables", "Placeholders valid for the selected message"),
                        Map.entry("version", "Plugin version")
                )));
    }

    public record PreparedLanguage(String locale, File file, LocalizationSnapshot snapshot) {
    }
}
