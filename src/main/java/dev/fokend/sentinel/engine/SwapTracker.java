package dev.fokend.sentinel.engine;

/**
 * Tracks hotbar swaps and attacks inside one client tick (delimited by the client's
 * {@code client_tick_end} packet). Pure logic, no Bukkit types.
 *
 * <p>Within a single tick the vanilla client handles hotbar keys (and sends the pending slot change)
 * <em>before</em> it processes attack clicks. A slot change that arrives <em>after</em> an attack in the
 * same client tick therefore cannot be produced by an unmodified client: it is the signature of
 * auto attribute swap macros (swap to item B, hit, swap back to item A in one tick).</p>
 */
public final class SwapTracker {

    public enum Result { NONE, SWAP_BEFORE_ATTACK, POST_ATTACK_SWAP }

    private int slotAtTickStart = -1;
    private int swapsBeforeAttack;
    private int swapsAfterAttack;
    private boolean attacked;
    private int lastPostAttackSlot = -1;

    /** A {@code set_carried_item} packet was processed. */
    public Result onSwap(int previousSlot, int newSlot) {
        if (slotAtTickStart < 0) {
            slotAtTickStart = previousSlot;
        }
        if (attacked) {
            swapsAfterAttack++;
            lastPostAttackSlot = newSlot;
            return Result.POST_ATTACK_SWAP;
        }
        swapsBeforeAttack++;
        return Result.NONE;
    }

    /** An attack packet was processed. Returns whether the held item was swapped earlier in this tick. */
    public Result onAttack(int currentSlot) {
        if (slotAtTickStart < 0) {
            slotAtTickStart = currentSlot;
        }
        attacked = true;
        return swapsBeforeAttack > 0 ? Result.SWAP_BEFORE_ATTACK : Result.NONE;
    }

    /** True if the last post-attack swap went back to the slot held when the tick started (classic macro). */
    public boolean swappedBack() {
        return swapsAfterAttack > 0 && lastPostAttackSlot == slotAtTickStart;
    }

    public int swapsBeforeAttack() {
        return swapsBeforeAttack;
    }

    public int swapsAfterAttack() {
        return swapsAfterAttack;
    }

    public int slotAtTickStart() {
        return slotAtTickStart;
    }

    /** The client finished its tick. */
    public void onTickEnd(int currentSlot) {
        slotAtTickStart = currentSlot;
        swapsBeforeAttack = 0;
        swapsAfterAttack = 0;
        attacked = false;
        lastPostAttackSlot = -1;
    }
}
