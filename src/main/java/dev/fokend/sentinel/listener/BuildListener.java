package dev.fokend.sentinel.listener;

import dev.fokend.sentinel.config.CheckSettings;
import dev.fokend.sentinel.config.SentinelConfig;
import dev.fokend.sentinel.data.CheckType;
import dev.fokend.sentinel.data.PlayerData;
import dev.fokend.sentinel.data.PlayerDataManager;
import dev.fokend.sentinel.engine.PositionHistory;
import dev.fokend.sentinel.service.ViolationService;
import java.util.Locale;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;

/** BlockReach, AirPlace and Scaffold. */
public final class BuildListener implements Listener {

    private final PlayerDataManager players;
    private final ViolationService violations;
    private final Supplier<SentinelConfig> config;

    public BuildListener(PlayerDataManager players, ViolationService violations, Supplier<SentinelConfig> config) {
        this.players = players;
        this.violations = violations;
        this.config = config;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Player p = event.getPlayer();
        PlayerData d = players.get(p);
        Block placed = event.getBlockPlaced();
        Block against = event.getBlockAgainst();
        SentinelConfig cfg = config.get();

        // ---------------------------------------------------------------- AirPlace: clicked on nothing
        // When the clicked block is replaced (air, grass, snow ...) Bukkit reports the placed block itself as
        // "against"; the state before placement tells whether the client clicked plain air, which a vanilla
        // client cannot do. Scaffolding moves its placement to the top of a column on its own, so it is skipped.
        boolean replacedClicked = against.getX() == placed.getX() && against.getY() == placed.getY()
                && against.getZ() == placed.getZ();
        boolean clickedAir = against.getType().isAir()
                || (replacedClicked && event.getBlockReplacedState().getType().isAir());
        if (clickedAir && placed.getType() != org.bukkit.Material.SCAFFOLDING) {
            if (violations.fail(p, d, CheckType.AIRPLACE, 1.0, "clicked air")) {
                event.setCancelled(true);
                return;
            }
        } else {
            violations.pass(d, CheckType.AIRPLACE);
        }

        // ---------------------------------------------------------------- BlockReach (to the clicked block)
        CheckSettings reach = cfg.check(CheckType.BLOCKREACH);
        if (reach.enabled()) {
            Location eye = p.getEyeLocation();
            double range = MovementProcessor.attribute(p, Attribute.BLOCK_INTERACTION_RANGE, 4.5);
            double dist = PositionHistory.distanceToBox(eye.getX(), eye.getY(), eye.getZ(), against.getX(),
                    against.getY(), against.getZ(), against.getX() + 1, against.getY() + 1, against.getZ() + 1);
            if (dist > range + reach.param("tolerance")) {
                if (violations.fail(p, d, CheckType.BLOCKREACH, 1.0,
                        String.format(Locale.US, "%.2f > %.2f", dist, range))) {
                    event.setCancelled(true);
                    return;
                }
            } else {
                violations.pass(d, CheckType.BLOCKREACH);
            }
        }

        // ---------------------------------------------------------------- Scaffold
        CheckSettings scaffold = cfg.check(CheckType.SCAFFOLD);
        if (scaffold.enabled()) {
            Location loc = p.getLocation();
            boolean underFeet = placed.getX() == loc.getBlockX() && placed.getZ() == loc.getBlockZ()
                    && placed.getY() == loc.getBlockY() - 1 && against.getY() == placed.getY();
            if (underFeet && loc.getPitch() < scaffold.param("max-pitch")) {
                if (violations.fail(p, d, CheckType.SCAFFOLD, 1.0,
                        String.format(Locale.US, "pitch=%.1f", loc.getPitch()))) {
                    event.setCancelled(true);
                }
            } else if (underFeet) {
                violations.pass(d, CheckType.SCAFFOLD);
            }
        }
    }
}
