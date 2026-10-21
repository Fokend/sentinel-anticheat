package dev.fokend.sentinel.data;

public enum CheckType {
    REACH("Reach", "Превышена дистанция удара", 6.0),
    HITBOX("HitBoxes", "Удар мимо серверного хитбокса цели", 5.0),
    AUTO_ATTRIBUTE_SWAP("AutoSwap", "Макрос/чит мгновенной смены оружия при ударе", 4.0),
    MULTI_AURA("Killaura/Multi", "Атака нескольких целей в один тик", 3.0),
    FLY("Fly", "Невалидная гравитация или зависание в воздухе", 8.0),
    SPEED("Speed", "Превышение лимита горизонтальной скорости", 8.0),
    NOSLOW("NoSlow", "Отсутствие замедления при использовании предмета", 6.0),
    AIR_PLACE("AirPlace", "Попытка поставить блок без опорной грани", 4.0),
    BAD_PACKETS("BadPackets", "Невозможные углы ротации или частота действий", 2.0),
    SCAFFOLD("Scaffold", "Аномальная установка блоков под себя на скорости", 6.0);

    private final String displayName;
    private final String description;
    private final double punishThreshold;

    CheckType(String displayName, String description, double punishThreshold) {
        this.displayName = displayName;
        this.description = description;
        this.punishThreshold = punishThreshold;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public double getPunishThreshold() {
        return punishThreshold;
    }
}

