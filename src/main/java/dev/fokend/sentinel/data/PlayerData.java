package dev.fokend.sentinel.data;

import dev.fokend.sentinel.engine.PositionHistory;
import dev.fokend.sentinel.engine.SwapTracker;
import dev.fokend.sentinel.engine.TimerBalance;
import java.util.UUID;

/**
 * Per-player state. Main thread only. Holds primitives and UUIDs, never Bukkit objects, and is removed
 * when the player quits.
 */
public final class PlayerData {

    public static final int HISTORY_TICKS = 40;

    private final UUID uuid;
    private final double[] violations = new double[CheckType.values().length];
    private final double[] buffers = new double[CheckType.values().length];
    private final long[] lastAlert = new long[CheckType.values().length];

    public boolean alerts = true;
    public boolean kickPending;

    // ---- client tick tracking
    /** Server tick of the last client_tick_end packet; clients that never send it are not checked. */
    public int lastClientTickEnd = Integer.MIN_VALUE;
    public long clientTicks;

    // ---- combat (per client tick)
    public final SwapTracker swaps = new SwapTracker();
    public UUID firstTargetThisTick;
    public boolean attackedThisTick;
    public boolean swungThisTick;
    public long autoSwapPenaltyUntil;

    // ---- movement
    public boolean hasLast;
    public UUID lastWorld;
    public double lastX, lastY, lastZ;
    public double lastDeltaY, lastDeltaXZ;
    /** Ground state / slipperiness at the end of the previous tick (= start of the current tick). */
    public boolean lastGround;
    public double lastSlip = 0.6;
    /** Ground state / slipperiness at the start of the previous tick. */
    public boolean prevStartGround;
    public double prevStartSlip = 0.6;
    public double lastSpeedAttribute = 0.1;
    public boolean lastSprinting;
    /** Client ticks during which movement checks are skipped (after teleports, fluids, climbing ...). */
    public int exemptTicks;
    /** Pending knockback tolerance. */
    public double velocityXZ, velocityY;
    public int velocityTicks;
    /** Last position that passed every movement check while on ground (setback target). */
    public boolean hasSafe;
    public UUID safeWorld;
    public double safeX, safeY, safeZ;

    // ---- timer
    public final TimerBalance timer = new TimerBalance();
    public int timerGraceTicks = 100;

    // ---- reach
    public final PositionHistory history = new PositionHistory(HISTORY_TICKS);

    public PlayerData(UUID uuid) {
        this.uuid = uuid;
    }

    public UUID uuid() {
        return uuid;
    }

    public double violation(CheckType t) {
        return violations[t.ordinal()];
    }

    public double addViolation(CheckType t, double amount) {
        return violations[t.ordinal()] += amount;
    }

    public void decayViolations(double factor) {
        for (int i = 0; i < violations.length; i++) {
            violations[i] = violations[i] * factor < 0.05 ? 0 : violations[i] * factor;
        }
    }

    public void resetViolations() {
        java.util.Arrays.fill(violations, 0);
        java.util.Arrays.fill(buffers, 0);
    }

    public double addBuffer(CheckType t, double amount) {
        return buffers[t.ordinal()] += amount;
    }

    public void decayBuffer(CheckType t, double amount) {
        buffers[t.ordinal()] = Math.max(0, buffers[t.ordinal()] - amount);
    }

    public void resetBuffer(CheckType t) {
        buffers[t.ordinal()] = 0;
    }

    public long lastAlert(CheckType t) {
        return lastAlert[t.ordinal()];
    }

    public void lastAlert(CheckType t, long when) {
        lastAlert[t.ordinal()] = when;
    }

    /** Forget movement history (teleports, respawns, world changes, flight toggles ...). */
    public void resetMovement(int exemptFor) {
        hasLast = false;
        exemptTicks = Math.max(exemptTicks, exemptFor);
        velocityTicks = 0;
        velocityXZ = 0;
        velocityY = 0;
    }
}
