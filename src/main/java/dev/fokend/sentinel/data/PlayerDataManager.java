package dev.fokend.sentinel.data;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.entity.Player;

/** Data of online players only: created on join, removed on quit. Main thread only. */
public final class PlayerDataManager {

    private final Map<UUID, PlayerData> data = new HashMap<>();

    public PlayerData get(Player player) {
        return data.computeIfAbsent(player.getUniqueId(), PlayerData::new);
    }

    public PlayerData getIfPresent(UUID id) {
        return data.get(id);
    }

    public void remove(UUID id) {
        data.remove(id);
    }

    public Collection<PlayerData> all() {
        return data.values();
    }

    public int size() {
        return data.size();
    }

    public void clear() {
        data.clear();
    }
}
