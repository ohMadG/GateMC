package me.Evan52098.genscore.lagwatch;

public enum LagCheck {
    REDSTONE("redstone", "Redstone", "redstone changes", Kind.RATE, 3000),
    PISTONS("pistons", "Piston spam", "piston moves", Kind.RATE, 300),
    HOPPER_MOVES("hopper-moves", "Hopper chain", "item moves", Kind.RATE, 6000),
    DISPENSERS("dispensers", "Dispenser spam", "dispenses", Kind.RATE, 600),
    ITEM_DROPS("item-drops", "Item spam", "items dropped", Kind.RATE, 2000),
    FALLING_SPAWNS("falling-blocks", "Falling block machine", "falling blocks", Kind.RATE, 200),
    TNT("tnt", "TNT machine", "TNT primed", Kind.RATE, 80),
    MOB_SPAWNS("mob-spawns", "Mob spam", "mobs bred or hatched", Kind.RATE, 100),
    VEHICLE_SPAWNS("vehicle-spawns", "Minecart/boat spam", "minecarts or boats placed", Kind.RATE, 60),
    PORTALS("portals", "Portal loader", "entities through portals", Kind.RATE, 150),

    ITEMS("items", "Item pile", "items on the ground", Kind.COUNT, 600),
    VEHICLES("vehicles", "Minecart/boat pile", "minecarts and boats", Kind.COUNT, 80),
    ARMOR_STANDS("armor-stands", "Armor stand pile", "armor stands", Kind.COUNT, 100),
    ITEM_FRAMES("item-frames", "Item frame pile", "item frames", Kind.COUNT, 400),
    MOBS("mobs", "Mob pile", "mobs", Kind.COUNT, 250),
    VILLAGERS("villagers", "Villager pile", "villagers", Kind.COUNT, 80),
    PROJECTILES("projectiles", "Projectile pile", "arrows and projectiles", Kind.COUNT, 300),
    FALLING("falling-standing", "Falling block pile", "falling blocks in the air", Kind.COUNT, 150),
    HOPPERS("hoppers", "Hopper pile", "hoppers", Kind.COUNT, 600);

    public enum Kind {
        RATE,
        COUNT
    }

    static final int RATE_HITS = 3;
    static final int RATE_WINDOWS = 6;
    static final int COUNT_HITS = 2;

    private final String key;
    private final String label;
    private final String unit;
    private final Kind kind;
    private final int defaultLimit;

    LagCheck(String key, String label, String unit, Kind kind, int defaultLimit) {
        this.key = key;
        this.label = label;
        this.unit = unit;
        this.kind = kind;
        this.defaultLimit = defaultLimit;
    }

    public String key() {
        return this.key;
    }

    public String label() {
        return this.label;
    }

    public String describe(int value) {
        return String.format("%,d", value) + " " + this.unit + (this.kind == Kind.RATE ? " in 10s" : "");
    }

    public Kind kind() {
        return this.kind;
    }

    public int defaultLimit() {
        return this.defaultLimit;
    }

    int hitsNeeded() {
        return this.kind == Kind.RATE ? RATE_HITS : COUNT_HITS;
    }

    int windowMask() {
        return this.kind == Kind.RATE ? (1 << RATE_WINDOWS) - 1 : (1 << COUNT_HITS) - 1;
    }
}
