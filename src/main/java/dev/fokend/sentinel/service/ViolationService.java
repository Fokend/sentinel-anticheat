package dev.fokend.sentinel.service;

import dev.fokend.sentinel.config.CheckSettings;
import dev.fokend.sentinel.config.SentinelConfig;
import dev.fokend.sentinel.data.CheckType;
import dev.fokend.sentinel.data.PlayerData;
import dev.fokend.sentinel.data.PlayerDataManager;
import java.util.Locale;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Single place where a failed check turns into consequences: bypass permissions, buffers, violation
 * levels, alerts (rate limited), mitigation and kicks. Main thread only.
 */
public final class ViolationService {

    private final Plugin plugin;
    private final PlayerDataManager players;
    private final Supplier<SentinelConfig> config;

    public ViolationService(Plugin plugin, PlayerDataManager players, Supplier<SentinelConfig> config) {
        this.plugin = plugin;
        this.players = players;
        this.config = config;
    }

    public boolean enabled(CheckType type) {
        return config.get().check(type).enabled();
    }

    public boolean exempt(Player player, CheckType type) {
        return player.hasPermission("sentinel.bypass") || player.hasPermission("sentinel.bypass." + type.key());
    }

    /** A passing event: let the buffer drain. */
    public void pass(PlayerData data, CheckType type) {
        data.decayBuffer(type, config.get().check(type).bufferDecay());
    }

    /**
     * A failing event. Adds to the buffer; once the buffer threshold is reached the player is flagged.
     *
     * @param bufferIncrement how suspicious this single event is (1 = normal)
     * @return true if the caller should mitigate (cancel the action / set back)
     */
    public boolean fail(Player player, PlayerData data, CheckType type, double bufferIncrement, String detail) {
        SentinelConfig cfg = config.get();
        CheckSettings s = cfg.check(type);
        if (!s.enabled() || exempt(player, type)) {
            return false;
        }
        if (data.addBuffer(type, bufferIncrement) < s.buffer()) {
            return false;
        }
        double vl = data.addViolation(type, s.vl());
        alert(player, data, type, vl, detail, cfg);
        if (!cfg.enforce()) {
            return false;
        }
        if (s.kickVl() > 0 && vl >= s.kickVl() && !data.kickPending) {
            data.kickPending = true;
            String name = type.displayName();
            // Kick on the next tick: never disconnect a player from inside packet handling.
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player p = Bukkit.getPlayer(data.uuid());
                if (p != null) {
                    plugin.getLogger().info("Kicking " + p.getName() + " for " + name + " (VL " + format(vl) + ")");
                    p.kick(Component.text()
                            .append(Component.text("Sentinel AntiCheat\n\n", NamedTextColor.RED, TextDecoration.BOLD))
                            .append(Component.text("Обнаружена подозрительная активность: ", NamedTextColor.GRAY))
                            .append(Component.text(name, NamedTextColor.GOLD))
                            .build());
                }
            });
        }
        return s.mitigate();
    }

    /** Teleport the player back to the last position that passed every movement check. */
    public void setback(Player player, PlayerData data) {
        if (!data.hasSafe) {
            return;
        }
        World world = Bukkit.getWorld(data.safeWorld);
        if (world == null || !world.equals(player.getWorld())) {
            return;
        }
        Location target = new Location(world, data.safeX, data.safeY, data.safeZ,
                player.getLocation().getYaw(), player.getLocation().getPitch());
        player.teleport(target);
        data.resetMovement(2);
    }

    private void alert(Player suspect, PlayerData data, CheckType type, double vl, String detail, SentinelConfig cfg) {
        long now = System.currentTimeMillis();
        if (now - data.lastAlert(type) < cfg.alertCooldownMs()) {
            return;
        }
        data.lastAlert(type, now);
        Component message = Component.text()
                .append(Component.text("[Sentinel] ", NamedTextColor.RED, TextDecoration.BOLD))
                .append(Component.text(suspect.getName(), NamedTextColor.WHITE))
                .append(Component.text(" → ", NamedTextColor.DARK_GRAY))
                .append(Component.text(type.displayName(), NamedTextColor.GOLD))
                .append(Component.text(" [VL " + format(vl) + "] ", NamedTextColor.RED))
                .append(Component.text("(" + detail + ")", NamedTextColor.DARK_GRAY))
                .hoverEvent(HoverEvent.showText(Component.text()
                        .append(Component.text(type.description(), NamedTextColor.YELLOW))
                        .append(Component.newline())
                        .append(Component.text("Пинг: " + suspect.getPing() + " мс", NamedTextColor.GRAY))
                        .append(Component.newline())
                        .append(Component.text("Режим: " + (cfg.enforce() ? "enforce" : "monitor"), NamedTextColor.GRAY))
                        .append(Component.newline())
                        .append(Component.text("Клик — телепорт к игроку", NamedTextColor.AQUA))
                        .build()))
                .clickEvent(ClickEvent.runCommand("/tp " + suspect.getName()))
                .build();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.hasPermission("sentinel.alerts")) {
                PlayerData d = players.getIfPresent(online.getUniqueId());
                if (d == null || d.alerts) {
                    online.sendMessage(message);
                }
            }
        }
        if (cfg.consoleAlerts()) {
            plugin.getLogger().info(suspect.getName() + " failed " + type.displayName() + " [VL " + format(vl) + "] ("
                    + detail + ")");
        }
    }

    public static String format(double v) {
        return String.format(Locale.US, "%.2f", v);
    }
}
