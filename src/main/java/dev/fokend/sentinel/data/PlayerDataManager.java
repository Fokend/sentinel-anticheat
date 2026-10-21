package dev.fokend.sentinel.data;

import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PlayerDataManager {

    private final Map<UUID, PlayerData> dataMap = new ConcurrentHashMap<>();

    public PlayerData getData(Player player) {
        return dataMap.computeIfAbsent(player.getUniqueId(), PlayerData::new);
    }

    public void removeData(UUID uuid) {
        dataMap.remove(uuid);
    }

    public Collection<PlayerData> getAllData() {
        return dataMap.values();
    }

    public void clearAll() {
        dataMap.clear();
    }
}

