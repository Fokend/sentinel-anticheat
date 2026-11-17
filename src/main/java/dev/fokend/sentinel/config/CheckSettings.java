package dev.fokend.sentinel.config;

import java.util.Map;

/**
 * Settings of one check.
 *
 * @param enabled     whether the check runs at all
 * @param buffer      the check flags once its buffer reaches this value (1 = flag on the first failure)
 * @param bufferDecay how much the buffer drops on every passing event
 * @param vl          violation level added per flag
 * @param kickVl      kick when the accumulated VL reaches this value; 0 disables kicking for this check
 * @param mitigate    cancel the action (hit / block / swap advantage) or set the player back
 * @param params      check-specific tuning values
 */
public record CheckSettings(boolean enabled, double buffer, double bufferDecay, double vl, double kickVl,
                            boolean mitigate, Map<String, Double> params) {

    public double param(String key) {
        Double v = params.get(key);
        if (v == null) {
            throw new IllegalArgumentException("Unknown parameter " + key);
        }
        return v;
    }
}
