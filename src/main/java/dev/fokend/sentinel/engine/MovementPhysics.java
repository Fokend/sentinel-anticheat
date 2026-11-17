package dev.fokend.sentinel.engine;

/**
 * Upper bounds of legitimate player movement for one client tick, derived from vanilla
 * {@code LivingEntity#travel} (Minecraft 1.21.x). Pure math, no Bukkit types.
 *
 * <p>Vanilla per tick (no fluids, no climbing): horizontal velocity gets an acceleration, the player
 * moves by that velocity, then the velocity is multiplied by the friction of the tick. Vertically, the
 * player moves by its velocity, then {@code vy = (vy - gravity) * 0.98}. Measured per-tick deltas
 * therefore obey:
 * <pre>
 *   dXZ(t) <= dXZ(t-1) * friction(t-1) + accel(t) [+ 0.2 on a sprint-jump]
 *   dY(t)  <= (dY(t-1) - gravity) * 0.98                      (airborne)
 * </pre>
 * Collisions only ever reduce movement, so these are safe upper bounds.</p>
 */
public final class MovementPhysics {

    public static final double DEFAULT_SLIPPERINESS = 0.6;
    public static final double AIR_FRICTION = 0.91;
    public static final double VERTICAL_DRAG = 0.98;
    /** Air control while sprinting (0.02 when walking); the larger value is used as an upper bound. */
    public static final double AIR_ACCELERATION = 0.026;
    public static final double SPRINT_JUMP_BOOST = 0.2;
    /** Movement input multiplier while using an item (eating, drawing a bow, blocking). */
    public static final double USE_ITEM_MULTIPLIER = 0.2;

    private MovementPhysics() {
    }

    /** Friction applied at the end of a tick that started on ground / in the air. */
    public static double friction(boolean groundAtStart, double slipperiness) {
        return groundAtStart ? slipperiness * AIR_FRICTION : AIR_FRICTION;
    }

    /** Horizontal acceleration of a tick that started on ground / in the air. */
    public static double acceleration(boolean groundAtStart, double slipperiness, double movementSpeed) {
        if (!groundAtStart) {
            return AIR_ACCELERATION;
        }
        double s = Math.max(0.01, slipperiness);
        return movementSpeed * (0.21600002 / (s * s * s));
    }

    /**
     * Maximum horizontal distance for this tick.
     *
     * @param lastDeltaXZ         horizontal distance moved in the previous tick
     * @param groundStartPrevTick whether the previous tick started on ground
     * @param slipStartPrevTick   slipperiness of the block under the player at the start of the previous tick
     * @param groundStartThisTick whether this tick started on ground (= on ground at end of previous tick)
     * @param slipStartThisTick   slipperiness under the player at the start of this tick
     * @param movementSpeed       movement speed attribute (includes sprint, potions, soul speed ...)
     * @param inputMultiplier     1.0 normally, {@link #USE_ITEM_MULTIPLIER} while using an item
     * @param sprintJump          whether this tick is a sprint-jump
     */
    public static double maxHorizontal(double lastDeltaXZ, boolean groundStartPrevTick, double slipStartPrevTick,
                                       boolean groundStartThisTick, double slipStartThisTick, double movementSpeed,
                                       double inputMultiplier, boolean sprintJump) {
        double carried = lastDeltaXZ * friction(groundStartPrevTick, slipStartPrevTick);
        double accel = acceleration(groundStartThisTick, slipStartThisTick, movementSpeed) * inputMultiplier;
        return carried + accel + (sprintJump ? SPRINT_JUMP_BOOST : 0.0);
    }

    /** Maximum vertical delta for an airborne tick that did not start on ground. */
    public static double maxAirborneDeltaY(double lastDeltaY, double gravity) {
        return (lastDeltaY - gravity) * VERTICAL_DRAG;
    }

    /** Effective gravity: slow falling caps gravity at 0.01 while not moving upwards. */
    public static double gravity(double gravityAttribute, boolean slowFalling, double lastDeltaY) {
        if (slowFalling && lastDeltaY <= 0) {
            return Math.min(gravityAttribute, 0.01);
        }
        return gravityAttribute;
    }

    /** Jump velocity: jump_strength attribute plus 0.1 per level of Jump Boost. */
    public static double jumpVelocity(double jumpStrength, int jumpBoostLevel) {
        return jumpStrength + 0.1 * Math.max(0, jumpBoostLevel);
    }
}
