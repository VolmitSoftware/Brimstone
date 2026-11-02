package com.volmit.brimstone.localization;

import art.arcane.volmlib.util.localization.LanguageUpstreamSync;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.PluginLanguageService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteLanguagesTest {
    private static final String REVISION_ONE = "1111111111111111111111111111111111111111";
    private static final String REVISION_TWO = "2222222222222222222222222222222222222222";

    @TempDir
    Path directory;

    @Test
    void unpublishedRepositoryKeepsEnglishAndReportsTheFailure() throws Exception {
        try (LoopbackRepository repository = new LoopbackRepository();
             LanguageService language = repository.service(directory.toFile())) {
            repository.published.set(false);
            language.install(language.prepare("de_DE"));
            assertEquals("Brimstone › You do not have permission.", language.render(null, "no-permission").plain());
            assertFalse(Files.exists(language.languageFile("de_DE").toPath()));
            assertTrue(Files.exists(language.languageFile("en_US").toPath()));
            LanguageUpstreamSync.SyncReport report = language.updateAction().update().get(10, TimeUnit.SECONDS);
            assertTrue(report.failure().isPresent());
            assertTrue(language.upstreamStatus().orElseThrow().lastError().isPresent());
            assertTrue(language.sourceDescription().startsWith("VolmitSoftware/Brimstone@"));
            PluginLanguageService selections = language.initializeSelections(() -> "en_US", (locale, snapshot) -> {});
            UUID player = UUID.randomUUID();
            selections.selectPlayer(player, "de_DE").get(10, TimeUnit.SECONDS);
            assertEquals("en_US", selections.effectiveLocale(player));
            assertFalse(Files.exists(language.languageFile("de_DE").toPath()));
        }
    }

    @Test
    void forcedUpdateMergesRepositoryChangesAndKeepsLocalEditsAndCustomFiles() throws Exception {
        try (LoopbackRepository repository = new LoopbackRepository();
             LanguageService language = repository.service(directory.toFile())) {
            List<String> selfWrites = new CopyOnWriteArrayList<>();
            language.setSelfWriteListener((file, content) -> selfWrites.add(file.getName()));
            repository.files.put(REVISION_ONE + "/de_DE",
                    "\"no-permission\" = \"Keine Berechtigung.\"\n\"player.missing\" = \"Spieler fehlt.\"\n");
            language.install(language.prepare("en_US"));
            assertTrue(language.requestInstall("de_DE").get(10, TimeUnit.SECONDS).contains("Keine Berechtigung."));
            assertTrue(Files.isRegularFile(directory.resolve("languages/.upstream/de_DE.toml")));
            assertTrue(Files.isRegularFile(directory.resolve("languages/.upstream/upstream.properties")));
            assertTrue(selfWrites.contains("de_DE.toml"));
            language.install(language.prepare("de_DE"));
            assertEquals("Keine Berechtigung.", language.render(null, "no-permission").plain());

            Path german = language.languageFile("de_DE").toPath();
            Files.writeString(german, Files.readString(german).replace("Keine Berechtigung.", "Mein Text."));
            language.install(language.prepare("de_DE"));
            Path pirate = language.languageFile("pirate").toPath();
            String custom = "\"no-permission\" = \"Arr, no permission.\"\n\n[meta]\nname = \"Pirate Speak\"\nbase = \"de_DE\"\n";
            Files.writeString(pirate, custom);
            repository.revision.set(REVISION_TWO);
            repository.files.put(REVISION_TWO + "/de_DE", "\"no-permission\" = \"Kein Zugriff.\"\n"
                    + "\"player.missing\" = \"Spieler ist offline.\"\n\"list\" = \"TNT-Typen: {types}\"\n");

            LanguageUpstreamSync.SyncReport report = language.updateAction().update().get(10, TimeUnit.SECONDS);

            assertTrue(report.failure().isEmpty());
            assertEquals(List.of("de_DE"), report.changedLocales());
            assertEquals(List.of("no-permission"), List.copyOf(report.merged().get("de_DE").kept()));
            assertEquals(List.of("list"), List.copyOf(report.merged().get("de_DE").added()));
            String merged = Files.readString(german);
            assertTrue(merged.contains("Mein Text.") && merged.contains("Spieler ist offline.") && merged.contains("TNT-Typen"));
            assertFalse(merged.contains("Kein Zugriff."));
            assertTrue(Files.readString(directory.resolve("languages/.upstream/de_DE.toml")).contains("Kein Zugriff."));
            assertEquals(custom, Files.readString(pirate));
            assertEquals("Spieler ist offline.", language.render(null, "player.missing").plain());
            assertEquals(REVISION_TWO, language.upstreamStatus().orElseThrow().revision().orElseThrow());

            LanguageUpstreamSync.SyncReport unchanged = language.updateAction().update().get(10, TimeUnit.SECONDS);

            assertTrue(unchanged.unchanged());
            assertTrue(unchanged.changedLocales().isEmpty());
            assertEquals(merged, Files.readString(german));
        }
    }

    @Test
    void repeatedChecksInsideTheMinimumIntervalReportTheCooldown() throws Exception {
        try (LoopbackRepository repository = new LoopbackRepository();
             LanguageService language = repository.service(directory.toFile(), Duration.ofMinutes(5))) {
            language.install(language.prepare("en_US"));
            assertTrue(language.startUpstreamCheck().get(10, TimeUnit.SECONDS).successful());
            LanguageUpstreamSync.SyncReport repeated = language.updateAction().update().get(10, TimeUnit.SECONDS);
            assertTrue(repeated.coolingDown());
            assertEquals(1, repository.apiRequests.size());
        }
    }

    @Test
    void selectionInstallsMissingTranslationOnTheSharedWorker() throws Exception {
        String catalog = "\"no-permission\" = \"Keine Berechtigung.\"\n";
        try (LoopbackRepository repository = new LoopbackRepository();
             LanguageService language = repository.service(directory.toFile())) {
            repository.files.put(REVISION_ONE + "/de_DE", catalog);
            language.install(language.prepare("en_US"));
            PluginLanguageService selections = language.initializeSelections(() -> "en_US", (locale, snapshot) -> {});
            UUID player = UUID.randomUUID();
            selections.selectPlayer(player, "de_DE").get(10, TimeUnit.SECONDS);
            assertEquals("de_DE", selections.effectiveLocale(player));
            assertTrue(Files.readString(language.languageFile("de_DE").toPath()).contains("Keine Berechtigung."));
        }
    }

    @Test
    void downloadedEntryWithMismatchedPlaceholdersFallsBackToEnglish() throws Exception {
        try (LoopbackRepository repository = new LoopbackRepository();
             LanguageService language = repository.service(directory.toFile())) {
            repository.files.put(REVISION_ONE + "/de_DE",
                    "\"list\" = \"Missing the required argument\"\n\"player.missing\" = \"Spieler fehlt.\"\n");
            language.install(language.prepare("en_US"));
            language.requestInstall("de_DE").get(10, TimeUnit.SECONDS);
            language.install(language.prepare("de_DE"));
            assertEquals("Spieler fehlt.", language.render(null, "player.missing").plain());
            assertEquals("Brimstone › TNT types: love", language.render(null, "list",
                    MessageArgs.builder().untrusted("types", "love").build()).plain());
        }
    }

    private static final class LoopbackRepository implements AutoCloseable {
        private final HttpServer server;
        private final AtomicReference<String> revision = new AtomicReference<>(REVISION_ONE);
        private final AtomicReference<Boolean> published = new AtomicReference<>(true);
        private final Map<String, String> files = new ConcurrentHashMap<>();
        private final List<String> apiRequests = new CopyOnWriteArrayList<>();

        private LoopbackRepository() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/", this::api);
            server.createContext("/raw/", this::raw);
            server.start();
        }

        private LanguageService service(File dataFolder) {
            return service(dataFolder, Duration.ZERO);
        }

        private LanguageService service(File dataFolder, Duration minimumCheckInterval) {
            URI root = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
            return new LanguageService(dataFolder, Logger.getLogger("Brimstone-test"), root.resolve("api/"), root.resolve("raw/"),
                    minimumCheckInterval);
        }

        @Override
        public void close() {
            server.stop(0);
        }

        private void api(HttpExchange exchange) throws IOException {
            apiRequests.add(exchange.getRequestURI().toString());
            if (!published.get()) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            String etag = "\"" + revision.get() + "\"";
            if (etag.equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
                exchange.sendResponseHeaders(304, -1);
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().set("ETag", etag);
            byte[] response = ("[{\"sha\":\"" + revision.get() + "\"}]").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        }

        private void raw(HttpExchange exchange) throws IOException {
            String[] parts = exchange.getRequestURI().getPath().split("/");
            String content = files.get(parts[4] + "/" + parts[parts.length - 1].replace(".toml", ""));
            if (content == null) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            byte[] response = content.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        }
    }
}
