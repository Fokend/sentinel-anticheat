package dev.fokend.sentinel.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fokend.sentinel.data.CheckType;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class ConfigLoaderTest {

    @Test
    void bundledConfigEqualsDefaults() throws Exception {
        YamlConfiguration y;
        try (var in = getClass().getResourceAsStream("/config.yml")) {
            y = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        ConfigLoader loader = new ConfigLoader(y);
        SentinelConfig cfg = loader.load();
        assertTrue(loader.warnings().isEmpty(), loader.warnings().toString());
        assertEquals(SentinelConfig.defaults(), cfg);
    }

    @Test
    void invalidValuesFallBack() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("mode", "chaos");
        y.set("checks.fly.tolerance", "abc");
        y.set("checks.speed.buffer", 0);
        y.set("checks.reach.enabled", "yes");
        y.set("checks.unknown.enabled", true);
        y.set("violation-decay.factor", 5);
        ConfigLoader loader = new ConfigLoader(y);
        SentinelConfig cfg = loader.load();
        assertTrue(cfg.enforce());
        assertEquals(0.03, cfg.check(CheckType.FLY).param("tolerance"));
        assertEquals(1, cfg.check(CheckType.SPEED).buffer());
        assertTrue(cfg.check(CheckType.REACH).enabled());
        assertEquals(1.0, cfg.decayFactor());
        assertEquals(6, loader.warnings().size(), loader.warnings().toString());
    }

    @Test
    void monitorMode() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("mode", "monitor");
        y.set("checks.autoswap.enabled", false);
        SentinelConfig cfg = new ConfigLoader(y).load();
        assertFalse(cfg.enforce());
        assertFalse(cfg.check(CheckType.AUTOSWAP).enabled());
    }
}
