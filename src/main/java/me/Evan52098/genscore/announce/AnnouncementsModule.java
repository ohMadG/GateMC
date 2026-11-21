package me.Evan52098.genscore.announce;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.module.GensModule;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.util.Msg;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.annotation.Named;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.bukkit.annotation.CommandPermission;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Timed announcements: a rotation of messages from config.yml, sent every N seconds. A simpler port of
 * BlockvilleCore's AnnouncementService (its admin menu is replaced by editing config.yml and /announce reload).
 * Players with genscore.announcements.hide do not get them.
 */
public final class AnnouncementsModule implements GensModule {

    private GensCore plugin;
    private final Map<String, List<String>> messages = new LinkedHashMap<>();
    private final AtomicInteger next = new AtomicInteger();
    private volatile boolean random;
    private ScheduledTask task;

    @Override
    public String id() {
        return "announcements";
    }

    @Override
    public void enable(GensCore plugin) {
        this.plugin = plugin;
        load();
        plugin.commands().register(this);
    }

    private synchronized void load() {
        if (task != null) {
            task.cancel();
        }
        messages.clear();
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("announcements");
        if (c == null) {
            return;
        }
        random = c.getBoolean("random", false);
        ConfigurationSection list = c.getConfigurationSection("messages");
        if (list != null) {
            for (String id : list.getKeys(false)) {
                List<String> lines = list.isList(id) ? list.getStringList(id) : List.of(list.getString(id, ""));
                if (!lines.isEmpty()) {
                    messages.put(id, lines);
                }
            }
        }
        long interval = Math.max(10, c.getLong("interval-seconds", 300));
        if (!messages.isEmpty()) {
            task = Tasks.async.timer(t -> sendNext(), interval, interval, TimeUnit.SECONDS);
        }
    }

    private void sendNext() {
        List<List<String>> all;
        synchronized (this) {
            all = new ArrayList<>(messages.values());
        }
        if (all.isEmpty()) {
            return;
        }
        int index = random ? ThreadLocalRandom.current().nextInt(all.size()) : Math.floorMod(next.getAndIncrement(), all.size());
        send(all.get(index));
    }

    private void send(List<String> lines) {
        List<Component> parsed = lines.stream().map(Msg::parse).toList();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.hasPermission("genscore.announcements.hide")) {
                Tasks.onEntity(p, () -> parsed.forEach(p::sendMessage));
            }
        }
    }

    @Command("announce reload")
    @CommandPermission("genscore.announcements.admin")
    @Description("Reload announcements from config.yml")
    public void reload(BukkitCommandActor actor) {
        plugin.reloadConfig();
        load();
        actor.sender().sendMessage(Msg.good("Announcements reloaded (" + messages.size() + " messages)."));
    }

    @Command("announce send")
    @CommandPermission("genscore.announcements.admin")
    @Description("Send one announcement now")
    public void sendNow(BukkitCommandActor actor, @Named("id") String id) {
        List<String> lines;
        synchronized (this) {
            lines = messages.get(id);
        }
        if (lines == null) {
            actor.sender().sendMessage(Msg.bad("Announcement " + Msg.white(id) + " not found."));
            return;
        }
        send(lines);
        actor.sender().sendMessage(Msg.good("Sent announcement: " + Msg.white(id) + "."));
    }

    @Command("announce list")
    @CommandPermission("genscore.announcements.admin")
    public void list(BukkitCommandActor actor) {
        actor.sender().sendMessage(messages.isEmpty() ? Msg.neutral("No announcements are set.")
                : Msg.neutral("Announcements: " + Msg.white(String.join(", ", messages.keySet()))));
    }

    @Override
    public void disable() {
        if (task != null) {
            task.cancel();
        }
    }

    public java.util.List<String> announcementIds() {
        synchronized (messages) {
            return java.util.List.copyOf(messages.keySet());
        }
    }
}
