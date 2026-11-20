package me.Evan52098.genscore.antihack;

public enum AntiHackCheck {
    FLY_HOVER("Fly (Hover)", 1, true),
    FLY_RISE("Fly (Rise)", 2, true),
    SPEED("Speed", 2, true),
    REACH("Reach", 2, false),
    AUTO_CLICKER_CPS("Auto clicker (CPS)", 2, false),
    AUTO_CLICKER_PATTERN("Auto clicker (pattern)", 2, false),
    KILL_AURA_MULTI("Kill aura (multi-target)", 3, false),
    KILL_AURA_NO_SWING("Kill aura (no swing)", 2, false),
    KILL_AURA_ANGLE("Kill aura (angle)", 2, false),
    HITBOX("Hitbox", 2, false),
    CRITICALS("Criticals", 3, false),
    VELOCITY("Velocity", 2, false),
    AUTO_BLOCK("Auto block", 3, false),
    AIM_SNAP("Aim (snap)", 2, false),
    JESUS("Jesus", 3, true),
    STEP("Step", 2, true),
    AIR_JUMP("Air jump", 3, true),
    NO_SLOW("No slow", 2, true),
    SPRINT("Sprint", 2, true),
    STRAFE("Strafe", 2, true),
    WALL_CLIMB("Wall climb", 3, true),
    FAST_CLIMB("Fast climb", 2, true),
    TIMER("Timer", 3, false),
    BOAT_FLY("Boat fly", 3, false),
    ENTITY_SPEED("Entity speed", 3, false),
    ELYTRA("Elytra", 3, false),
    FAST_BREAK("Fast break", 2, false),
    FAST_PLACE("Fast place", 2, false),
    FAST_USE("Fast use", 2, false),
    SCAFFOLD("Scaffold", 3, false),
    TOWER("Tower", 3, false),
    GROUND_SPOOF("Ground spoof", 3, true),
    INVALID("Invalid", 2, false),
    INVENTORY("Inventory", 2, false),
    PING_SPOOF("Ping spoof", 1, false),
    BAD_PACKETS("Bad packets", 2, false),
    CRASH("Crash attempt", 4, false),
    HACKED_CLIENT("Hacked client", 4, false),
    AIM_ROBOTIC("Aim (robotic)", 2, false),
    AUTO_CLICKER_VARIANCE("Auto clicker (variance)", 2, false),
    INTERACT_AUTO_CLICKER_CPS("Interact auto clicker (CPS)", 2, false),
    INTERACT_AUTO_CLICKER_PATTERN("Interact auto clicker (pattern)", 2, false),
    INTERACT_AUTO_CLICKER_VARIANCE("Interact auto clicker (variance)", 2, false);

    private final String label;
    private final int weight;
    private final boolean movement;

    AntiHackCheck(String label, int weight, boolean movement) {
        this.label = label;
        this.weight = weight;
        this.movement = movement;
    }

    public String label() {
        return this.label;
    }

    public int weight() {
        return this.weight;
    }

    public boolean isMovement() {
        return this.movement;
    }
}
