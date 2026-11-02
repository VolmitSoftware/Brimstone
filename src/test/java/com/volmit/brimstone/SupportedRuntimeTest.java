package com.volmit.brimstone;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class SupportedRuntimeTest {
    @Test
    void acceptsPaperAndSpigotVersionsFrom261Through263OnJava25AndNewer() {
        assertThat(SupportedRuntime.supports("26.1-R0.1-SNAPSHOT", 25)).isTrue();
        assertThat(SupportedRuntime.supports("26.1.2-R0.1-SNAPSHOT", 25)).isTrue();
        assertThat(SupportedRuntime.supports("26.2", 26)).isTrue();
        assertThat(SupportedRuntime.supports("26.3.1.build.4-stable", 27)).isTrue();
    }

    @Test
    void rejectsOtherMinecraftVersionsAndOlderJava() {
        assertThat(SupportedRuntime.supports("26.4-R0.1-SNAPSHOT", 25)).isFalse();
        assertThat(SupportedRuntime.supports("1.21.11-R0.1-SNAPSHOT", 25)).isFalse();
        assertThat(SupportedRuntime.supports("26.1.2-R0.1-SNAPSHOT", 24)).isFalse();
        assertThat(SupportedRuntime.supports(null, 25)).isFalse();
        assertThatThrownBy(() -> SupportedRuntime.requireSupported("26.4", 21))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Brimstone requires Minecraft 26.1 through 26.3 on Java 25 or newer; found Minecraft 26.4 on Java 21.");
    }
}
