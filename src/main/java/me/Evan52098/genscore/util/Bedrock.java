package me.Evan52098.genscore.util;

import org.bukkit.entity.Player;

public final class Bedrock {

    private Bedrock() {
    }

    public static boolean is(Player p) {
        return p.getUniqueId().getMostSignificantBits() == 0L || p.getName().startsWith(".");
    }
}
