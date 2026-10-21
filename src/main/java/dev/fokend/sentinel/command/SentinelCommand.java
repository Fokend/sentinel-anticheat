package dev.fokend.sentinel.command;

import dev.fokend.sentinel.data.CheckType;
import dev.fokend.sentinel.data.PlayerData;
import dev.fokend.sentinel.data.PlayerDataManager;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

public final class SentinelCommand implements BasicCommand {

    private final PlayerDataManager dataManager;

    public SentinelCommand(PlayerDataManager dataManager) {
        this.dataManager = dataManager;
    }

    @Override
    public void execute(CommandSourceStack stack, String[] args) {
        CommandSender sender = stack.getSender();
        if (!sender.hasPermission("sentinel.alerts")) {
            sender.sendMessage(Component.text("Недостаточно прав.", NamedTextColor.RED));
            return;
        }

        if (args.length == 0) {
            sender.sendMessage(Component.text("Использование: /sentinel <alerts|profile> [игрок]", NamedTextColor.YELLOW));
            return;
        }

        if (args[0].equalsIgnoreCase("alerts")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(Component.text("Только для игроков.", NamedTextColor.RED));
                return;
            }
            PlayerData data = dataManager.getData(player);
            boolean nextState = !data.isAlertsEnabled();
            data.setAlertsEnabled(nextState);
            player.sendMessage(Component.text(
                    "Уведомления Sentinel: " + (nextState ? "ВКЛЮЧЕНЫ" : "ВЫКЛЮЧЕНЫ"),
                    nextState ? NamedTextColor.GREEN : NamedTextColor.RED
            ));
            return;
        }

        if (args[0].equalsIgnoreCase("profile") && args.length >= 2) {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(Component.text("Игрок не найден.", NamedTextColor.RED));
                return;
            }
            PlayerData data = dataManager.getData(target);
            sender.sendMessage(Component.text("=== Профиль " + target.getName() + " (Пинг: " + target.getPing() + " мс) ===", NamedTextColor.GOLD));
            for (CheckType type : CheckType.values()) {
                double vl = data.getViolation(type);
                if (vl > 0.0) {
                    sender.sendMessage(Component.text(
                            " - " + type.getDisplayName() + ": " + String.format(Locale.US, "%.2f", vl),
                            NamedTextColor.RED
                    ));
                }
            }
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack commandSourceStack, String[] args) {
        if (args.length <= 1) {
            return List.of("alerts", "profile");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("profile")) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        }
        return List.of();
    }
}

