package com.volmit.brimstone;

import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

final class PluginMetadataTest {
    @Test
    void descriptorDeclaresIdentityAliasesAndPermissionDefaults() throws Exception {
        PluginDescriptionFile description;
        try (InputStream input = Objects.requireNonNull(getClass().getResourceAsStream("/plugin.yml"))) {
            description = new PluginDescriptionFile(input);
        }
        assertThat(description.getName()).isEqualTo("Brimstone");
        assertThat(description.getMain()).isEqualTo("com.volmit.brimstone.Brimstone");
        assertThat(description.getAPIVersion()).isEqualTo("26.1");
        assertThat(description.getAuthors()).containsExactly("VolmitSoftware", "Arcane Arts", "SwiftSwamp", "NextdoorPsycho");
        assertThat(description.getWebsite()).isEqualTo("https://volmitsoftware.com");
        assertThat(description.getCommands()).containsOnlyKeys("brimstone");
        assertThat(description.getCommands().get("brimstone").get("aliases")).isEqualTo(List.of("brim"));
        Map<String, PermissionDefault> defaults = description.getPermissions().stream()
                .collect(Collectors.toMap(permission -> permission.getName(), permission -> permission.getDefault()));
        assertThat(defaults).containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
                Map.entry("brimstone.command", PermissionDefault.TRUE),
                Map.entry("brimstone.launch", PermissionDefault.TRUE),
                Map.entry("brimstone.craft", PermissionDefault.TRUE),
                Map.entry("brimstone.language.self", PermissionDefault.TRUE),
                Map.entry("brimstone.bypass.access", PermissionDefault.FALSE),
                Map.entry("brimstone.bypass.cooldown", PermissionDefault.FALSE),
                Map.entry("brimstone.bypass.fuel", PermissionDefault.FALSE),
                Map.entry("brimstone.give", PermissionDefault.OP),
                Map.entry("brimstone.clear", PermissionDefault.OP),
                Map.entry("brimstone.config", PermissionDefault.OP),
                Map.entry("brimstone.debug", PermissionDefault.OP),
                Map.entry("brimstone.update", PermissionDefault.OP)));
        assertThat(description.getPermissions()).allSatisfy(permission ->
                assertThat(permission.getDescription()).isNotBlank());
        assertThat(defaults).doesNotContainKey("brimstone.reload");
    }
}
