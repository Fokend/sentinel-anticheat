package dev.fokend.sentinel.listener;

import dev.fokend.sentinel.config.CheckSettings;
import dev.fokend.sentinel.config.SentinelConfig;
import dev.fokend.sentinel.data.CheckType;
import dev.fokend.sentinel.data.PlayerData;
import dev.fokend.sentinel.data.PlayerDataManager;
import dev.fokend.sentinel.service.ViolationService;
import io.papermc.paper.event.packet.ClientTickEndEvent;
import java.util.Locale;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Handles the end of every client tick. Paper processes all game packets of a player in order on the
 * main thread, so everything that happened between two {@code client_tick_end} packets belongs to the
 * same client tick.
 */
public final class ClientTickListener implements Listener {

    private final PlayerDataManager players;
    private final ViolationService violations;
    private final MovementProcessor movement;
    private final Supplier<SentinelConfig> config;

    public ClientTickListener(PlayerDataManager players, ViolationService violations, MovementProcessor movement,
                              Supplier<SentinelConfig> config) {
        this.players = players;
        this.violations = violations;
        this.movement = movement;
        this.config = config;
    }

    /** Clients that never send client_tick_end (pre-1.21.2 via ViaVersion) are not tick-checked. */
    public static boolean tickAware(PlayerData d) {
        return Bukkit.getCurrentTick() - d.lastClientTickEnd <= 40;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClientTickEnd(ClientTickEndEvent event) {
        Player p = event.getPlayer();
        PlayerData d = players.get(p);
        SentinelConfig cfg = config.get();
        boolean wasAware = tickAware(d);
        d.lastClientTickEnd = Bukkit.getCurrentTick();
        d.clientTicks++;

        // ---------------------------------------------------------------- NoSwing
        if (wasAware && d.attackedThisTick) {
            if (!d.swungThisTick) {
                violations.fail(p, d, CheckType.NOSWING, 1.0, "attack without arm swing");
            } else {
                violations.pass(d, CheckType.NOSWING);
            }
        }
        d.attackedThisTick = false;
        d.swungThisTick = false;
        d.firstTargetThisTick = null;
        d.swaps.onTickEnd(p.getInventory().getHeldItemSlot());

        // ---------------------------------------------------------------- BadPackets (rotation)
        float pitch = p.getLocation().getPitch();
        if (Math.abs(pitch) > 90.001f) {
            violations.fail(p, d, CheckType.BADPACKETS, 1.0, "pitch=" + pitch);
        }

        // ---------------------------------------------------------------- Timer
        CheckSettings timer = cfg.check(CheckType.TIMER);
        double balance = d.timer.onClientTick(System.nanoTime(), timer.param("max-lag-ms"));
        if (d.timerGraceTicks > 0) {
            d.timerGraceTicks--;
            if (d.timerGraceTicks == 0) {
                d.timer.reset();
            }
        } else if (timer.enabled() && wasAware) {
            if (balance > timer.param("threshold-ms")) {
                boolean mitigate = violations.fail(p, d, CheckType.TIMER, 1.0,
                        String.format(Locale.US, "+%.0fms ahead", balance));
                d.timer.consume(balance);
                if (mitigate) {
                    violations.setback(p, d);
                }
            } else {
                violations.pass(d, CheckType.TIMER);
            }
        }

        // ---------------------------------------------------------------- Movement
        if (wasAware) {
            movement.process(p, d, cfg);
        }
    }
}
