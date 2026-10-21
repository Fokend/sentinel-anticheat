package dev.fokend.sentinel.listener;

import dev.fokend.sentinel.data.CheckType;
import dev.fokend.sentinel.data.PlayerData;
import dev.fokend.sentinel.data.PlayerDataManager;
import dev.fokend.sentinel.service.AlertService;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.Locale;

public final class CombatCheckListener implements Listener {

    private final PlayerDataManager dataManager;
    private final AlertService alertService;

    public CombatCheckListener(PlayerDataManager dataManager, AlertService alertService) {
        this.dataManager = dataManager;
        this.alertService = alertService;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHotbarSwitch(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        PlayerData data = dataManager.getData(player);
        long now = System.currentTimeMillis();
        int currentTick = Bukkit.getCurrentTick();

        data.recordSlotSwitch(now, currentTick);

        // Если игрок сменил слот, ударил и вернул слот обратно в ТОТ ЖЕ серверный тик
        if (data.getLastAttackTick() == currentTick && data.getSwitchesInCurrentTick() >= 2) {
            alertService.flag(
                    player,
                    CheckType.AUTO_ATTRIBUTE_SWAP,
                    1.5,
                    "Post-Hit Tick Swap (switches=" + data.getSwitchesInCurrentTick() + ")",
                    false
            );
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) {
            return;
        }
        if (attacker.hasPermission("sentinel.bypass")) {
            return;
        }
        // Проверяем только прямые атаки игрока (не шипы, не взрывы, не sweeping edge по соседним мобам)
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) {
            return;
        }

        Entity target = event.getEntity();
        PlayerData data = dataManager.getData(attacker);
        int currentTick = Bukkit.getCurrentTick();
        long now = System.currentTimeMillis();

        // 1. Проверка BadPackets (невозможный Pitch при ударе)
        float pitch = attacker.getLocation().getPitch();
        if (Math.abs(pitch) > 90.01f) {
            event.setCancelled(true);
            alertService.flag(attacker, CheckType.BAD_PACKETS, 2.0, "pitch=" + pitch, false);
            return;
        }

        // 2. Проверка MultiAura (удар двух разных сущностей в один серверный тик без Sweeping Edge)
        if (data.getLastAttackTick() == currentTick
                && data.getLastTargetUuid() != null
                && !data.getLastTargetUuid().equals(target.getUniqueId())) {
            event.setCancelled(true);
            alertService.flag(attacker, CheckType.MULTI_AURA, 1.5, "2 targets in 1 tick", false);
            return;
        }
        data.setLastAttackTick(currentTick);
        data.setLastTargetUuid(target.getUniqueId());

        // 3. Проверка AutoAttributeSwap (мгновенный свап слота перед ударом)
        long timeSinceSwitch = now - data.getLastSlotSwitchMillis();
        boolean sameTickSwitch = data.getLastSwitchTick() == currentTick;

        if (sameTickSwitch || timeSinceSwitch <= 12L) {
            double buffer = data.incrementBuffer(CheckType.AUTO_ATTRIBUTE_SWAP, 1.0);
            if (buffer >= 1.5 || data.getSwitchesInCurrentTick() > 1) {
                event.setCancelled(true);
                alertService.flag(
                        attacker,
                        CheckType.AUTO_ATTRIBUTE_SWAP,
                        1.0,
                        "delta=" + timeSinceSwitch + "ms, switches=" + data.getSwitchesInCurrentTick(),
                        false
                );
                return;
            }
        } else {
            data.decayBuffer(CheckType.AUTO_ATTRIBUTE_SWAP, 0.25);
        }

        // 4. Расчет Reach и Hitbox
        Location eyeLoc = attacker.getEyeLocation();
        Vector eyePos = eyeLoc.toVector();
        Vector direction = eyeLoc.getDirection().normalize();

        // Серверный хитбокс цели. В ванильном клиенте при атаке есть маржа 0.1 блока (getPickRadius).
        // Добавляем компенсацию на интерполяцию движения и пинг.
        double pingCompensation = Math.min(0.25, (attacker.getPing() / 1000.0) * 1.2);
        BoundingBox targetBox = target.getBoundingBox().clone().expand(0.1 + pingCompensation);

        // 4a. Проверка Reach: расстояние от глаз до ближайшей точки на границе хитбокса
        double distanceToBox = calculateDistanceToBox(eyePos, target.getBoundingBox());
        double maxBaseReach = getMaxReach(attacker);
        double allowedReach = maxBaseReach + pingCompensation + (attacker.isSprinting() ? 0.15 : 0.05);

        if (distanceToBox > allowedReach) {
            double buffer = data.incrementBuffer(CheckType.REACH, 1.0);
            if (buffer >= 1.5 || distanceToBox > allowedReach + 0.35) {
                event.setCancelled(true);
                alertService.flag(
                        attacker,
                        CheckType.REACH,
                        1.0,
                        String.format(Locale.US, "dist=%.2f > max=%.2f", distanceToBox, allowedReach),
                        false
                );
                return;
            }
        } else {
            data.decayBuffer(CheckType.REACH, 0.2);
        }

        // 4b. Проверка HitBox (RayTrace пересечения вектора взгляда с BoundingBox цели)
        // Если глаза игрока еще не находятся внутри самого хитбокса цели, проверяем луч
        if (!targetBox.contains(eyePos)) {
            RayTraceResult rayHit = targetBox.rayTrace(eyePos, direction, 7.0);
            if (rayHit == null) {
                // Игрок ударил цель, но его вектор взгляда вообще не проходит через ее хитбокс
                double angleMiss = calculateAngleToBoxCenter(eyePos, direction, target.getBoundingBox());
                double buffer = data.incrementBuffer(CheckType.HITBOX, 1.0);

                if (buffer >= 2.0 || angleMiss > 0.38) {
                    event.setCancelled(true);
                    alertService.flag(
                            attacker,
                            CheckType.HITBOX,
                            1.0,
                            String.format(Locale.US, "rayMiss, angleRad=%.2f", angleMiss),
                            false
                    );
                }
            } else {
                data.decayBuffer(CheckType.HITBOX, 0.25);
            }
        }
    }

    private double getMaxReach(Player player) {
        if (player.getGameMode() == GameMode.CREATIVE) {
            return 5.0;
        }
        // В Paper 1.20.5+ / 1.21 поддерживается атрибут ENTITY_INTERACTION_RANGE
        AttributeInstance rangeAttr = player.getAttribute(Attribute.ENTITY_INTERACTION_RANGE);
        if (rangeAttr != null) {
            return rangeAttr.getValue();
        }
        return 3.0;
    }

    private double calculateDistanceToBox(Vector point, BoundingBox box) {
        double clampedX = Math.max(box.getMinX(), Math.min(point.getX(), box.getMaxX()));
        double clampedY = Math.max(box.getMinY(), Math.min(point.getY(), box.getMaxY()));
        double clampedZ = Math.max(box.getMinZ(), Math.min(point.getZ(), box.getMaxZ()));

        double dx = point.getX() - clampedX;
        double dy = point.getY() - clampedY;
        double dz = point.getZ() - clampedZ;

        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private double calculateAngleToBoxCenter(Vector eyePos, Vector lookDir, BoundingBox box) {
        Vector toCenter = box.getCenter().subtract(eyePos).normalize();
        return lookDir.angle(toCenter);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocityReceive(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            // Даем окну движения знать, что игрок получил легитимный Knockback
            dataManager.getData(player).setVelocityGraceTicks(25);
        }
    }
}

