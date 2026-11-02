package com.volmit.brimstone.localization;

import art.arcane.volmlib.util.localization.LanguageMeta;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.LanguageAudience;
import art.arcane.volmlib.util.localization.MessageCatalog;
import art.arcane.volmlib.util.localization.MessageKey;
import art.arcane.volmlib.util.localization.PluginLanguageService;
import art.arcane.volmlib.util.localization.PluginLanguageEditor;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.localization.TomlLanguageEditor;
import art.arcane.volmlib.util.localization.TomlLanguageParser;
import art.arcane.volmlib.util.localization.TextValue;
import art.arcane.volmlib.util.localization.VolmitLocales;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LanguageServiceTest {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{[A-Za-z0-9_.-]+}");

    @TempDir
    Path directory;

    @Test
    void everySourceCatalogHasCompleteKeysAndMatchingPlaceholders() throws IOException {
        MessageCatalog catalog = BrimstoneMessages.catalog();
        try (LanguageService language = service()) {
            Map<String, String> english = TomlLanguageParser.parseValidText(Files.readString(language.prepare("en_US").file().toPath()), catalog);
            for (String locale : VolmitLocales.all()) {
                LanguageService.PreparedLanguage prepared = language.prepare(locale);
                Map<String, String> entries = TomlLanguageParser.parseValidText(Files.readString(prepared.file().toPath()), catalog);
                assertEquals(catalog.byId().keySet(), entries.keySet(), locale);
                for (MessageKey key : catalog.keys()) {
                    assertEquals(key.englishValue().placeholders(), new TextValue(entries.get(key.id())).placeholders(), locale + ":" + key.id());
                    assertEquals(placeholderOccurrences(english.get(key.id())), placeholderOccurrences(entries.get(key.id())), locale + ":" + key.id());
                }
            }
        }
    }

    @Test
    void generatedEnglishHeaderDocumentsEveryCatalogPlaceholder() throws IOException {
        try (LanguageService language = service()) {
            String english = Files.readString(language.prepare("en_US").file().toPath());
            assertTrue(english.startsWith("# Brimstone — en_US\n"));
            Set<String> documented = new HashSet<>();
            Matcher matcher = Pattern.compile("^#   \\{([A-Za-z0-9_]+)}  ", Pattern.MULTILINE).matcher(english);
            while (matcher.find()) {
                documented.add(matcher.group(1));
            }
            for (MessageKey key : BrimstoneMessages.catalog().keys()) {
                assertTrue(documented.containsAll(key.englishValue().placeholders()), key.id());
            }
        }
    }

    @Test
    void customLocalesUseTheirMetaNameAndBaseChainBeforeEnglish() throws Exception {
        try (LanguageService language = service()) {
            language.install(language.prepare("en_US"));
            Files.writeString(language.languageFile("de_DE").toPath(), "\"player.missing\" = \"Spieler fehlt.\"\n");
            Files.writeString(language.languageFile("pirate").toPath(), "\"no-permission\" = \"Arr, no permission.\"\n\n"
                    + "[meta]\nname = \"Pirate Speak\"\nbase = \"de_DE\"\n");
            language.install(language.prepare("pirate"));
            assertEquals("Arr, no permission.", language.render(null, "no-permission").plain());
            assertEquals("Spieler fehlt.", language.render(null, "player.missing").plain());
            assertEquals("Brimstone › TNT types: love", language.render(null, "list",
                    MessageArgs.builder().untrusted("types", "love").build()).plain());
            assertTrue(language.availableLocales().contains("pirate"));
            assertEquals("Pirate Speak", language.localeDisplayName("pirate"));
            assertEquals("German (Germany)", language.localeDisplayName("de_DE"));
            assertEquals("Pirate Speak", language.displayNames().displayName("pirate"));
            assertNull(language.displayNames().displayName("de_DE"));
            assertEquals("de_DE", language.meta("pirate").base().orElseThrow());
            assertEquals("pirate", LanguageService.canonicalLocale("pirate"));
            assertEquals("ja-JP", LanguageService.canonicalLocale("JA_jp"));
            assertThrows(IllegalArgumentException.class, () -> LanguageService.canonicalLocale("../en_US"));

            language.prepare("shanty");
            LanguageMeta generated = LanguageMeta.parse(Files.readString(language.languageFile("shanty").toPath()));
            assertEquals("shanty", generated.name().orElseThrow());
            assertEquals("en_US", generated.base().orElseThrow());
            assertTrue(language.availableLocales().contains("shanty"));
            assertTrue(language.files().contains(language.languageFile("shanty")));
            assertTrue(language.isLanguageFile(language.languageFile("shanty")));
            assertFalse(language.isLanguageFile(directory.resolve("languages/.upstream/en_US.toml").toFile()));
        }
    }

    @Test
    void englishReferenceGainsNewCatalogKeysWhileKeepingEdits() throws IOException {
        try (LanguageService first = service()) {
            first.prepare("en_US");
        }
        Path english = directory.resolve("languages/en_US.toml");
        Path sidecar = directory.resolve("languages/.upstream/en_US.toml");
        String olderReference = TomlLanguageEditor.remove(Files.readString(sidecar), "no-permission").content();
        Files.writeString(sidecar, olderReference);
        Files.writeString(english, TomlLanguageEditor.upsertText(olderReference, BrimstoneMessages.PREFIX, "&6Boom").content());
        try (LanguageService language = service()) {
            language.install(language.prepare("en_US"));
            Map<String, String> values = TomlLanguageParser.parseText(Files.readString(english));
            assertTrue(values.containsKey("no-permission"));
            assertEquals("&6Boom", values.get(BrimstoneMessages.PREFIX));
            assertEquals("Boom › You do not have permission.", language.render(null, "no-permission").plain());
            assertTrue(Files.readString(sidecar).contains("no-permission"));
        }
    }

    @Test
    void missingMessagesUseEnglishAndUntrustedArgumentsStayLiteral() throws IOException {
        try (LanguageService language = service()) {
            LanguageService.PreparedLanguage german = language.prepare("de_DE");
            Files.writeString(german.file().toPath(), "\"no-permission\" = \"Keine Berechtigung.\"\n");
            language.install(language.prepare("de_DE"));
            assertEquals("Keine Berechtigung.", language.render(null, "no-permission").plain());
            assertEquals("Brimstone › That player is not online.", language.render(null, "player.missing").plain());
            assertEquals("Brimstone › TNT types: <red>test</red>", language.render(null, "list",
                    MessageArgs.builder().untrusted("types", "<red>test</red>").build()).plain());
        }
    }

    @Test
    void allInstalledLanguagesAreValidatedBeforeReloadAndUserEditsSurvivePreparation() throws IOException {
        try (LanguageService language = service()) {
            language.install(language.prepare("en_US"));
            LanguageService.PreparedLanguage german = language.prepare("de_DE");
            String original = Files.readString(german.file().toPath());
            language.prepare("de_DE");
            assertEquals(original, Files.readString(german.file().toPath()));
            Files.writeString(german.file().toPath(), "broken = [");
            assertThrows(IOException.class, () -> language.prepareReload("en_US"));
            assertEquals("Brimstone › You do not have permission.", language.render(null, "no-permission").plain());
            language.install(language.englishFallback("de_DE"));
            assertEquals("Brimstone › You do not have permission.", language.render(null, "no-permission").plain());
        }
    }

    @Test
    void personalLocalePersistsAcrossRestart() throws Exception {
        UUID player = UUID.randomUUID();
        try (LanguageService language = service()) {
            language.install(language.prepare("en_US"));
            PluginLanguageService selections = language.initializeSelections(() -> "en_US", (locale, snapshot) -> {});
            selections.selectPlayer(player, "ja_JP").get(10, TimeUnit.SECONDS);
            assertEquals("ja-JP", selections.effectiveLocale(player));
        }
        try (LanguageService language = service()) {
            language.install(language.prepare("en_US"));
            PluginLanguageService selections = language.initializeSelections(() -> "en_US", (locale, snapshot) -> {});
            assertEquals("ja-JP", selections.effectiveLocale(player));
            assertTrue(Files.isRegularFile(directory.resolve("languages/language-preferences.properties")));
        }
    }

    @Test
    void editorPersistsTranslationsAndRefreshesPlayerAndDefaultMessages() throws Exception {
        UUID player = UUID.randomUUID();
        TextKey key = (TextKey) BrimstoneMessages.catalog().require("no-permission");
        try (LanguageService language = service()) {
            language.install(language.prepare("en_US"));
            PluginLanguageService selections = language.initializeSelections(() -> "en_US", (locale, snapshot) -> {});
            selections.selectPlayer(player, "de_DE").get(10, TimeUnit.SECONDS);
            String english = Files.readString(language.activeFile().toPath());
            try (PluginLanguageEditor editor = new PluginLanguageEditor(selections, language.editorOptions())) {
                PluginLanguageEditor.Document document = editor.load("de_DE").get(10, TimeUnit.SECONDS);
                editor.save(new PluginLanguageEditor.Edit("de_DE", key.id(), document.snapshot().value(key),
                        new TextValue("Zugriff verweigert."))).get(10, TimeUnit.SECONDS);
                assertEquals("Brimstone › You do not have permission.", language.render(null, key.id()).plain());
                assertEquals(english, Files.readString(language.activeFile().toPath()));
                assertEquals("Zugriff verweigert.", LanguageAudience.call(player,
                        () -> language.render(null, key.id()).plain()));
                language.install(language.prepare("de_DE"));
                assertEquals("Zugriff verweigert.", language.render(null, key.id()).plain());
            }
        }
        try (LanguageService language = service()) {
            language.install(language.prepare("de_DE"));
            assertEquals("Zugriff verweigert.", language.render(null, key.id()).plain());
        }
    }

    @Test
    void editorRejectsStaleMessagesAndInvalidPlaceholdersWithoutChangingDisk() throws Exception {
        TextKey key = (TextKey) BrimstoneMessages.catalog().require("list");
        try (LanguageService language = service()) {
            language.install(language.prepare("en_US"));
            PluginLanguageService selections = language.initializeSelections(() -> "en_US", (locale, snapshot) -> {});
            try (PluginLanguageEditor editor = new PluginLanguageEditor(selections, language.editorOptions())) {
                PluginLanguageEditor.Document document = editor.load("en_US").get(10, TimeUnit.SECONDS);
                TextValue saved = new TextValue("Available TNT: {types}");
                editor.save(new PluginLanguageEditor.Edit("en_US", key.id(), document.snapshot().value(key), saved))
                        .get(10, TimeUnit.SECONDS);
                String content = Files.readString(language.activeFile().toPath());
                assertThrows(ExecutionException.class, () -> editor.save(new PluginLanguageEditor.Edit(
                        "en_US", key.id(), document.snapshot().value(key), new TextValue("Old edit: {types}")))
                        .get(10, TimeUnit.SECONDS));
                assertThrows(ExecutionException.class, () -> editor.save(new PluginLanguageEditor.Edit(
                        "en_US", key.id(), saved, new TextValue("Missing required argument")))
                        .get(10, TimeUnit.SECONDS));
                assertEquals(content, Files.readString(language.activeFile().toPath()));
                assertEquals("Available TNT: love", language.render(null, key.id(),
                        MessageArgs.builder().untrusted("types", "love").build()).plain());
            }
        }
    }

    @Test
    void sharedMenuMessagesFollowTheCurrentPlayersLanguageAudience() throws Exception {
        UUID player = UUID.randomUUID();
        TextKey key = (TextKey) BrimstoneMessages.catalog().require("command.description.give");
        try (LanguageService language = service()) {
            language.install(language.prepare("en_US"));
            PluginLanguageService selections = language.initializeSelections(() -> "en_US", (locale, snapshot) -> {});
            selections.selectPlayer(player, "de_DE").get(10, TimeUnit.SECONDS);
            String translated = ((TextValue) language.prepare("de_DE").snapshot().value(key)).template();
            assertEquals(translated, LanguageAudience.call(player,
                    () -> language.directorResolver().resolve(key, MessageArgs.empty())));
            assertEquals("Give a TNT launcher to a player.", language.directorResolver().resolve(key, MessageArgs.empty()));
        }
    }

    private LanguageService service() throws IOException {
        Path languages = directory.resolve("languages");
        Files.createDirectories(languages);
        for (String locale : VolmitLocales.nonEnglish()) {
            Path target = languages.resolve(locale + ".toml");
            if (!Files.exists(target)) {
                Files.copy(Path.of(System.getProperty("brimstone.projectDir"), "src/main/resources/languages", locale + ".toml"), target);
            }
        }
        URI unreachable = URI.create("http://127.0.0.1:9/");
        return new LanguageService(directory.toFile(), Logger.getLogger("Brimstone-test"), unreachable, unreachable, Duration.ZERO);
    }

    private static List<String> placeholderOccurrences(String template) {
        List<String> occurrences = new ArrayList<>();
        Matcher matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            occurrences.add(matcher.group());
        }
        Collections.sort(occurrences);
        return occurrences;
    }
}
