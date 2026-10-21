package dev.fokend.sentinel.listener;

import dev.fokend.sentinel.data.CheckType;
import dev.fokend.sentinel.data.PlayerData;
import dev.fokend.sentinel.data.PlayerDataManager;
import dev.fokend.sentinel.service.AlertService;
import org.bukkit.GameMode;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;

import java.util.Locale;

public final class MovementCheckListener implements Listener {

    private final PlayerDataManager dataManager;
    private final AlertService alertService;

    public MovementCheckListener(PlayerDataManager dataManager, AlertService alertService) {
        this.dataManager = dataManager;
        this.alertService = alertService;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerVelocity(PlayerVelocityEvent event) {
        dataManager.getData(event.getPlayer()).setVelocityGraceTicks(30);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        PlayerData data = dataManager.getData(event.getPlayer());
        data.setTeleportGraceTicks(20);
        data.resetAirTicks();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("sentinel.bypass")) {
            return;
        }

        GameMode gm = player.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR || player.getAllowFlight() || player.isGliding() || player.isInsideVehicle()) {
            return;
        }

        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ()) {
            return;
        }

        PlayerData data = dataManager.getData(player);
        data.tickGraceCounters(Bukkit.getCurrentTick());

        if (data.getTeleportGraceTicks() > 0) {
            return;
        }

        // Проверяем невозможный угол камеры в движении (Scaffold / Derp / Aimbot)
        if (Math.abs(to.getPitch()) > 90.01f) {
            alertService.flag(player, CheckType.BAD_PACKETS, 2.0, "movePitch=" + to.getPitch(), true);
            return;
        }

        EnvironmentState env = inspectEnvironment(player);
        if (env.hasIce) data.setIceGraceTicks(35);
        if (env.hasSlime) data.setSlimeGraceTicks(40);

        double deltaX = to.getX() - from.getX();
        double deltaY = to.getY() - from.getY();
        double deltaZ = to.getZ() - from.getZ();
        double deltaXZ = Math.hypot(deltaX, deltaZ);

        // Сохраняем последнюю твердую точку на земле для Setback
        if (env.serverOnGround && !env.inLiquid) {
            data.resetAirTicks();
            data.setLastSafeGroundLocation(from);
        } else {
            data.incrementAirTicks();
        }

        // 1. Проверка FLY
        checkFly(player, data, env, deltaY);

        // 2. Проверка SPEED
        checkSpeed(player, data, env, deltaXZ);

        // 3. Проверка NOSLOW (бег с натянутым луком / щитом / едой)
        checkNoSlow(player, data, env, deltaXZ);

        data.setLastDeltaY(deltaY);
    }

    private void checkFly(Player player, PlayerData data, EnvironmentState env, double deltaY) {
        if (env.inLiquid || env.onClimbable || env.inWeb || player.hasPotionEffect(PotionEffectType.LEVITATION)
                || player.hasPotionEffect(PotionEffectType.SLOW_FALLING) || data.getVelocityGraceTicks() > 0
                || data.getSlimeGraceTicks() > 0) {
            data.decayBuffer(CheckType.FLY, 0.5);
            return;
        }

        int airTicks = data.getAirTicks();
        int jumpBoostAmp = getPotionAmplifier(player, PotionEffectType.JUMP_BOOST);
        int maxAscendingTicks = 12 + (jumpBoostAmp * 4);

        // Сценарий А: Игрок продолжает набирать высоту или висеть на месте (deltaY >= 0) слишком долго в воздухе
        if (airTicks > maxAscendingTicks && deltaY >= -0.015) {
            double buffer = data.incrementBuffer(CheckType.FLY, 1.0);
            if (buffer >= 2.0) {
                alertService.flag(
                        player,
                        CheckType.FLY,
                        1.5,
                        String.format(Locale.US, "airTicks=%d, dY=%.3f", airTicks, deltaY),
                        true
                );
            }
            return;
        }

        // Сценарий Б: Проверка физики гравитации после пика прыжка (AirJump / Glide)
        if (airTicks > 6 && deltaY > data.getLastDeltaY() + 0.08 && !env.nearStairsOrSlabs) {
            double buffer = data.incrementBuffer(CheckType.FLY, 1.25);
            if (buffer >= 2.0) {
                alertService.flag(
                        player,
                        CheckType.FLY,
                        1.5,
                        String.format(Locale.US, "airAscend dY=%.3f > lastDY=%.3f", deltaY, data.getLastDeltaY()),
                        true
                );
            }
            return;
        }

        data.decayBuffer(CheckType.FLY, 0.25);
    }

    private void checkSpeed(Player player, PlayerData data, EnvironmentState env, double deltaXZ) {
        if (data.getVelocityGraceTicks() > 0) {
            return;
        }

        // Базовый лимит с учетом прыжка с разбега
        double maxSpeed = env.serverOnGround ? 0.38 : 0.43;

        // Учет кастомного walkSpeed (напр. от плагинов)
        float walkSpeed = player.getWalkSpeed();
        if (walkSpeed > 0.2f) {
            maxSpeed *= (walkSpeed / 0.2f);
        }

        // Зелье скорости (+20% за каждый уровень)
        int speedAmp = getPotionAmplifier(player, PotionEffectType.SPEED);
        if (speedAmp > 0) {
            maxSpeed += speedAmp * 0.085;
        }

        // Лед, блоки под головой (HeadHitter) и полублоки/ступеньки
        if (data.getIceGraceTicks() > 0) {
            maxSpeed += 0.28;
        }
        if (env.blockAboveHead) {
            maxSpeed += 0.22;
        }
        if (env.nearStairsOrSlabs) {
            maxSpeed += 0.16;
        }
        if (env.onSoulSpeedBlock && player.getInventory().getBoots() != null) {
            maxSpeed += 0.14;
        }

        if (deltaXZ > maxSpeed) {
            double buffer = data.incrementBuffer(CheckType.SPEED, 1.0);
            if (buffer >= 3.0 || deltaXZ > maxSpeed + 0.25) {
                alertService.flag(
                        player,
                        CheckType.SPEED,
                        1.0,
                        String.format(Locale.US, "dXZ=%.3f > max=%.3f", deltaXZ, maxSpeed),
                        true
                );
            }
        } else {
            data.decayBuffer(CheckType.SPEED, 0.2);
        }
    }

    private void checkNoSlow(Player player, PlayerData data, EnvironmentState env, double deltaXZ) {
        if (player.isHandRaised() && data.getVelocityGraceTicks() == 0 && data.getIceGraceTicks() == 0) {
            data.incrementUseItemTicks();
            // После 6 тиков использования предмета (еда, лук, щит) скорость обязана упасть
            if (data.getUseItemTicks() > 6 && env.serverOnGround) {
                int speedAmp = getPotionAmplifier(player, PotionEffectType.SPEED);
                double maxAllowedNoSlow = 0.16 + (speedAmp * 0.03);

                if (deltaXZ > maxAllowedNoSlow) {
                    double buffer = data.incrementBuffer(CheckType.NOSLOW, 1.0);
                    if (buffer >= 3.0) {
                        alertService.flag(
                                player,
                                CheckType.NOSLOW,
                                1.0,
                                String.format(Locale.US, "useSpeed=%.3f > %.3f", deltaXZ, maxAllowedNoSlow),
                                true
                        );
                    }
                }
            }
        } else {
            data.resetUseItemTicks();
            data.decayBuffer(CheckType.NOSLOW, 0.25);
        }
    }

    private int getPotionAmplifier(Player player, PotionEffectType type) {
        PotionEffect effect = player.getPotionEffect(type);
        return effect != null ? effect.getAmplifier() + 1 : 0;
    }

    private EnvironmentState inspectEnvironment(Player player) {
        EnvironmentState state = new EnvironmentState();
        World world = player.getWorld();
        BoundingBox box = player.getBoundingBox().clone().expand(0.3, 0.5, 0.3);

        int minX = (int) Math.floor(box.getMinX());
        int maxX = (int) Math.floor(box.getMaxX());
        int minY = (int) Math.floor(box.getMinY());
        int maxY = (int) Math.floor(box.getMaxY());
        int minZ = (int) Math.floor(box.getMinZ());
        int maxZ = (int) Math.floor(box.getMaxZ());

        double footY = player.getLocation().getY();

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                        continue;
                    }
                    Block block = world.getBlockAt(x, y, z);
                    Material type = block.getType();
                    if (type.isAir()) {
                        continue;
                    }

                    String name = type.name();
                    if (block.isLiquid() || name.contains("KELP") || name.contains("SEAGRASS") || type == Material.BUBBLE_COLUMN) {
                        state.inLiquid = true;
                    }
                    if (type == Material.LADDER || type == Material.VINE || name.contains("VINE") || type == Material.SCAFFOLDING) {
                        state.onClimbable = true;
                    }
                    if (type == Material.COBWEB || type == Material.SWEET_BERRY_BUSH || type == Material.POWDER_SNOW) {
                        state.inWeb = true;
                    }
                    if (name.contains("ICE")) {
                        state.hasIce = true;
                    }
                    if (type == Material.SLIME_BLOCK || type == Material.HONEY_BLOCK || name.contains("BED")) {
                        state.hasSlime = true;
                    }
                    if (name.contains("STAIRS") || name.contains("SLAB") || name.contains("STEP") || name.contains("FENCE") || name.contains("WALL")) {
                        state.nearStairsOrSlabs = true;
                    }
                    if (type == Material.SOUL_SAND || type == Material.SOUL_SOIL) {
                        state.onSoulSpeedBlock = true;
                    }

                    if (!block.isPassable()) {
                        if (y <= footY + 0.2) {
                            state.serverOnGround = true;
                        }
                        if (y >= footY + 1.8) {
                            state.blockAboveHead = true;
                        }
                    }
                }
            }
        }
        return state;
    }

    private static final class EnvironmentState {
        boolean serverOnGround = false;
        boolean inLiquid = false;
        boolean onClimbable = false;
        boolean inWeb = false;
        boolean hasIce = false;
        boolean hasSlime = false;
        boolean nearStairsOrSlabs = false;
        boolean blockAboveHead = false;
        boolean onSoulSpeedBlock = false;
    }
}

