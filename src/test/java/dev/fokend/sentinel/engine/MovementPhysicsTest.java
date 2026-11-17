package dev.fokend.sentinel.engine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MovementPhysicsTest {

    private static final double TOL_H = 0.025;
    private static final double TOL_V = 0.03;

    /** Minimal re-implementation of vanilla LivingEntity#travel for flat terrain, used as ground truth. */
    private static final class VanillaPlayer {
        double vx;          // horizontal velocity along the movement direction
        double vy;
        double y;
        boolean ground = true;
        double slip = 0.6;
        double speed = 0.1;  // movement_speed attribute
        boolean sprinting;
        double gravity = 0.08;
        boolean slowFalling;

        // previous measured deltas, for the checker
        double lastDXZ;
        double lastDY;
        boolean prevStartGround = true;
        double prevStartSlip = 0.6;
        boolean lastGround = true;
        double lastSlip = 0.6;

        double maxH;
        double maxV;

        /** One vanilla tick. Returns {dXZ, dY}. */
        double[] tick(boolean forward, boolean jump) {
            boolean startGround = ground;
            double f = startGround ? slip * 0.91 : 0.91;
            if (jump && startGround) {
                vy = 0.42;
                if (sprinting) {
                    vx += 0.2;
                }
            }
            double accel = startGround ? speed * (0.21600002 / (slip * slip * slip))
                    : (sprinting ? 0.026 : 0.02);
            if (forward) {
                vx += accel * 0.98;
            }
            double dxz = vx;
            double dy = vy;
            y += vy;
            if (y <= 0) { // landing on flat ground at y=0
                dy -= y;
                y = 0;
                vy = 0;
                ground = true;
            } else {
                ground = false;
            }
            vx *= f;
            double g = slowFalling && vy <= 0 ? Math.min(gravity, 0.01) : gravity;
            vy = (vy - g) * 0.98;
            if (ground) {
                vy = 0;
            }
            return new double[]{dxz, dy};
        }

        /** Runs the checker on the measured deltas, mirroring MovementProcessor. */
        boolean check(double dxz, double dy, boolean nowGround) {
            boolean sprintJump = lastGround && dy > 0.1 && sprinting;
            maxH = MovementPhysics.maxHorizontal(lastDXZ, prevStartGround, prevStartSlip, lastGround, lastSlip, speed,
                    1.0, sprintJump);
            if (lastGround) {
                maxV = Math.max(MovementPhysics.jumpVelocity(0.42, 0), 0.6);
            } else {
                maxV = MovementPhysics.maxAirborneDeltaY(lastDY, MovementPhysics.gravity(gravity, slowFalling, lastDY));
                if (nowGround) {
                    maxV = Math.max(maxV, 0.6);
                }
            }
            boolean ok = dxz <= maxH + TOL_H && dy <= maxV + TOL_V;
            prevStartGround = lastGround;
            prevStartSlip = lastSlip;
            lastGround = nowGround;
            lastSlip = nowGround ? slip : 0.6;
            lastDXZ = dxz;
            lastDY = dy;
            return ok;
        }

        boolean step(boolean forward, boolean jump) {
            double[] d = tick(forward, jump);
            return check(d[0], d[1], ground);
        }
    }

    @Test
    void walkingSprintingAndStoppingAreLegal() {
        VanillaPlayer p = new VanillaPlayer();
        for (int i = 0; i < 100; i++) {
            assertTrue(p.step(true, false), "walk tick " + i);
        }
        p.sprinting = true;
        p.speed = 0.13;
        for (int i = 0; i < 100; i++) {
            assertTrue(p.step(true, false), "sprint tick " + i);
        }
        for (int i = 0; i < 20; i++) {
            assertTrue(p.step(false, false), "stop tick " + i);
        }
    }

    @Test
    void continuousSprintJumpingIsLegal() {
        VanillaPlayer p = new VanillaPlayer();
        p.sprinting = true;
        p.speed = 0.13;
        for (int i = 0; i < 600; i++) {
            assertTrue(p.step(true, p.ground), "tick " + i + " maxH=" + p.maxH + " maxV=" + p.maxV);
        }
    }

    @Test
    void sprintJumpingOnIceIsLegal() {
        VanillaPlayer p = new VanillaPlayer();
        p.sprinting = true;
        p.speed = 0.13;
        p.slip = 0.98;
        p.lastSlip = 0.98;
        p.prevStartSlip = 0.98;
        for (int i = 0; i < 600; i++) {
            assertTrue(p.step(true, p.ground), "tick " + i);
        }
    }

    @Test
    void slowFallingIsLegal() {
        VanillaPlayer p = new VanillaPlayer();
        p.slowFalling = true;
        p.y = 30;
        p.ground = false;
        p.lastGround = false;
        p.prevStartGround = false;
        for (int i = 0; i < 400 && !(p.ground && i > 0); i++) {
            assertTrue(p.step(false, false), "tick " + i);
        }
    }

    @Test
    void hoveringIsDetected() {
        VanillaPlayer p = new VanillaPlayer();
        p.step(false, true); // jump
        boolean failed = false;
        for (int i = 0; i < 20; i++) {
            failed |= !p.check(0, 0, false); // stays at the same height in the air
        }
        assertTrue(failed);
    }

    @Test
    void airJumpAndGlideAreDetected() {
        VanillaPlayer p = new VanillaPlayer();
        p.step(false, true);
        for (int i = 0; i < 8; i++) {
            p.step(false, false);
        }
        assertFalse(p.check(0, 0.42, false), "jumping again while airborne");

        VanillaPlayer g = new VanillaPlayer();
        g.step(false, true);
        for (int i = 0; i < 12; i++) {
            g.step(false, false);
        }
        int fails = 0;
        for (int i = 0; i < 20; i++) {
            if (!g.check(0, -0.05, false)) { // falling slowly at a constant rate
                fails++;
            }
        }
        assertTrue(fails > 10, "glide detected in most ticks: " + fails);
    }

    @Test
    void speedHackIsDetected() {
        VanillaPlayer p = new VanillaPlayer();
        p.sprinting = true;
        p.speed = 0.13;
        for (int i = 0; i < 40; i++) {
            p.step(true, false);
        }
        int fails = 0;
        for (int i = 0; i < 40; i++) {
            if (!p.check(0.29 * 1.3, 0, true)) { // 30% faster than sprinting
                fails++;
            }
        }
        assertTrue(fails >= 35, "fails=" + fails);
    }

    @Test
    void noSlowIsDetected() {
        // Walking at full speed while "using an item" exceeds the 0.2 input multiplier bound.
        double walk = 0.2158;
        double bound = MovementPhysics.maxHorizontal(walk, true, 0.6, true, 0.6, 0.1, MovementPhysics.USE_ITEM_MULTIPLIER,
                false);
        double next = MovementPhysics.maxHorizontal(bound, true, 0.6, true, 0.6, 0.1,
                MovementPhysics.USE_ITEM_MULTIPLIER, false);
        assertTrue(walk > next + 0.025, "full walking speed is impossible after the first slowed tick: " + next);
    }
}
