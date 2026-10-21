package dev.fokend.sentinel.data;

import org.bukkit.Location;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

public final class PlayerData {

    private final UUID uuid;
    private final Map<CheckType, Double> violations = new EnumMap<>(CheckType.class);
    private final Map<CheckType, Double> buffers = new EnumMap<>(CheckType.class);

    private boolean alertsEnabled = true;

    // Состояния для AutoAttributeSwap и Killaura
    private long lastSlotSwitchMillis = 0L;
    private int lastSwitchTick = 0;
    private int switchesInCurrentTick = 0;
    private int lastAttackTick = 0;
    private UUID lastTargetUuid = null;

    // Состояния для Movement (Fly / Speed / NoSlow)
    private int airTicks = 0;
    private double lastDeltaY = 0.0;
    private int velocityGraceTicks = 0;
    private int teleportGraceTicks = 0;
    private int iceGraceTicks = 0;
    private int slimeGraceTicks = 0;
    private int lastGraceTick = Integer.MIN_VALUE;
    private int useItemTicks = 0;
    private Location lastSafeGroundLocation = null;

    // Состояния для Scaffold
    private long lastBlockPlaceMillis = 0L;
    private float lastPlacePitch = 0.0f;

    public PlayerData(UUID uuid) {
        this.uuid = uuid;
    }

    public UUID getUuid() {
        return uuid;
    }

    public double addViolation(CheckType type, double amount) {
        double updated = violations.getOrDefault(type, 0.0) + amount;
        violations.put(type, updated);
        return updated;
    }

    public double getViolation(CheckType type) {
        return violations.getOrDefault(type, 0.0);
    }

    public void decayViolations(double factor) {
        for (Map.Entry<CheckType, Double> entry : violations.entrySet()) {
            double next = entry.getValue() * factor;
            if (next < 0.05) {
                violations.put(entry.getKey(), 0.0);
            } else {
                violations.put(entry.getKey(), next);
            }
        }
    }

    public double incrementBuffer(CheckType type, double amount) {
        double next = buffers.getOrDefault(type, 0.0) + amount;
        buffers.put(type, next);
        return next;
    }

    public void decayBuffer(CheckType type, double amount) {
        double next = Math.max(0.0, buffers.getOrDefault(type, 0.0) - amount);
        buffers.put(type, next);
    }

    public void resetBuffer(CheckType type) {
        buffers.put(type, 0.0);
    }

    public boolean isAlertsEnabled() {
        return alertsEnabled;
    }

    public void setAlertsEnabled(boolean alertsEnabled) {
        this.alertsEnabled = alertsEnabled;
    }

    public long getLastSlotSwitchMillis() {
        return lastSlotSwitchMillis;
    }

    public void recordSlotSwitch(long nowMillis, int currentTick) {
        this.lastSlotSwitchMillis = nowMillis;
        if (this.lastSwitchTick == currentTick) {
            this.switchesInCurrentTick++;
        } else {
            this.lastSwitchTick = currentTick;
            this.switchesInCurrentTick = 1;
        }
    }

    public int getLastSwitchTick() {
        return lastSwitchTick;
    }

    public int getSwitchesInCurrentTick() {
        return switchesInCurrentTick;
    }

    public int getLastAttackTick() {
        return lastAttackTick;
    }

    public void setLastAttackTick(int lastAttackTick) {
        this.lastAttackTick = lastAttackTick;
    }

    public UUID getLastTargetUuid() {
        return lastTargetUuid;
    }

    public void setLastTargetUuid(UUID lastTargetUuid) {
        this.lastTargetUuid = lastTargetUuid;
    }

    public int getAirTicks() {
        return airTicks;
    }

    public void incrementAirTicks() {
        this.airTicks++;
    }

    public void resetAirTicks() {
        this.airTicks = 0;
    }

    public double getLastDeltaY() {
        return lastDeltaY;
    }

    public void setLastDeltaY(double lastDeltaY) {
        this.lastDeltaY = lastDeltaY;
    }

    public int getVelocityGraceTicks() {
        return velocityGraceTicks;
    }

    public void setVelocityGraceTicks(int velocityGraceTicks) {
        this.velocityGraceTicks = velocityGraceTicks;
    }

    public void tickGraceCounters(int currentTick) {
        // PlayerMoveEvent may fire several times in one server tick. Grace periods
        // are measured in ticks, so decrement each counter at most once per tick.
        if (lastGraceTick == currentTick) {
            return;
        }
        lastGraceTick = currentTick;
        if (velocityGraceTicks > 0) velocityGraceTicks--;
        if (teleportGraceTicks > 0) teleportGraceTicks--;
        if (iceGraceTicks > 0) iceGraceTicks--;
        if (slimeGraceTicks > 0) slimeGraceTicks--;
    }

    public int getTeleportGraceTicks() {
        return teleportGraceTicks;
    }

    public void setTeleportGraceTicks(int teleportGraceTicks) {
        this.teleportGraceTicks = teleportGraceTicks;
    }

    public int getIceGraceTicks() {
        return iceGraceTicks;
    }

    public void setIceGraceTicks(int iceGraceTicks) {
        this.iceGraceTicks = iceGraceTicks;
    }

    public int getSlimeGraceTicks() {
        return slimeGraceTicks;
    }

    public void setSlimeGraceTicks(int slimeGraceTicks) {
        this.slimeGraceTicks = slimeGraceTicks;
    }

    public int getUseItemTicks() {
        return useItemTicks;
    }

    public void incrementUseItemTicks() {
        this.useItemTicks++;
    }

    public void resetUseItemTicks() {
        this.useItemTicks = 0;
    }

    public Location getLastSafeGroundLocation() {
        return lastSafeGroundLocation;
    }

    public void setLastSafeGroundLocation(Location lastSafeGroundLocation) {
        this.lastSafeGroundLocation = lastSafeGroundLocation.clone();
    }

    public long getLastBlockPlaceMillis() {
        return lastBlockPlaceMillis;
    }

    public void setLastBlockPlaceMillis(long lastBlockPlaceMillis) {
        this.lastBlockPlaceMillis = lastBlockPlaceMillis;
    }

    public float getLastPlacePitch() {
        return lastPlacePitch;
    }

    public void setLastPlacePitch(float lastPlacePitch) {
        this.lastPlacePitch = lastPlacePitch;
    }
}

