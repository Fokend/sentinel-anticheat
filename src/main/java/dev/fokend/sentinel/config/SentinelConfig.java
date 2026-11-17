package dev.fokend.sentinel.config;

import dev.fokend.sentinel.data.CheckType;
import java.util.EnumMap;
import java.util.Map;

/**
 * Immutable configuration snapshot (no Bukkit types; replaced as a whole on {@code /sentinel reload}).
 *
 * @param enforce            false = monitor mode: alerts only, nothing is cancelled, set back or kicked
 * @param alertCooldownMs    minimum time between two alerts of the same check for the same player
 * @param decayIntervalTicks how often violation levels decay
 * @param decayFactor        multiplier applied to every VL on decay
 * @param velocityExtraTicks client ticks of tolerance after knockback, added on top of the player's ping
 */
public record SentinelConfig(boolean enforce, long alertCooldownMs, boolean consoleAlerts, int decayIntervalTicks,
                             double decayFactor, int velocityExtraTicks, Map<CheckType, CheckSettings> checks) {

    public CheckSettings check(CheckType type) {
        return checks.get(type);
    }

    /** Built-in defaults; they mirror config.yml (a unit test keeps both in sync). */
    public static SentinelConfig defaults() {
        Map<CheckType, CheckSettings> m = new EnumMap<>(CheckType.class);
        // enabled, buffer, bufferDecay, vl, kickVl, mitigate
        m.put(CheckType.AUTOSWAP, s(1, 0.0, 2.0, 6.0, true));
        m.put(CheckType.MULTIAURA, s(1, 0.0, 2.0, 8.0, true));
        m.put(CheckType.NOSWING, s(3, 0.5, 1.0, 10.0, true));
        m.put(CheckType.REACH, s(2, 0.25, 1.0, 0.0, true));
        m.put(CheckType.FLY, s(3, 0.1, 1.0, 20.0, true));
        m.put(CheckType.SPEED, s(4, 0.05, 1.0, 0.0, true));
        m.put(CheckType.NOSLOW, s(5, 0.1, 1.0, 0.0, true));
        m.put(CheckType.TIMER, s(1, 0.0, 1.0, 0.0, true));
        m.put(CheckType.BADPACKETS, s(1, 0.0, 2.0, 6.0, true));
        m.put(CheckType.BLOCKREACH, s(2, 0.25, 1.0, 0.0, true));
        m.put(CheckType.AIRPLACE, s(2, 0.25, 2.0, 8.0, true));
        m.put(CheckType.SCAFFOLD, s(3, 0.25, 1.0, 0.0, true));
        Map<CheckType, CheckSettings> withParams = new EnumMap<>(CheckType.class);
        m.forEach((k, v) -> withParams.put(k, new CheckSettings(v.enabled(), v.buffer(), v.bufferDecay(), v.vl(),
                v.kickVl(), v.mitigate(), Map.copyOf(k.defaultParams()))));
        return new SentinelConfig(true, 1000L, true, 600, 0.85, 4, Map.copyOf(withParams));
    }

    private static CheckSettings s(double buffer, double decay, double vl, double kickVl, boolean mitigate) {
        return new CheckSettings(true, buffer, decay, vl, kickVl, mitigate, Map.of());
    }
}
