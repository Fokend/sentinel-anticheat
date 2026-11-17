package dev.fokend.sentinel.engine;

/**
 * Detects clients that run more than 20 ticks per second ("Timer"). Each received client tick adds
 * 50 ms of client time; real elapsed time is subtracted. A positive balance means the client is ahead.
 *
 * <p>Network lag makes ticks arrive late and then in a burst; the burst only repays the negative
 * balance built up during the delay. The negative balance is capped so a client cannot "bank" time
 * by holding packets back. Server lag spikes are handled by the caller via {@link #reset}.</p>
 */
public final class TimerBalance {

    private static final double TICK_MS = 50.0;

    private long lastNanos = -1;
    private double balanceMs;

    /**
     * @return the balance after this tick (milliseconds the client is ahead of real time)
     */
    public double onClientTick(long nowNanos, double maxLagMs) {
        if (lastNanos < 0) {
            lastNanos = nowNanos;
            return 0;
        }
        double elapsedMs = (nowNanos - lastNanos) / 1_000_000.0;
        lastNanos = nowNanos;
        balanceMs += TICK_MS - elapsedMs;
        if (balanceMs < -maxLagMs) {
            balanceMs = -maxLagMs;
        }
        return balanceMs;
    }

    public double balance() {
        return balanceMs;
    }

    /** Forget accumulated balance (after a violation, server lag spike, join or world change). */
    public void reset() {
        balanceMs = 0;
        lastNanos = -1;
    }

    /** Remove the excess after a violation so continued cheating is flagged again. */
    public void consume(double ms) {
        balanceMs -= ms;
    }
}
