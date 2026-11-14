package top.xuanjian.map.core.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GisConfigTest {

    @Test
    void defaultsAndFileCreated(@TempDir Path dir) {
        GisConfig config = new GisConfig(dir);
        assertEquals("https://xuanjian.top", config.apiBase());
        assertEquals("yl", config.serverKey());
        assertTrue(config.captureEnabled());
        assertTrue(config.uploadEnabled());
        assertTrue(config.hudEnabled());
        assertEquals(60, config.uploadRatePerMinute());
        assertEquals(1, config.sampleStep());
        assertTrue(Files.exists(config.file()), "首次启动应生成配置文件");
    }

    @Test
    void saveLoadRoundtrip(@TempDir Path dir) {
        GisConfig config = new GisConfig(dir);
        config.apiBase("https://dev.xuanjian.top");
        config.serverKey("test");
        config.uploadEnabled(false);
        config.captureRadiusChunks(4);
        config.uploadRatePerMinute(30);
        config.hudExpanded(false);
        config.layerRoads(false);
        config.debug(true);
        config.save();

        GisConfig reloaded = new GisConfig(dir);
        assertEquals("https://dev.xuanjian.top", reloaded.apiBase());
        assertEquals("test", reloaded.serverKey());
        assertFalse(reloaded.uploadEnabled());
        assertEquals(4, reloaded.captureRadiusChunks());
        assertEquals(30, reloaded.uploadRatePerMinute());
        assertFalse(reloaded.hudExpanded());
        assertFalse(reloaded.layerRoads());
        assertTrue(reloaded.debug());
    }

    @Test
    void valuesAreClamped(@TempDir Path dir) {
        GisConfig config = new GisConfig(dir);
        config.captureIntervalTicks(1);
        assertEquals(20, config.captureIntervalTicks());
        config.captureIntervalTicks(999999);
        assertEquals(12000, config.captureIntervalTicks());
        config.uploadBatchChunks(1000);
        assertEquals(64, config.uploadBatchChunks());
        config.uploadRatePerMinute(0);
        assertEquals(1, config.uploadRatePerMinute());
        config.sampleStep(0);
        assertEquals(1, config.sampleStep());
        config.sampleStep(100);
        assertEquals(32, config.sampleStep());
    }

    @Test
    void resetDefaults(@TempDir Path dir) {
        GisConfig config = new GisConfig(dir);
        config.apiBase("http://localhost:8080");
        config.uploadEnabled(false);
        config.resetDefaults();
        assertEquals("https://xuanjian.top", config.apiBase());
        assertTrue(config.uploadEnabled());
    }

    @Test
    void brokenConfigFallsBackToDefaults(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(GisConfig.FILE_NAME);
        Files.writeString(file, "api.base=\ncapture.intervalTicks=not-a-number\nupload.ratePerMinute=-5\n");
        GisConfig config = new GisConfig(dir);
        assertEquals("https://xuanjian.top", config.apiBase());
        assertEquals(GisConfig.DEFAULT_CAPTURE_INTERVAL_TICKS, config.captureIntervalTicks());
        assertEquals(1, config.uploadRatePerMinute());
    }

    @Test
    void contractConstantIsOne(@TempDir Path dir) {
        assertEquals(1, GisConfig.GIS_CONTRACT);
    }
}
