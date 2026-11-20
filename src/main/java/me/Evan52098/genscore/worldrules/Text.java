package me.Evan52098.genscore.worldrules;

import me.Evan52098.genscore.scheduler.Tasks;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Small helpers shared by the group K modules: legacy {@code &} text from config, and broadcasts done per player. */
public final class Text {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private Text() {
    }

    public static Component legacy(String text) {
        return LEGACY.deserialize(text == null ? "" : text);
    }

    /** Sends to every online player on that player's own thread (Folia rule 1), and to the console. */
    public static void broadcast(Component message) {
        Bukkit.getConsoleSender().sendMessage(message);
        for (Player p : Bukkit.getOnlinePlayers()) {
            Tasks.onEntity(p, () -> p.sendMessage(message));
        }
    }

    /** "5 minutes", "1 minute", "30 seconds". */
    public static String duration(long seconds) {
        if (seconds >= 3600 && seconds % 3600 == 0) {
            long h = seconds / 3600;
            return h + (h == 1 ? " hour" : " hours");
        }
        if (seconds >= 60 && seconds % 60 == 0) {
            long m = seconds / 60;
            return m + (m == 1 ? " minute" : " minutes");
        }
        if (seconds >= 60) {
            return (seconds / 60) + "m " + (seconds % 60) + "s";
        }
        return seconds + (seconds == 1 ? " second" : " seconds");
    }
}
