package dev.fokend.sentinel;

import dev.fokend.sentinel.command.SentinelCommand;
import dev.fokend.sentinel.data.PlayerData;
import dev.fokend.sentinel.data.PlayerDataManager;
import dev.fokend.sentinel.listener.CombatCheckListener;
import dev.fokend.sentinel.listener.MovementCheckListener;
import dev.fokend.sentinel.listener.WorldCheckListener;
import dev.fokend.sentinel.service.AlertService;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.Bukkit;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class SentinelPlugin extends JavaPlugin {

    private PlayerDataManager dataManager;
    private BukkitTask decayTask;

    @Override
    public void onEnable() {
        this.dataManager = new PlayerDataManager();
        AlertService alertService = new AlertService(this.dataManager);

        PluginManager pm = Bukkit.getPluginManager();
        pm.registerEvents(new CombatCheckListener(this.dataManager, alertService), this);
        pm.registerEvents(new MovementCheckListener(this.dataManager, alertService), this);
        pm.registerEvents(new WorldCheckListener(this.dataManager, alertService), this);

        // Регистрация Brigadier-команды /sentinel через современный Paper Lifecycle API
        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, commands -> {
            commands.registrar().register("sentinel", "Управление античитом Sentinel", new SentinelCommand(this.dataManager));
        });

        // Задача плавного снижения накопленных VL каждые 30 секунд (600 тиков), чтобы случайный лаг не привел к кику спустя час игры
        this.decayTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (PlayerData data : this.dataManager.getAllData()) {
                data.decayViolations(0.85);
            }
        }, 600L, 600L);

        getLogger().info("Sentinel AntiCheat успешно запущен.");
    }

    @Override
    public void onDisable() {
        if (this.decayTask != null) {
            this.decayTask.cancel();
        }
        if (this.dataManager != null) {
            this.dataManager.clearAll();
        }
    }
}

