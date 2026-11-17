package dev.fokend.sentinel.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EngineTest {

    // ----------------------------------------------------------------- SwapTracker

    @Test
    void vanillaSwapThenHitIsAllowed() {
        SwapTracker t = new SwapTracker();
        t.onTickEnd(0);
        assertEquals(SwapTracker.Result.NONE, t.onSwap(0, 3));
        assertEquals(SwapTracker.Result.SWAP_BEFORE_ATTACK, t.onAttack(3));
        t.onTickEnd(3);
        // swapping back in the NEXT client tick is what a fast human does
        assertEquals(SwapTracker.Result.NONE, t.onSwap(3, 0));
    }

    @Test
    void autoAttributeSwapMacroIsDetected() {
        SwapTracker t = new SwapTracker();
        t.onTickEnd(0);
        t.onSwap(0, 3);
        t.onAttack(3);
        assertEquals(SwapTracker.Result.POST_ATTACK_SWAP, t.onSwap(3, 0));
        assertTrue(t.swappedBack());
    }

    @Test
    void hitThenSwapInSameTickIsDetectedEvenWithoutPriorSwap() {
        SwapTracker t = new SwapTracker();
        t.onTickEnd(2);
        assertEquals(SwapTracker.Result.NONE, t.onAttack(2));
        assertEquals(SwapTracker.Result.POST_ATTACK_SWAP, t.onSwap(2, 5));
        assertFalse(t.swappedBack());
    }

    @Test
    void tickEndResetsState() {
        SwapTracker t = new SwapTracker();
        t.onTickEnd(0);
        t.onAttack(0);
        t.onTickEnd(0);
        assertEquals(SwapTracker.Result.NONE, t.onSwap(0, 1));
    }

    // ----------------------------------------------------------------- TimerBalance

    private static final long MS = 1_000_000L;

    @Test
    void steadyTwentyTicksPerSecondStaysBalanced() {
        TimerBalance t = new TimerBalance();
        long now = 0;
        double max = 0;
        for (int i = 0; i < 2000; i++) {
            now += 50 * MS + (i % 2 == 0 ? 3 * MS : -3 * MS); // jitter
            max = Math.max(max, t.onClientTick(now, 1000));
        }
        assertTrue(max < 10, "max=" + max);
    }

    @Test
    void lagFollowedByBurstIsNotFlagged() {
        TimerBalance t = new TimerBalance();
        long now = 0;
        for (int i = 0; i < 100; i++) {
            now += 50 * MS;
            t.onClientTick(now, 1000);
        }
        now += 800 * MS; // 16 ticks delayed by the network ...
        double max = 0;
        for (int i = 0; i < 17; i++) { // ... then arrive at once
            max = Math.max(max, t.onClientTick(now, 1000));
        }
        for (int i = 0; i < 200; i++) {
            now += 50 * MS;
            max = Math.max(max, t.onClientTick(now, 1000));
        }
        assertTrue(max < 60, "max=" + max);
    }

    @Test
    void fastClientIsFlagged() {
        TimerBalance t = new TimerBalance();
        long now = 0;
        double balance = 0;
        int ticks = 0;
        while (balance < 200 && ticks < 1000) {
            now += 45 * MS; // ~22 ticks per second
            balance = t.onClientTick(now, 1000);
            ticks++;
        }
        assertTrue(balance >= 200, "detected after " + ticks + " ticks");
        assertTrue(ticks < 60);
    }

    @Test
    void bankedLagIsCapped() {
        TimerBalance t = new TimerBalance();
        t.onClientTick(0, 1000);
        t.onClientTick(10_000 * MS, 1000); // 10 s without ticks
        assertEquals(-1000, t.balance(), 1e-9, "the debt is capped at max-lag-ms");
    }

    // ----------------------------------------------------------------- PositionHistory

    @Test
    void historyWindowAndDistance() {
        PositionHistory h = new PositionHistory(40);
        for (int tick = 0; tick < 100; tick++) {
            double x = tick * 0.3; // target running along +X
            h.record(tick, x - 0.3, 0, -0.3, x + 0.3, 1.8, 0.3);
        }
        assertEquals(40, h.size());
        // attacker at x=26 sees the target from a few ticks ago (x=29.7 now, ~27 at tick 90)
        double now = h.minDistance(26, 1.6, 0, 99, 99, 0);
        double lagged = h.minDistance(26, 1.6, 0, 90, 99, 0);
        assertTrue(lagged < now, lagged + " < " + now);
        assertTrue(Double.isNaN(h.minDistance(0, 0, 0, 0, 10, 0)), "out of window");
        assertEquals(0, PositionHistory.distanceToBox(0, 0, 0, -1, -1, -1, 1, 1, 1), 1e-12);
        assertEquals(1, PositionHistory.distanceToBox(2, 0, 0, -1, -1, -1, 1, 1, 1), 1e-12);
    }
}
