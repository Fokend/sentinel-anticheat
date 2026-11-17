package dev.fokend.sentinel.listener;

import dev.fokend.sentinel.config.CheckSettings;
import dev.fokend.sentinel.config.SentinelConfig;
import dev.fokend.sentinel.data.CheckType;
import dev.fokend.sentinel.data.PlayerData;
import dev.fokend.sentinel.data.PlayerDataManager;
import dev.fokend.sentinel.engine.PositionHistory;
import dev.fokend.sentinel.engine.SwapTracker;
import dev.fokend.sentinel.service.ViolationService;
import io.papermc.paper.event.player.PlayerArmSwingEvent;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import java.util.Locale;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.util.BoundingBox;

/** AutoSwap, MultiAura, NoSwing bookkeeping, Reach and attack-time BadPackets. */
public final class CombatListener implements Listener {

    private final PlayerDataManager players;
    private final ViolationService violations;
    private final Supplier<SentinelConfig> config;

    public CombatListener(PlayerDataManager players, ViolationService violations, Supplier<SentinelConfig> config) {
        this.players = players;
        this.violations = violations;
        this.config = config;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwap(PlayerItemHeldEvent event) {
        Player p = event.getPlayer();
        PlayerData d = players.get(p);
        if (!ClientTickListener.tickAware(d)) {
            return;
        }
        SwapTracker.Result r = d.swaps.onSwap(event.getPreviousSlot(), event.getNewSlot());
        if (r == SwapTracker.Result.POST_ATTACK_SWAP) {
            String detail = "slot " + (event.getPreviousSlot() + 1) + "→" + (event.getNewSlot() + 1) + " after hit"
                    + (d.swaps.swappedBack() ? ", back to start slot" : "");
            if (violations.fail(p, d, CheckType.AUTOSWAP, 1.0, detail)) {
                long penalty = (long) (config.get().check(CheckType.AUTOSWAP).param("penalty-seconds") * 1000);
                d.autoSwapPenaltyUntil = System.currentTimeMillis() + penalty;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwing(PlayerArmSwingEvent event) {
        players.get(event.getPlayer()).swungThisTick = true;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAttack(PrePlayerAttackEntityEvent event) {
        if (!event.willAttack()) {
            return;
        }
        Player p = event.getPlayer();
        PlayerData d = players.get(p);
        Entity target = event.getAttacked();

        // ---------------------------------------------------------------- BadPackets
        float pitch = p.getLocation().getPitch();
        if (Math.abs(pitch) > 90.001f) {
            if (violations.fail(p, d, CheckType.BADPACKETS, 1.0, "attack pitch=" + pitch)) {
                event.setCancelled(true);
                return;
            }
        }

        if (ClientTickListener.tickAware(d)) {
            // ------------------------------------------------------------ AutoSwap mitigation
            SwapTracker.Result r = d.swaps.onAttack(p.getInventory().getHeldItemSlot());
            if (r == SwapTracker.Result.SWAP_BEFORE_ATTACK && System.currentTimeMillis() < d.autoSwapPenaltyUntil
                    && config.get().enforce()) {
                event.setCancelled(true); // a flagged macro user gains nothing from swap-hits for a while
            }
            // ------------------------------------------------------------ MultiAura
            if (d.attackedThisTick && d.firstTargetThisTick != null
                    && !d.firstTargetThisTick.equals(target.getUniqueId())) {
                if (violations.fail(p, d, CheckType.MULTIAURA, 1.0, "2 targets in 1 client tick")) {
                    event.setCancelled(true);
                }
            } else if (!d.attackedThisTick) {
                d.firstTargetThisTick = target.getUniqueId();
                violations.pass(d, CheckType.MULTIAURA);
            }
            d.attackedThisTick = true;
        }

        // ---------------------------------------------------------------- Reach
        CheckSettings reach = config.get().check(CheckType.REACH);
        if (reach.enabled() && !event.isCancelled()) {
            double range = MovementProcessor.attribute(p, Attribute.ENTITY_INTERACTION_RANGE, 3.0);
            double distance = distance(p, target, reach);
            if (distance > range + reach.param("tolerance")) {
                if (violations.fail(p, d, CheckType.REACH, 1.0,
                        String.format(Locale.US, "%.2f > %.2f", distance, range))) {
                    event.setCancelled(true);
                }
            } else {
                violations.pass(d, CheckType.REACH);
            }
        }
    }

    /**
     * Distance from the attacker's eyes to the closest position of the target's hitbox that the attacker
     * could have seen, given his ping (players: recorded history; other entities: velocity margin).
     */
    private double distance(Player attacker, Entity target, CheckSettings reach) {
        Location eye = attacker.getEyeLocation();
        double ex = eye.getX();
        double ey = eye.getY();
        double ez = eye.getZ();
        double margin = reach.param("hitbox-margin");
        int lagTicks = (int) Math.ceil(attacker.getPing() / 50.0) + (int) reach.param("extra-ticks");
        BoundingBox now = target.getBoundingBox();
        if (target instanceof Player victim) {
            double current = PositionHistory.distanceToBox(ex, ey, ez, now.getMinX() - margin, now.getMinY() - margin,
                    now.getMinZ() - margin, now.getMaxX() + margin, now.getMaxY() + margin, now.getMaxZ() + margin);
            PlayerData vd = players.getIfPresent(victim.getUniqueId());
            if (vd == null) {
                return current;
            }
            int tick = Bukkit.getCurrentTick();
            int back = Math.min(lagTicks, PlayerData.HISTORY_TICKS - 1);
            double past = vd.history.minDistance(ex, ey, ez, tick - back, tick, margin);
            return Double.isNaN(past) ? current : Math.min(current, past);
        }
        double speed = target.getVelocity().length();
        double m = margin + Math.min(3.0, speed * lagTicks);
        return PositionHistory.distanceToBox(ex, ey, ez, now.getMinX() - m, now.getMinY() - m, now.getMinZ() - m,
                now.getMaxX() + m, now.getMaxY() + m, now.getMaxZ() + m);
    }
}
