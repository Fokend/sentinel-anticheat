package dev.fokend.sentinel;

import dev.fokend.sentinel.command.SentinelCommand;
import dev.fokend.sentinel.config.ConfigLoader;
import dev.fokend.sentinel.config.SentinelConfig;
import dev.fokend.sentinel.data.PlayerData;
import dev.fokend.sentinel.data.PlayerDataManager;
import dev.fokend.sentinel.listener.BuildListener;
import dev.fokend.sentinel.listener.ClientTickListener;
import dev.fokend.sentinel.listener.CombatListener;
import dev.fokend.sentinel.listener.MovementProcessor;
import dev.fokend.sentinel.listener.StateListener;
import dev.fokend.sentinel.service.ViolationService;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** SentinelAC 2 entry point. No static state; everything is released in {@link #onDisable()}. */
public final class SentinelPlugin extends JavaPlugin {

    private volatile SentinelConfig config = SentinelConfig.defaults();
    private PlayerDataManager players;
    private NamespacedKey alertsKey;
    private BukkitTask decayTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfiguration();
        players = new PlayerDataManager();
        alertsKey = new NamespacedKey(this, "alerts");
        ViolationService violations = new ViolationService(this, players, this::cfg);

        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(new StateListener(players, this::cfg, alertsKey), this);
        pm.registerEvents(new ClientTickListener(players, violations, new MovementProcessor(violations), this::cfg), this);
        pm.registerEvents(new CombatListener(players, violations, this::cfg), this);
        pm.registerEvents(new BuildListener(players, violations, this::cfg), this);

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, commands ->
                commands.registrar().register("sentinel", "Управление античитом Sentinel", new SentinelCommand(this)));

        for (Player p : getServer().getOnlinePlayers()) {
            players.get(p).resetMovement(10);
        }
        startDecayTask();
        getLogger().info("SentinelAC enabled, mode: " + (config.enforce() ? "enforce" : "monitor"));
    }

    private int loadConfiguration() {
        reloadConfig();
        ConfigLoader loader = new ConfigLoader(getConfig());
        config = loader.load();
        loader.warnings().forEach(w -> getLogger().warning("config.yml " + w));
        return loader.warnings().size();
    }

    private void startDecayTask() {
        if (decayTask != null) {
            decayTask.cancel();
        }
        int interval = config.decayIntervalTicks();
        decayTask = getServer().getScheduler().runTaskTimer(this, () -> {
            double factor = config.decayFactor();
            for (PlayerData d : players.all()) {
                d.decayViolations(factor);
                d.kickPending = false;
            }
        }, interval, interval);
    }

    /** Applies config.yml at runtime. Listeners read the current config on every event. */
    public int reloadSentinel() {
        int warnings = loadConfiguration();
        startDecayTask();
        return warnings;
    }

    @Override
    public void onDisable() {
        if (decayTask != null) {
            decayTask.cancel();
            decayTask = null;
        }
        HandlerList.unregisterAll(this);
        if (players != null) {
            players.clear();
        }
    }

    public SentinelConfig cfg() {
        return config;
    }

    public PlayerDataManager players() {
        return players;
    }

    public NamespacedKey alertsKey() {
        return alertsKey;
    }
}
