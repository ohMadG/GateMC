package me.Evan52098.genscore.chat;

import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.cacheddata.CachedMetaData;
import org.bukkit.entity.Player;

/** Reads a player's LuckPerms prefix and suffix. Only loaded when LuckPerms is installed. Safe from any thread. */
final class LuckPermsPrefix {

    private LuckPermsPrefix() {
    }

    static String prefix(Player player) {
        CachedMetaData meta = LuckPermsProvider.get().getPlayerAdapter(Player.class).getMetaData(player);
        String p = meta.getPrefix();
        return p == null ? "" : p;
    }

    static String suffix(Player player) {
        CachedMetaData meta = LuckPermsProvider.get().getPlayerAdapter(Player.class).getMetaData(player);
        String s = meta.getSuffix();
        return s == null ? "" : s;
    }
}
