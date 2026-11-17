package dev.fokend.sentinel.config;

import dev.fokend.sentinel.data.CheckType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;

/** Reads config.yml; invalid values fall back to defaults with a warning instead of breaking the plugin. */
public final class ConfigLoader {

    private final ConfigurationSection root;
    private final List<String> warnings = new ArrayList<>();

    public ConfigLoader(ConfigurationSection root) {
        this.root = root;
    }

    public List<String> warnings() {
        return Collections.unmodifiableList(warnings);
    }

    public SentinelConfig load() {
        SentinelConfig d = SentinelConfig.defaults();
        String mode = root.getString("mode", "enforce");
        boolean enforce;
        if ("enforce".equalsIgnoreCase(mode)) {
            enforce = true;
        } else if ("monitor".equalsIgnoreCase(mode)) {
            enforce = false;
        } else {
            warnings.add("mode: expected 'enforce' or 'monitor', got '" + mode + "', using enforce");
            enforce = true;
        }
        Map<CheckType, CheckSettings> checks = new EnumMap<>(CheckType.class);
        for (CheckType type : CheckType.values()) {
            CheckSettings def = d.check(type);
            String p = "checks." + type.key() + ".";
            Map<String, Double> params = new HashMap<>();
            for (Map.Entry<String, Double> e : def.params().entrySet()) {
                params.put(e.getKey(), num(p + e.getKey(), e.getValue(), 0, 1_000_000));
            }
            checks.put(type, new CheckSettings(
                    bool(p + "enabled", def.enabled()),
                    num(p + "buffer", def.buffer(), 1, 1000),
                    num(p + "buffer-decay", def.bufferDecay(), 0, 1000),
                    num(p + "vl", def.vl(), 0, 1000),
                    num(p + "kick-vl", def.kickVl(), 0, 100_000),
                    bool(p + "mitigate", def.mitigate()),
                    Map.copyOf(params)));
        }
        ConfigurationSection checkSection = root.getConfigurationSection("checks");
        if (checkSection != null) {
            for (String key : checkSection.getKeys(false)) {
                boolean known = false;
                for (CheckType t : CheckType.values()) {
                    known |= t.key().equals(key);
                }
                if (!known) {
                    warnings.add("checks." + key + ": unknown check, ignored");
                }
            }
        }
        return new SentinelConfig(enforce,
                (long) num("alerts.cooldown-ms", d.alertCooldownMs(), 0, 600_000),
                bool("alerts.console", d.consoleAlerts()),
                (int) Math.round(num("violation-decay.interval-ticks", d.decayIntervalTicks(), 20, 72_000)),
                num("violation-decay.factor", d.decayFactor(), 0, 1),
                (int) Math.round(num("velocity-extra-ticks", d.velocityExtraTicks(), 0, 40)),
                Map.copyOf(checks));
    }

    private boolean bool(String path, boolean def) {
        if (!root.contains(path)) {
            return def;
        }
        if (!root.isBoolean(path)) {
            warnings.add(path + ": expected true/false, using " + def);
            return def;
        }
        return root.getBoolean(path);
    }

    private double num(String path, double def, double min, double max) {
        if (!root.contains(path)) {
            return def;
        }
        Object raw = root.get(path);
        if (!(raw instanceof Number n) || !Double.isFinite(n.doubleValue())) {
            warnings.add(path + ": expected a number, got '" + raw + "', using " + def);
            return def;
        }
        double v = n.doubleValue();
        if (v < min || v > max) {
            double c = Math.max(min, Math.min(max, v));
            warnings.add(path + ": " + v + " outside [" + min + ", " + max + "], clamped to " + c);
            return c;
        }
        return v;
    }
}
