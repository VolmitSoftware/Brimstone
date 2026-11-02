package com.volmit.brimstone;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.Plugin;

import java.io.InputStream;
import java.io.File;
import java.lang.reflect.Parameter;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;

final class RuntimeJarTest {
    private static final String PLUGIN_PACKAGE = "com.volmit.brimstone.";
    private static final String LIBRARY_PACKAGE = PLUGIN_PACKAGE + "libs.";
    private static RuntimeLoader loader;
    private static Path artifact;

    @TempDir
    Path directory;

    @BeforeAll
    static void openRuntimeLoader() throws Exception {
        artifact = Path.of(Objects.requireNonNull(System.getProperty("brimstone.runtimeJar")));
        Path libraries = Path.of(Objects.requireNonNull(System.getProperty("brimstone.runtimeTestLibraries")));
        loader = new RuntimeLoader(new URL[]{artifact.toUri().toURL(), libraries.toUri().toURL()}, RuntimeJarTest.class.getClassLoader());
    }

    @AfterAll
    static void closeRuntimeLoader() throws Exception {
        if (loader != null) {
            loader.close();
        }
    }

    @BeforeEach
    void startServer() {
        MockBukkit.mock();
    }

    @AfterEach
    void stopServer() {
        MockBukkit.unmock();
    }

    @Test
    void packagedDescriptorUsesBrimstoneIdentity() throws Exception {
        try (ZipFile archive = new ZipFile(artifact.toFile());
             InputStream input = archive.getInputStream(archive.getEntry("plugin.yml"))) {
            PluginDescriptionFile description = new PluginDescriptionFile(input);
            assertThat(description.getName()).isEqualTo("Brimstone");
            assertThat(description.getMain()).isEqualTo("com.volmit.brimstone.Brimstone");
            assertThat(description.getCommands()).containsOnlyKeys("brimstone");
            assertThat(description.getCommands().get("brimstone").get("aliases")).isEqualTo(List.of("brim"));
            assertThat(description.getPermissions()).isNotEmpty().allSatisfy(permission ->
                    assertThat(permission.getName()).startsWith("brimstone."));
            assertThat(loader.loadClass(PLUGIN_PACKAGE + "metrics.Metrics").getClassLoader()).isSameAs(loader);
        }
    }

    @Test
    void packagedEntrypointAndBootstrapResolveWithoutInitialization() throws Exception {
        Class<?> plugin = Class.forName(PLUGIN_PACKAGE + "Brimstone", false, loader);
        assertThat(plugin.getClassLoader()).isSameAs(loader);
        assertThat(plugin.getSuperclass().getName()).isEqualTo("org.bukkit.plugin.java.JavaPlugin");
        assertThat(loader.loadClass(LIBRARY_PACKAGE + "slimjar.app.builder.SpigotApplicationBuilder").getClassLoader()).isSameAs(loader);
        assertThat(plugin.getDeclaredConstructor()).isNotNull();
    }

    @Test
    void packagedAdventureLoadsTheRelocatedSerializers() throws Exception {
        Class<?> type = loader.loadClass(LIBRARY_PACKAGE + "volmlib.util.plugin.ComponentText");
        Object text = type.getMethod("markup", String.class).invoke(null, "<gold>Brimstone</gold>");
        assertThat(type.getMethod("plain").invoke(text)).isEqualTo("Brimstone");
        assertThat((String) type.getMethod("legacy").invoke(text)).endsWith("Brimstone");
        assertThat((String) type.getMethod("miniMessage").invoke(text)).contains("Brimstone");
    }

    @Test
    void packagedConfigurationPreservesReflectiveTomlFields() throws Exception {
        Class<?> service = loader.loadClass(PLUGIN_PACKAGE + "config.ConfigService");
        Object configuration = service.getConstructor(File.class).newInstance(directory.toFile());
        Object prepared = service.getMethod("prepareFromDisk").invoke(configuration);
        service.getMethod("install", prepared.getClass()).invoke(configuration, prepared);
        Path file = directory.resolve("config.toml");
        String content = Files.readString(file);
        assertThat(content).contains("cooldownTicks = 30", "tntPerPlayer = 8", "particlesPerTick = 4096", "[metrics]", "enabled = true");
        Files.writeString(file, content.replace("cooldownTicks = 30", "cooldownTicks = 50")
                .replace("tntPerPlayer = 8", "tntPerPlayer = 3")
                .replace("particlesPerTick = 4096", "particlesPerTick = 256")
                .replace("enabled = true", "enabled = false"));
        Object replacement = service.getMethod("prepareFromDisk").invoke(configuration);
        service.getMethod("install", replacement.getClass()).invoke(configuration, replacement);
        Object snapshot = service.getMethod("runtime").invoke(configuration);
        assertThat(snapshot.getClass().getMethod("cooldownTicks").invoke(snapshot)).isEqualTo(50);
        assertThat(snapshot.getClass().getMethod("metricsEnabled").invoke(snapshot)).isEqualTo(false);
        Object limits = snapshot.getClass().getMethod("limits").invoke(snapshot);
        Object effects = snapshot.getClass().getMethod("effects").invoke(snapshot);
        assertThat(limits.getClass().getMethod("tntPerPlayer").invoke(limits)).isEqualTo(3);
        assertThat(effects.getClass().getMethod("particlesPerTick").invoke(effects)).isEqualTo(256);
    }

    @Test
    void packagedDirectorRetainsCommandsAndParameterMetadata() throws Exception {
        Class<?> plugin = loader.loadClass(PLUGIN_PACKAGE + "Brimstone");
        Class<?> commands = loader.loadClass(PLUGIN_PACKAGE + "command.BrimstoneCommands");
        Object instance = commands.getConstructor(plugin).newInstance(new Object[]{null});
        Class<?> factory = loader.loadClass(LIBRARY_PACKAGE + "volmlib.util.director.compat.DirectorEngineFactory");
        Object engine = factory.getMethod("create", Object.class).invoke(null, instance);
        Object root = engine.getClass().getMethod("getRoot").invoke(engine);
        List<?> children = (List<?>) root.getClass().getMethod("getChildren").invoke(root);
        assertThat(children).hasSizeGreaterThanOrEqualTo(5);
        boolean foundDebug = false;
        boolean foundVersion = false;
        for (Object child : children) {
            Object descriptor = child.getClass().getMethod("getDescriptor").invoke(child);
            String name = (String) descriptor.getClass().getMethod("getName").invoke(descriptor);
            assertThat(name).isNotBlank();
            if (name.equals("version")) {
                foundVersion = true;
                assertThat(descriptor.getClass().getMethod("isHidden").invoke(descriptor)).isEqualTo(true);
            }
            if (name.equals("debug")) {
                foundDebug = true;
                List<?> diagnosticCommands = (List<?>) child.getClass().getMethod("getChildren").invoke(child);
                Set<String> diagnosticNames = new HashSet<>();
                for (Object diagnostic : diagnosticCommands) {
                    Object detail = diagnostic.getClass().getMethod("getDescriptor").invoke(diagnostic);
                    diagnosticNames.add((String) detail.getClass().getMethod("getName").invoke(detail));
                }
                assertThat(diagnosticNames).containsExactlyInAnyOrder("dump", "version");
            }
        }
        assertThat(foundDebug).isTrue();
        assertThat(foundVersion).isTrue();
        assertThat(commands.getDeclaredMethods()).filteredOn(method -> !method.isSynthetic()).allSatisfy(method -> {
            for (Parameter parameter : method.getParameters()) {
                assertThat(parameter.isNamePresent()).isTrue();
            }
        });
    }

    @Test
    void packagedSuiteContractSurvivesRelocationAndShrinking() throws Exception {
        Class<?> serviceType = loader.loadClass(PLUGIN_PACKAGE + "integration.BrimstoneIntegrationService");
        Class<?> runtimeType = loader.loadClass(serviceType.getName() + "$RuntimeSnapshot");
        Class<?> countersType = loader.loadClass(PLUGIN_PACKAGE + "diagnostics.EffectDiagnostics");
        Object runtime = runtimeType.getConstructor(boolean.class, int.class, int.class, int.class, int.class)
                .newInstance(true, 2, 3, 7, 7);
        Object counters = countersType.getConstructor().newInstance();
        countersType.getMethod("launchAttempt").invoke(counters);
        Object snapshot = countersType.getMethod("snapshot").invoke(counters);
        Supplier<Object> runtimeSupplier = () -> runtime;
        Supplier<Object> countersSupplier = () -> snapshot;
        Object service = serviceType.getConstructor(String.class, Supplier.class, Supplier.class)
                .newInstance("2.0.0", runtimeSupplier, countersSupplier);
        serviceType.getMethod("register", Plugin.class).invoke(service, MockBukkit.createMockPlugin());
        try {
            serviceType.getMethod("markReady").invoke(service);
            Class<?> protocolType = loader.loadClass(LIBRARY_PACKAGE + "volmlib.integration.IntegrationProtocolVersion");
            Object protocol = protocolType.getConstructor(int.class, int.class).newInstance(1, 1);
            Class<?> requestType = loader.loadClass(LIBRARY_PACKAGE + "volmlib.integration.IntegrationHandshakeRequest");
            Object request = requestType.getConstructor(String.class, String.class, Set.class, Set.class, long.class)
                    .newInstance("consumer", "1", Set.of(protocol), Set.of("metrics"), 1L);
            Object handshake = serviceType.getMethod("handshake", requestType).invoke(service, request);
            assertThat(handshake.getClass().getMethod("accepted").invoke(handshake)).isEqualTo(true);
            assertThat(handshake.getClass().getMethod("responderPluginId").invoke(handshake)).isEqualTo("brimstone");
            Object heartbeat = serviceType.getMethod("heartbeat").invoke(service);
            assertThat(heartbeat.getClass().getMethod("healthy").invoke(heartbeat)).isEqualTo(true);
            assertThat(heartbeat.getClass().getMethod("lastHeartbeatMs").invoke(heartbeat)).isInstanceOf(Long.class);
            Map<?, ?> samples = (Map<?, ?>) serviceType.getMethod("sampleMetrics", Set.class)
                    .invoke(service, Set.of("brimstone.launch-attempts", "brimstone.active-tnt"));
            Object sample = samples.get("brimstone.launch-attempts");
            assertThat(sample.getClass().getMethod("numericValue").invoke(sample)).isEqualTo(1D);
            assertThat(sample.getClass().getMethod("available").invoke(sample)).isEqualTo(true);
            assertThat(sample.getClass().getMethod("sampledAtMs").invoke(sample)).isInstanceOf(Long.class);
            assertThat(sample.getClass().getMethod("message").invoke(sample)).isEqualTo("");
            Object descriptor = sample.getClass().getMethod("descriptor").invoke(sample);
            assertThat(descriptor.getClass().getMethod("key").invoke(descriptor)).isEqualTo("brimstone.launch-attempts");
            assertThat(descriptor.getClass().getMethod("unit").invoke(descriptor)).isEqualTo("launches");
            assertThat(descriptor.getClass().getMethod("tags").invoke(descriptor)).isInstanceOf(Map.class);
            assertThat(descriptor.getClass().getMethod("type").invoke(descriptor).toString()).isEqualTo("LONG");
            assertThat(serviceType.getMethod("metricGroups").invoke(service)).isEqualTo(List.of());
        } finally {
            serviceType.getMethod("close").invoke(service);
        }
        Object stopped = serviceType.getMethod("heartbeat").invoke(service);
        assertThat(stopped.getClass().getMethod("healthy").invoke(stopped)).isEqualTo(false);
    }

    @Test
    void languageManifestTracksTheRepositoryBranchAndBundlesNoCatalogs() throws Exception {
        Set<String> expected = Set.of("de_DE", "es_ES", "fi_FI", "fr_FR", "he_IL", "it_IT", "ja-JP",
                "ko_KR", "lt_LT", "nl_NL", "pl_PL", "pt_PT", "ru_RU", "tr_TR", "vi_VI", "zh_CN", "zh_TW");
        try (ZipFile archive = new ZipFile(artifact.toFile())) {
            Properties manifest = new Properties();
            try (InputStream input = archive.getInputStream(archive.getEntry("brimstone-language-source.properties"))) {
                manifest.load(input);
            }
            assertThat(manifest.getProperty("repository")).isEqualTo("VolmitSoftware/Brimstone");
            assertThat(manifest.getProperty("branch")).matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,127}");
            assertThat(manifest.getProperty("path")).isEqualTo("src/main/resources/languages");
            assertThat(manifest.getProperty("extension")).isEqualTo(".toml");
            assertThat(Set.of(manifest.getProperty("locales").split(","))).isEqualTo(expected);
            assertThat(manifest.stringPropertyNames()).noneMatch(key -> key.equals("revision") || key.equals("mode")
                    || key.equals("origin") || key.startsWith("sha256."));
            assertThat(archive.stream().map(ZipEntry::getName)
                    .filter(name -> name.startsWith("languages/") && name.endsWith(".toml"))).isEmpty();
            for (String locale : expected) {
                assertThat(Files.isRegularFile(Path.of(System.getProperty("brimstone.projectDir"),
                        "src/main/resources/languages", locale + ".toml"))).isTrue();
            }
        }
    }

    @Test
    void packagedLanguageServiceLoadsItsSourceAndGeneratesEnglish() throws Exception {
        Class<?> service = loader.loadClass(PLUGIN_PACKAGE + "localization.LanguageService");
        Object language = service.getConstructor(File.class, Logger.class).newInstance(directory.toFile(), Logger.getLogger("Brimstone-test"));
        try {
            assertThat(service.getMethod("sourceFailure").invoke(language)).isEqualTo(Optional.empty());
            assertThat(service.getMethod("sourceDescription").invoke(language)).asString().startsWith("VolmitSoftware/Brimstone@");
            service.getMethod("prepare", String.class).invoke(language, "en_US");
            assertThat(Files.readString(directory.resolve("languages/en_US.toml"))).startsWith("# Brimstone — en_US");
            assertThat(directory.resolve("languages/.upstream/en_US.toml")).isRegularFile();
        } finally {
            service.getMethod("close").invoke(language);
        }
    }

    private static final class RuntimeLoader extends URLClassLoader {
        private RuntimeLoader(URL[] artifacts, ClassLoader parent) {
            super(artifacts, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith(PLUGIN_PACKAGE)) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    loaded = findClass(name);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }
    }
}
