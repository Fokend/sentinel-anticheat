package dev.fokend.sentinel.listener;

import dev.fokend.sentinel.data.CheckType;
import dev.fokend.sentinel.data.PlayerData;
import dev.fokend.sentinel.data.PlayerDataManager;
import dev.fokend.sentinel.service.AlertService;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Locale;

public final class WorldCheckListener implements Listener {

    private static final BlockFace[] ADJACENT_FACES = {
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST,
            BlockFace.WEST, BlockFace.UP, BlockFace.DOWN
    };

    private final PlayerDataManager dataManager;
    private final AlertService alertService;

    public WorldCheckListener(PlayerDataManager dataManager, AlertService alertService) {
        this.dataManager = dataManager;
        this.alertService = alertService;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("sentinel.bypass")) {
            return;
        }

        Block placed = event.getBlockPlaced();
        Block against = event.getBlockAgainst();
        PlayerData data = dataManager.getData(player);

        // 1. Проверка AirPlace
        // Исключение для кувшинки и лягушачьей икры, которые ставятся на воду
        if (against.getType().isAir() || (!hasAdjacentSupport(placed) && placed.getType() != Material.LILY_PAD)) {
            event.setCancelled(true);
            alertService.flag(
                    player,
                    CheckType.AIR_PLACE,
                    1.5,
                    "against=" + against.getType().name(),
                    false
            );
            return;
        }

        // 2. Проверка дистанции установки блока (Block Reach / Nuker)
        double maxDist = player.getGameMode() == GameMode.CREATIVE ? 6.2 : 5.25;
        double dist = player.getEyeLocation().distance(placed.getLocation().add(0.5, 0.5, 0.5));
        if (dist > maxDist) {
            event.setCancelled(true);
            alertService.flag(
                    player,
                    CheckType.AIR_PLACE,
                    1.0,
                    String.format(Locale.US, "blockReach=%.2f", dist),
                    false
            );
            return;
        }

        // 3. Проверка Scaffold (игрок ставит блок ровно под ноги, но смотрит прямо вперед или вверх)
        boolean placedDirectlyUnderFeet = placed.getX() == player.getLocation().getBlockX()
                && placed.getZ() == player.getLocation().getBlockZ()
                && placed.getY() == player.getLocation().getBlockY() - 1;

        float currentPitch = player.getLocation().getPitch();
        long now = System.currentTimeMillis();

        if (placedDirectlyUnderFeet && against.getY() == placed.getY() && currentPitch < 42.0f) {
            double buffer = data.incrementBuffer(CheckType.SCAFFOLD, 1.0);
            if (buffer >= 2.0) {
                event.setCancelled(true);
                alertService.flag(
                        player,
                        CheckType.SCAFFOLD,
                        1.5,
                        String.format(Locale.US, "impossiblePitch=%.1f", currentPitch),
                        true
                );
                return;
            }
        } else {
            data.decayBuffer(CheckType.SCAFFOLD, 0.25);
        }

        data.setLastBlockPlaceMillis(now);
        data.setLastPlacePitch(currentPitch);
    }

    private boolean hasAdjacentSupport(Block block) {
        for (BlockFace face : ADJACENT_FACES) {
            Material neighborType = block.getRelative(face).getType();
            if (!neighborType.isAir()) {
                return true;
            }
        }
        return false;
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Очистка данных по UUID для предотвращения утечек памяти
        dataManager.removeData(event.getPlayer().getUniqueId());
    }
}

