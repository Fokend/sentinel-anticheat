package dev.fokend.sentinel.command;

import dev.fokend.sentinel.SentinelPlugin;
import dev.fokend.sentinel.data.CheckType;
import dev.fokend.sentinel.data.PlayerData;
import dev.fokend.sentinel.service.ViolationService;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

/** {@code /sentinel <alerts|profile|reload>}. */
public final class SentinelCommand implements BasicCommand {

    private final SentinelPlugin plugin;

    public SentinelCommand(SentinelPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSourceStack stack, String[] args) {
        CommandSender sender = stack.getSender();
        if (!sender.hasPermission("sentinel.alerts")) {
            sender.sendMessage(Component.text("Недостаточно прав.", NamedTextColor.RED));
            return;
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "alerts" -> alerts(sender);
            case "profile" -> profile(sender, args);
            case "reload" -> reload(sender);
            default -> sender.sendMessage(Component.text(
                    "Использование: /sentinel <alerts|profile <игрок>|reload>", NamedTextColor.YELLOW));
        }
    }

    private void alerts(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Только для игроков.", NamedTextColor.RED));
            return;
        }
        PlayerData d = plugin.players().get(player);
        d.alerts = !d.alerts;
        player.getPersistentDataContainer().set(plugin.alertsKey(), PersistentDataType.BYTE, (byte) (d.alerts ? 1 : 0));
        player.sendMessage(Component.text("Уведомления Sentinel: " + (d.alerts ? "ВКЛЮЧЕНЫ" : "ВЫКЛЮЧЕНЫ"),
                d.alerts ? NamedTextColor.GREEN : NamedTextColor.RED));
    }

    private void profile(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("Использование: /sentinel profile <игрок>", NamedTextColor.YELLOW));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(Component.text("Игрок не найден.", NamedTextColor.RED));
            return;
        }
        PlayerData d = plugin.players().get(target);
        boolean aware = Bukkit.getCurrentTick() - d.lastClientTickEnd <= 40;
        sender.sendMessage(Component.text("=== " + target.getName() + " (пинг " + target.getPing() + " мс, "
                + (aware ? "полная проверка" : "клиент без client_tick_end: проверки по тикам выключены") + ") ===",
                NamedTextColor.GOLD));
        boolean any = false;
        for (CheckType type : CheckType.values()) {
            double vl = d.violation(type);
            if (vl > 0) {
                any = true;
                sender.sendMessage(Component.text(" - " + type.displayName() + ": VL " + ViolationService.format(vl),
                        NamedTextColor.RED));
            }
        }
        if (!any) {
            sender.sendMessage(Component.text(" Нарушений нет.", NamedTextColor.GREEN));
        }
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission("sentinel.admin")) {
            sender.sendMessage(Component.text("Недостаточно прав.", NamedTextColor.RED));
            return;
        }
        int warnings = plugin.reloadSentinel();
        sender.sendMessage(Component.text("Конфигурация перезагружена. Предупреждений: " + warnings
                + ". Режим: " + (plugin.cfg().enforce() ? "enforce" : "monitor"), NamedTextColor.GREEN));
    }

    @Override
    public Collection<String> suggest(CommandSourceStack stack, String[] args) {
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (String s : List.of("alerts", "profile", "reload")) {
                if (s.startsWith(prefix)) {
                    out.add(s);
                }
            }
            return out;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("profile")) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        }
        return List.of();
    }

    @Override
    public String permission() {
        return "sentinel.alerts";
    }
}
