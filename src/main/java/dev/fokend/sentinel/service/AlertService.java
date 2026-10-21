package dev.fokend.sentinel.service;

import dev.fokend.sentinel.data.CheckType;
import dev.fokend.sentinel.data.PlayerData;
import dev.fokend.sentinel.data.PlayerDataManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Locale;

public final class AlertService {

    private final PlayerDataManager dataManager;

    public AlertService(PlayerDataManager dataManager) {
        this.dataManager = dataManager;
    }

    public void flag(Player suspect, CheckType checkType, double vlWeight, String debugInfo, boolean setback) {
        PlayerData data = dataManager.getData(suspect);
        double totalVl = data.addViolation(checkType, vlWeight);

        if (setback) {
            Location safe = data.getLastSafeGroundLocation();
            if (safe != null && safe.getWorld().equals(suspect.getWorld())) {
                suspect.teleport(safe);
            }
        }

        Component alertMessage = Component.text()
                .append(Component.text("[Sentinel] ", NamedTextColor.RED, TextDecoration.BOLD))
                .append(Component.text(suspect.getName(), NamedTextColor.WHITE))
                .append(Component.text(" провалил ", NamedTextColor.GRAY))
                .append(Component.text(checkType.getDisplayName(), NamedTextColor.GOLD))
                .append(Component.text(" [VL: " + String.format(Locale.US, "%.1f", totalVl) + "] ", NamedTextColor.RED))
                .append(Component.text("(" + debugInfo + ")", NamedTextColor.DARK_GRAY))
                .hoverEvent(HoverEvent.showText(Component.text()
                        .append(Component.text("Описание: ", NamedTextColor.GRAY))
                        .append(Component.text(checkType.getDescription(), NamedTextColor.YELLOW))
                        .append(Component.newline())
                        .append(Component.text("Пинг: ", NamedTextColor.GRAY))
                        .append(Component.text(suspect.getPing() + " мс", NamedTextColor.GREEN))
                        .append(Component.newline())
                        .append(Component.text("Нажмите для телепортации", NamedTextColor.AQUA))
                        .build()))
                .clickEvent(ClickEvent.runCommand("/tp " + suspect.getName()))
                .build();

        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.hasPermission("sentinel.alerts") && dataManager.getData(online).isAlertsEnabled()) {
                online.sendMessage(alertMessage);
            }
        }

        Bukkit.getConsoleSender().sendMessage(alertMessage);

        if (totalVl >= checkType.getPunishThreshold()) {
            data.decayViolations(0.0);
            suspect.kick(Component.text()
                    .append(Component.text("Sentinel AntiCheat\n\n", NamedTextColor.RED, TextDecoration.BOLD))
                    .append(Component.text("Обнаружена подозрительная активность: ", NamedTextColor.GRAY))
                    .append(Component.text(checkType.getDisplayName(), NamedTextColor.GOLD))
                    .build());
        }
    }
}

