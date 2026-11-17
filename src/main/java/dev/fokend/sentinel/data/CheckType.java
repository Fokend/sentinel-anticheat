package dev.fokend.sentinel.data;

import java.util.Locale;
import java.util.Map;

/**
 * All checks. {@link #key()} is used in config.yml and in the per-check bypass permission
 * {@code sentinel.bypass.<key>}.
 */
public enum CheckType {
    AUTOSWAP("AutoSwap", "Смена слота после удара в том же клиентском тике (auto attribute swap)",
            Map.of("penalty-seconds", 15.0)),
    MULTIAURA("MultiAura", "Удары по разным целям в одном клиентском тике", Map.of()),
    NOSWING("NoSwing", "Удар без взмаха руки", Map.of()),
    REACH("Reach", "Удар дальше дистанции взаимодействия (с компенсацией пинга)",
            Map.of("tolerance", 0.1, "hitbox-margin", 0.1, "extra-ticks", 4.0)),
    FLY("Fly", "Движение вверх или зависание вопреки гравитации", Map.of("tolerance", 0.03)),
    SPEED("Speed", "Горизонтальная скорость выше физически возможной", Map.of("tolerance", 0.025)),
    NOSLOW("NoSlow", "Нет замедления при использовании предмета", Map.of("tolerance", 0.025, "min-use-ticks", 3.0)),
    TIMER("Timer", "Клиент шлёт больше 20 тиков в секунду", Map.of("threshold-ms", 200.0, "max-lag-ms", 1000.0)),
    BADPACKETS("BadPackets", "Невозможный угол камеры", Map.of()),
    BLOCKREACH("BlockReach", "Установка блока дальше дистанции взаимодействия", Map.of("tolerance", 0.25)),
    AIRPLACE("AirPlace", "Установка блока на воздух", Map.of()),
    SCAFFOLD("Scaffold", "Блок под ноги при взгляде вперёд или вверх", Map.of("max-pitch", 30.0));

    private final String displayName;
    private final String description;
    private final Map<String, Double> defaultParams;

    CheckType(String displayName, String description, Map<String, Double> defaultParams) {
        this.displayName = displayName;
        this.description = description;
        this.defaultParams = defaultParams;
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }

    public Map<String, Double> defaultParams() {
        return defaultParams;
    }
}
