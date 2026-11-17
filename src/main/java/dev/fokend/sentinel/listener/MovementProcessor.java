package dev.fokend.sentinel.listener;

import dev.fokend.sentinel.config.CheckSettings;
import dev.fokend.sentinel.config.SentinelConfig;
import dev.fokend.sentinel.data.CheckType;
import dev.fokend.sentinel.data.PlayerData;
import dev.fokend.sentinel.engine.MovementPhysics;
import dev.fokend.sentinel.service.ViolationService;
import java.util.Locale;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.entity.Shulker;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;

/**
 * Per-client-tick movement verification (Fly, Speed, NoSlow). Runs once per {@code client_tick_end},
 * so every tick of the client is checked exactly once, including ticks where a cheater hovers without
 * sending any movement.
 *
 * <p>Cost when the movement is legitimate: one collision query and one block lookup. The surroundings
 * are only scanned for special blocks/entities when a tick fails the physics bounds.</p>
 */
public final class MovementProcessor {

    private final ViolationService violations;

    public MovementProcessor(ViolationService violations) {
        this.violations = violations;
    }

    public void process(Player p, PlayerData d, SentinelConfig cfg) {
        CheckSettings fly = cfg.check(CheckType.FLY);
        CheckSettings speed = cfg.check(CheckType.SPEED);
        CheckSettings noslow = cfg.check(CheckType.NOSLOW);
        if (!fly.enabled() && !speed.enabled() && !noslow.enabled()) {
            return;
        }
        if (skipState(p)) {
            d.resetMovement(5);
            return;
        }
        World world = p.getWorld();
        double x = p.getX();
        double y = p.getY();
        double z = p.getZ();
        BoundingBox box = p.getBoundingBox();
        boolean ground = onGround(world, box);
        double slip = ground ? slipperiness(world, x, box.getMinY(), z) : MovementPhysics.DEFAULT_SLIPPERINESS;
        double speedAttribute = attribute(p, Attribute.MOVEMENT_SPEED, 0.1);

        if (!d.hasLast || !world.getUID().equals(d.lastWorld)) {
            remember(d, world, x, y, z, ground, slip, 0, 0, speedAttribute, p.isSprinting(), true);
            return;
        }
        double dx = x - d.lastX;
        double dy = y - d.lastY;
        double dz = z - d.lastZ;
        double dxz = Math.sqrt(dx * dx + dz * dz);
        if (dxz > 8 || Math.abs(dy) > 8) {
            // A server-side teleport/correction that did not fire an event: start over.
            remember(d, world, x, y, z, ground, slip, 0, 0, speedAttribute, p.isSprinting(), true);
            return;
        }

        if (p.isInWater() || p.isInLava() || p.isClimbing()
                || p.hasPotionEffect(PotionEffectType.LEVITATION)) {
            d.exemptTicks = Math.max(d.exemptTicks, 2);
        }
        boolean check = d.exemptTicks <= 0;
        if (d.exemptTicks > 0) {
            d.exemptTicks--;
        }
        boolean velocity = d.velocityTicks > 0;
        if (velocity) {
            d.velocityTicks--;
        }

        boolean flagged = false;
        boolean mitigate = false;
        Boolean envExempt = null; // lazily computed
        if (check) {
            // ------------------------------------------------------------------ Fly (vertical)
            if (fly.enabled()) {
                double maxDY;
                double step = attribute(p, Attribute.STEP_HEIGHT, 0.6);
                if (d.lastGround) {
                    double jump = MovementPhysics.jumpVelocity(attribute(p, Attribute.JUMP_STRENGTH, 0.42),
                            level(p, PotionEffectType.JUMP_BOOST));
                    maxDY = Math.max(jump, step);
                } else {
                    double g = MovementPhysics.gravity(attribute(p, Attribute.GRAVITY, 0.08),
                            p.hasPotionEffect(PotionEffectType.SLOW_FALLING), d.lastDeltaY);
                    maxDY = MovementPhysics.maxAirborneDeltaY(d.lastDeltaY, g);
                    if (ground) {
                        maxDY = Math.max(maxDY, step); // stepping up onto a block while landing
                    }
                }
                if (velocity) {
                    maxDY = Math.max(maxDY, d.velocityY);
                }
                if (dy > maxDY + fly.param("tolerance")) {
                    envExempt = environmentExempt(p, world, box);
                    if (!envExempt) {
                        flagged = true;
                        mitigate |= violations.fail(p, d, CheckType.FLY, 1.0, String.format(Locale.US,
                                "dY=%.3f > %.3f, lastDY=%.3f, ground %s→%s, y=%.3f",
                                dy, maxDY, d.lastDeltaY, d.lastGround, ground, y));
                    }
                } else {
                    violations.pass(d, CheckType.FLY);
                }
            }
            // ------------------------------------------------------------------ Speed / NoSlow (horizontal)
            if (speed.enabled() || noslow.enabled()) {
                double speedBound = Math.max(speedAttribute, d.lastSpeedAttribute);
                boolean sprinting = p.isSprinting() || d.lastSprinting;
                boolean sprintJump = d.lastGround && dy > 0.1 && sprinting;
                double max = MovementPhysics.maxHorizontal(d.lastDeltaXZ, d.prevStartGround, d.prevStartSlip,
                        d.lastGround, d.lastSlip, speedBound, 1.0, sprintJump);
                if (velocity) {
                    max = Math.max(max, d.velocityXZ);
                }
                if (speed.enabled() && dxz > max + speed.param("tolerance")) {
                    if (envExempt == null) {
                        envExempt = environmentExempt(p, world, box);
                    }
                    if (!envExempt) {
                        flagged = true;
                        mitigate |= violations.fail(p, d, CheckType.SPEED, 1.0,
                                String.format(Locale.US, "dXZ=%.3f > %.3f, lastXZ=%.3f, ground %s→%s, slip=%.2f",
                                        dxz, max, d.lastDeltaXZ, d.lastGround, ground, d.lastSlip));
                    }
                } else {
                    violations.pass(d, CheckType.SPEED);
                    if (noslow.enabled() && !velocity && p.isHandRaised()
                            && p.getActiveItemUsedTime() >= noslow.param("min-use-ticks")) {
                        double maxSlow = MovementPhysics.maxHorizontal(d.lastDeltaXZ, d.prevStartGround,
                                d.prevStartSlip, d.lastGround, d.lastSlip, speedBound,
                                MovementPhysics.USE_ITEM_MULTIPLIER, sprintJump);
                        if (dxz > maxSlow + noslow.param("tolerance")) {
                            if (envExempt == null) {
                                envExempt = environmentExempt(p, world, box);
                            }
                            if (!envExempt) {
                                flagged = true;
                                mitigate |= violations.fail(p, d, CheckType.NOSLOW, 1.0,
                                        String.format(Locale.US, "dXZ=%.3f > %.3f (using item)", dxz, maxSlow));
                            }
                        } else {
                            violations.pass(d, CheckType.NOSLOW);
                        }
                    }
                }
            }
        }

        if (mitigate) {
            violations.setback(p, d); // resets movement state; the teleport also fires PlayerTeleportEvent
            return;
        }
        if (check && !flagged && ground) {
            d.hasSafe = true;
            d.safeWorld = world.getUID();
            d.safeX = x;
            d.safeY = y;
            d.safeZ = z;
        }
        remember(d, world, x, y, z, ground, slip, dy, dxz, speedAttribute, p.isSprinting(), false);
    }

    private static void remember(PlayerData d, World world, double x, double y, double z, boolean ground, double slip,
                                 double dy, double dxz, double speedAttribute, boolean sprinting, boolean fresh) {
        if (fresh) {
            d.prevStartGround = ground;
            d.prevStartSlip = slip;
            // The real velocity is unknown until two consecutive ticks have been measured.
            d.exemptTicks = Math.max(d.exemptTicks, 2);
        } else {
            d.prevStartGround = d.lastGround;
            d.prevStartSlip = d.lastSlip;
        }
        d.hasLast = true;
        d.lastWorld = world.getUID();
        d.lastX = x;
        d.lastY = y;
        d.lastZ = z;
        d.lastGround = ground;
        d.lastSlip = slip;
        d.lastDeltaY = dy;
        d.lastDeltaXZ = dxz;
        d.lastSpeedAttribute = speedAttribute;
        d.lastSprinting = sprinting;
    }

    /** States in which vanilla movement rules do not apply at all. */
    private static boolean skipState(Player p) {
        GameMode gm = p.getGameMode();
        return gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR || p.getAllowFlight() || p.isFlying()
                || p.isInsideVehicle() || p.isGliding() || p.isRiptiding() || p.isDead() || p.isSleeping();
    }

    /** Server-side ground check: is there a collision box directly below the player's feet? */
    static boolean onGround(World world, BoundingBox box) {
        // Collision tests are strict (touching faces do not collide), so the full width is used: a player
        // standing on the very edge of a block is on ground in vanilla as well.
        BoundingBox feet = new BoundingBox(box.getMinX(), box.getMinY() - 0.03, box.getMinZ(),
                box.getMaxX(), box.getMinY(), box.getMaxZ());
        return world.hasCollisionsIn(feet);
    }

    /** Slipperiness of the block that affects movement (vanilla: the block 0.5 below the feet). */
    private static double slipperiness(World world, double x, double minY, double z) {
        Block below = world.getBlockAt((int) Math.floor(x), (int) Math.floor(minY - 0.5000001), (int) Math.floor(z));
        float s = below.getType().getSlipperiness();
        return s > 0 ? s : MovementPhysics.DEFAULT_SLIPPERINESS;
    }

    /**
     * Blocks and entities whose effects the simple physics model does not cover (bounces, webs, honey,
     * pistons, ridable/pushing entities). Only evaluated after a failure.
     */
    private static boolean environmentExempt(Player p, World world, BoundingBox box) {
        int minX = (int) Math.floor(box.getMinX() - 0.4);
        int maxX = (int) Math.floor(box.getMaxX() + 0.4);
        int minY = (int) Math.floor(box.getMinY() - 1.0);
        int maxY = (int) Math.floor(box.getMaxY() + 0.5);
        int minZ = (int) Math.floor(box.getMinZ() - 0.4);
        int maxZ = (int) Math.floor(box.getMaxZ() + 0.4);
        for (int bx = minX; bx <= maxX; bx++) {
            for (int bz = minZ; bz <= maxZ; bz++) {
                if (!world.isChunkLoaded(bx >> 4, bz >> 4)) {
                    return true; // unknown surroundings: never punish
                }
                for (int by = minY; by <= maxY; by++) {
                    Material m = world.getBlockAt(bx, by, bz).getType();
                    if (m.isAir()) {
                        continue;
                    }
                    if (m == Material.COBWEB || m == Material.POWDER_SNOW || m == Material.SWEET_BERRY_BUSH
                            || m == Material.HONEY_BLOCK || m == Material.SLIME_BLOCK || m == Material.SCAFFOLDING
                            || m == Material.BUBBLE_COLUMN || m == Material.WATER || m == Material.LAVA
                            || m == Material.MOVING_PISTON || m == Material.PISTON_HEAD
                            || Tag.BEDS.isTagged(m) || Tag.CLIMBABLE.isTagged(m)) {
                        return true;
                    }
                }
            }
        }
        for (Entity e : p.getNearbyEntities(1.0, 1.5, 1.0)) {
            if (e instanceof Boat || e instanceof Minecart || e instanceof Shulker
                    || (e instanceof LivingEntity && e.getBoundingBox().overlaps(box.clone().expand(0.2)))) {
                return true; // standing on / pushed by an entity
            }
        }
        return false;
    }

    static double attribute(Player p, Attribute attribute, double fallback) {
        AttributeInstance a = p.getAttribute(attribute);
        return a != null ? a.getValue() : fallback;
    }

    private static int level(Player p, PotionEffectType type) {
        PotionEffect e = p.getPotionEffect(type);
        return e == null ? 0 : e.getAmplifier() + 1;
    }
}
