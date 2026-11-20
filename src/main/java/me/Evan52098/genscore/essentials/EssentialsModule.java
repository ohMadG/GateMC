package me.Evan52098.genscore.essentials;

import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.module.GensModule;
import me.Evan52098.genscore.storage.Migrations;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;

/** The everyday commands that replace EssentialsX. */
public final class EssentialsModule implements GensModule {

    private SocialCommands social;

    @Override
    public String id() {
        return "essentials";
    }

    @Override
    public void enable(GensCore plugin) throws Exception {
        Migrations.apply(plugin.database(), id(), plugin.getLogger(),
                "CREATE TABLE IF NOT EXISTS pvp_social ("
                        + "uuid TEXT PRIMARY KEY, nickname TEXT, nick_changed_at BIGINT NOT NULL DEFAULT 0, "
                        + "msg_enabled INTEGER NOT NULL DEFAULT 1, social_spy INTEGER NOT NULL DEFAULT 0);"
                        + "CREATE TABLE IF NOT EXISTS pvp_ignores ("
                        + "uuid TEXT NOT NULL, ignored TEXT NOT NULL, PRIMARY KEY (uuid, ignored))");

        ConfigurationSection c = plugin.getConfig().getConfigurationSection("essentials");
        if (c == null) {
            c = new MemoryConfiguration();
        }
        Social.Section section = new Social.Section();
        plugin.playerData().register(section);

        social = new SocialCommands(plugin.playerData(), section,
                Math.max(1, c.getInt("nickname.min-length", 3)),
                Math.max(1, c.getInt("nickname.max-length", 16)),
                Math.max(0, c.getLong("nickname.cooldown-seconds", 3600)),
                Math.max(0, c.getLong("afk.auto-minutes", 5)),
                c.getStringList("rules"));
        ItemCommands items = new ItemCommands(c.getLong("fix.cooldown-seconds", 300));
        PlayerStateCommands state = new PlayerStateCommands(
                c.getBoolean("heal.extinguish", true),
                c.getBoolean("heal.clear-effects", false),
                c.getDouble("speed.min", 0.1),
                c.getDouble("speed.max", 10));

        plugin.commands().register(state, items, social);
        Bukkit.getPluginManager().registerEvents(items, plugin);
        Bukkit.getPluginManager().registerEvents(social, plugin);
    }

    /** For the chat module: ignores and AFK state. */
    public SocialCommands social() {
        return social;
    }
}
