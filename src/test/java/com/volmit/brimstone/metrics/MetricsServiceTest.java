package com.volmit.brimstone.metrics;

import com.volmit.brimstone.Brimstone;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

final class MetricsServiceTest {
    @Test
    void reloadsDoNotDuplicateCollectorsAndDisableStopsThem() {
        Brimstone plugin = mock(Brimstone.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("BrimstoneMetricsTest"));
        try (MockedConstruction<Metrics> construction = mockConstruction(Metrics.class,
                (metrics, context) -> {
                    assertEquals(plugin, context.arguments().get(0));
                    assertEquals(34582, context.arguments().get(1));
                })) {
            MetricsService service = new MetricsService(plugin);
            service.reconfigure(false);
            assertTrue(construction.constructed().isEmpty());
            service.reconfigure(true);
            service.reconfigure(true);
            assertEquals(1, construction.constructed().size());
            assertTrue(service.initialized());
            service.reconfigure(false);
            verify(construction.constructed().get(0)).shutdown();
            assertFalse(service.initialized());
            service.reconfigure(true);
            assertEquals(2, construction.constructed().size());
            service.close();
            service.close();
            verify(construction.constructed().get(1)).shutdown();
            service.reconfigure(true);
            assertEquals(2, construction.constructed().size());
            assertFalse(service.initialized());
        }
    }
}
