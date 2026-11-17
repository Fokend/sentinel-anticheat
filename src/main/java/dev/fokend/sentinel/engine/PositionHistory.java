package dev.fokend.sentinel.engine;

/**
 * Fixed-size ring buffer of a player's bounding boxes per server tick, used for lag-compensated reach
 * checks. Stores primitives only (no Bukkit objects, no per-tick allocation).
 */
public final class PositionHistory {

    private final int capacity;
    private final double[] boxes; // 6 doubles per entry: minX, minY, minZ, maxX, maxY, maxZ
    private final int[] ticks;
    private int head;
    private int size;

    public PositionHistory(int capacity) {
        this.capacity = capacity;
        this.boxes = new double[capacity * 6];
        this.ticks = new int[capacity];
    }

    public void record(int serverTick, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        int i = head * 6;
        boxes[i] = minX;
        boxes[i + 1] = minY;
        boxes[i + 2] = minZ;
        boxes[i + 3] = maxX;
        boxes[i + 4] = maxY;
        boxes[i + 5] = maxZ;
        ticks[head] = serverTick;
        head = (head + 1) % capacity;
        if (size < capacity) {
            size++;
        }
    }

    public void clear() {
        size = 0;
        head = 0;
    }

    public int size() {
        return size;
    }

    /**
     * Smallest distance from a point to any recorded box (inflated by {@code margin}) recorded at a server
     * tick in {@code [fromTick, toTick]}. Returns {@link Double#NaN} if no entry is in range.
     */
    public double minDistance(double px, double py, double pz, int fromTick, int toTick, double margin) {
        double best = Double.NaN;
        for (int n = 0; n < size; n++) {
            int idx = Math.floorMod(head - 1 - n, capacity);
            int t = ticks[idx];
            if (t < fromTick || t > toTick) {
                continue;
            }
            int i = idx * 6;
            double d = distanceToBox(px, py, pz, boxes[i] - margin, boxes[i + 1] - margin, boxes[i + 2] - margin,
                    boxes[i + 3] + margin, boxes[i + 4] + margin, boxes[i + 5] + margin);
            if (Double.isNaN(best) || d < best) {
                best = d;
            }
        }
        return best;
    }

    /** Euclidean distance from a point to the closest point of an axis-aligned box (0 if inside). */
    public static double distanceToBox(double px, double py, double pz, double minX, double minY, double minZ,
                                       double maxX, double maxY, double maxZ) {
        double dx = Math.max(Math.max(minX - px, 0), px - maxX);
        double dy = Math.max(Math.max(minY - py, 0), py - maxY);
        double dz = Math.max(Math.max(minZ - pz, 0), pz - maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
