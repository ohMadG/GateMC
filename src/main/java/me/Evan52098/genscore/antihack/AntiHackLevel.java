package me.Evan52098.genscore.antihack;

public enum AntiHackLevel {
    NONE("None", "&7", 0, 0),
    LOW("Low", "&e", 4, 2),
    MEDIUM("Medium", "&6", 8, 4),
    HIGH("High", "&c", 14, 7),
    EXTREME("Extreme", "&4", 20, 10);

    private final String label;
    private final String colour;
    private final int normalPoints;
    private final int strictPoints;

    AntiHackLevel(String label, String colour, int normalPoints, int strictPoints) {
        this.label = label;
        this.colour = colour;
        this.normalPoints = normalPoints;
        this.strictPoints = strictPoints;
    }

    public String label() {
        return this.label;
    }

    public String colour() {
        return this.colour;
    }

    public static AntiHackLevel of(int points, boolean strict) {
        AntiHackLevel result = NONE;
        for (AntiHackLevel level : values()) {
            if (level != NONE && points >= (strict ? level.strictPoints : level.normalPoints)) {
                result = level;
            }
        }
        return result;
    }

    public static AntiHackLevel parse(String text, AntiHackLevel fallback) {
        if (text != null) {
            for (AntiHackLevel level : values()) {
                if (level.name().equalsIgnoreCase(text.trim())) {
                    return level;
                }
            }
        }
        return fallback;
    }
}
