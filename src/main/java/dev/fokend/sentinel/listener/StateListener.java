package dev.fokend.sentinel.listener;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import com.destroystokyo.paper.event.server.ServerTickStartEvent;
import dev.fokend.sentinel.config.SentinelConfig;
import dev.fokend.sentinel.data.PlayerData;
import dev.fokend.sentinel.data.PlayerDataManager;
import io.papermc.paper.event.entity.EntityKnockbackEvent;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerRiptideEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

/**
 * Lifecycle and "grace" events: data creation/removal, knockback tolerance, teleports, riptide, elytra,
 * pistons, server lag spikes and the position history used by the reach check.
 */
public final class StateListener implements Listener {

    private static final long LAG_SPIKE_NANOS = 150_000_000L;

    private final PlayerDataManager players;
    private final Supplier<SentinelConfig> config;
    private final NamespacedKey alertsKey;
    private long lastTickStart = -1;

    public StateListener(PlayerDataManager players, Supplier<SentinelConfig> config, NamespacedKey alertsKey) {
        this.players = players;
        this.config = config;
        this.alertsKey = alertsKey;
    }

    // ------------------------------------------------------------------ lifecycle

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        PlayerData d = players.get(p);
        Byte stored = p.getPersistentDataContainer().get(alertsKey, PersistentDataType.BYTE);
        d.alerts = stored == null || stored != 0;
        d.resetMovement(10);
        d.timerGraceTicks = 100;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        players.remove(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ knockback tolerance

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent event) {
        addVelocity(event.getPlayer(), event.getVelocity());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKnockback(EntityKnockbackEvent event) {
        if (event.getEntity() instanceof Player p) {
            addVelocity(p, event.getKnockback());
        }
    }

    private void addVelocity(Player p, Vector v) {
        PlayerData d = players.get(p);
        double xz = Math.hypot(v.getX(), v.getZ()) + d.lastDeltaXZ;
        double y = Math.max(0, v.getY()) + Math.max(0, d.lastDeltaY);
        int window = (int) Math.ceil(p.getPing() / 50.0) + config.get().velocityExtraTicks();
        if (d.velocityTicks > 0) {
            d.velocityXZ = Math.max(d.velocityXZ, xz);
            d.velocityY = Math.max(d.velocityY, y);
        } else {
            d.velocityXZ = xz;
            d.velocityY = y;
        }
        d.velocityTicks = Math.max(d.velocityTicks, Math.min(60, window));
    }

    // ------------------------------------------------------------------ state changes

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        PlayerData d = players.get(event.getPlayer());
        d.resetMovement(3);
        Location to = event.getTo();
        if (to.getWorld() != null) {
            d.hasSafe = true;
            d.safeWorld = to.getWorld().getUID();
            d.safeX = to.getX();
            d.safeY = to.getY();
            d.safeZ = to.getZ();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        PlayerData d = players.get(event.getPlayer());
        d.resetMovement(10);
        d.hasSafe = false;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        PlayerData d = players.get(event.getPlayer());
        d.resetMovement(10);
        d.hasSafe = false;
        d.history.clear();
        d.timerGraceTicks = Math.max(d.timerGraceTicks, 40);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameMode(PlayerGameModeChangeEvent event) {
        players.get(event.getPlayer()).resetMovement(10);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFlightToggle(PlayerToggleFlightEvent event) {
        players.get(event.getPlayer()).resetMovement(10);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRiptide(PlayerRiptideEvent event) {
        players.get(event.getPlayer()).resetMovement(20);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGlide(EntityToggleGlideEvent event) {
        if (event.getEntity() instanceof Player p) {
            players.get(p).resetMovement(10);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVehicleExit(VehicleExitEvent event) {
        if (event.getExited() instanceof Player p) {
            players.get(p).resetMovement(10);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        pistonNearby(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        pistonNearby(event.getBlock());
    }

    private void pistonNearby(Block piston) {
        Location center = piston.getLocation().add(0.5, 0.5, 0.5);
        for (Player p : piston.getWorld().getNearbyPlayers(center, 4.0)) {
            players.get(p).resetMovement(10);
        }
    }

    // ------------------------------------------------------------------ server ticks

    /** A server lag spike delays packet processing; timer balance would be wrong, so it is reset. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onServerTickStart(ServerTickStartEvent event) {
        long now = System.nanoTime();
        if (lastTickStart > 0 && now - lastTickStart > LAG_SPIKE_NANOS) {
            for (PlayerData d : players.all()) {
                d.timer.reset();
                d.timerGraceTicks = Math.max(d.timerGraceTicks, 40);
            }
        }
        lastTickStart = now;
    }

    /** Records every player's hitbox for lag-compensated reach checks (stored as primitives in a ring buffer). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onServerTickEnd(ServerTickEndEvent event) {
        int tick = Bukkit.getCurrentTick();
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerData d = players.getIfPresent(p.getUniqueId());
            if (d != null) {
                BoundingBox b = p.getBoundingBox();
                d.history.record(tick, b.getMinX(), b.getMinY(), b.getMinZ(), b.getMaxX(), b.getMaxY(), b.getMaxZ());
            }
        }
    }
}
