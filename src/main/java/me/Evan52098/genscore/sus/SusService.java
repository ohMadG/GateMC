package me.Evan52098.genscore.sus;

import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.util.Cooldowns;
import me.Evan52098.genscore.util.Msg;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Flags from the /sus checks. In memory only (cleared on restart, like HxCore's list). Flags never punish: they ping
 * staff with {@code genscore.sus} (at most once per player per check per cooldown) and are listed by /sus.
 */
final class SusService {

    static final String PERM = "genscore.sus";
    static final String PERM_TP = "genscore.sus.tp";

    enum Check {
        AUTO_CLICKER("Auto-clicker"),
        AUTO_CRYSTAL("Auto-crystal"),
        AUTO_TOTEM("Auto-totem"),
        NUKER("Nuker");

        final String label;

        Check(String label) {
            this.label = label;
        }
    }

    /** One player's flags. Synchronized: flagged from region threads, read by commands. */
    static final class Record {
        final UUID uuid;
        volatile String name;
        private final Map<Check, Integer> counts = new EnumMap<>(Check.class);
        private final Map<Check, String> lastDetail = new EnumMap<>(Check.class);
        private long lastAt;

        Record(UUID uuid, String name) {
            this.uuid = uuid;
            this.name = name;
        }

        synchronized void add(Check check, String detail) {
            counts.merge(check, 1, Integer::sum);
            lastDetail.put(check, detail);
            lastAt = System.currentTimeMillis();
        }

        synchronized int total() {
            return counts.values().stream().mapToInt(Integer::intValue).sum();
        }

        synchronized long lastAt() {
            return lastAt;
        }

        synchronized Map<Check, Integer> counts() {
            return new EnumMap<>(counts);
        }

        synchronized Map<Check, String> details() {
            return new EnumMap<>(lastDetail);
        }
    }

    private final Map<UUID, Record> records = new ConcurrentHashMap<>();
    private final Set<UUID> alertsOff = ConcurrentHashMap.newKeySet();
    private final Cooldowns alertCooldown = new Cooldowns();
    private final Logger log;
    private volatile SusSettings settings;

    SusService(SusSettings settings, Logger log) {
        this.settings = settings;
        this.log = log;
    }

    SusSettings settings() {
        return settings;
    }

    void settings(SusSettings settings) {
        this.settings = settings;
    }

    /** Records a flag and pings staff. Safe from any thread. */
    void flag(Player suspect, Check check, String detail) {
        if (suspect.hasPermission("genscore.sus.exempt")) {
            return;
        }
        Record r = records.computeIfAbsent(suspect.getUniqueId(), id -> new Record(id, suspect.getName()));
        r.name = suspect.getName();
        r.add(check, detail);
        if (!alertCooldown.tryStart(suspect.getUniqueId(), check.name(), settings.alertCooldownMillis())) {
            return;
        }
        log.info("[sus] " + suspect.getName() + " flagged: " + check.label + " (" + detail + ")");
        Component base = Msg.parse(settings.prefix() + "<white>" + Msg.escape(suspect.getName()) + "</white> <gray>flagged for</gray> <red>"
                + check.label + "</red> <dark_gray>(" + Msg.escape(detail) + ")");
        Component withTp = base.append(Component.text(" ")).append(tpButton(suspect.getName()));
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission(PERM) && !alertsOff.contains(staff.getUniqueId())) {
                Component line = staff.hasPermission(PERM_TP) ? withTp : base;
                Tasks.onEntity(staff, () -> staff.sendMessage(line));
            }
        }
    }

    static Component tpButton(String name) {
        return Msg.parse("<aqua>[TP]")
                .clickEvent(ClickEvent.runCommand("/sustp " + name))
                .hoverEvent(HoverEvent.showText(Msg.parse("<gray>Teleport to <white>" + Msg.escape(name))));
    }

    /** Flagged players, most recent first. */
    List<Record> list() {
        List<Record> list = new ArrayList<>(records.values());
        list.sort(Comparator.comparingLong(Record::lastAt).reversed());
        return list;
    }

    Record find(String name) {
        for (Record r : records.values()) {
            if (r.name.equalsIgnoreCase(name)) {
                return r;
            }
        }
        return null;
    }

    boolean clear(String name) {
        Record r = find(name);
        return r != null && records.remove(r.uuid, r);
    }

    int clearAll() {
        int n = records.size();
        records.clear();
        return n;
    }

    /** Toggles alerts for a staff member; returns true when alerts are now on. */
    boolean toggleAlerts(UUID staff) {
        if (alertsOff.remove(staff)) {
            return true;
        }
        alertsOff.add(staff);
        return false;
    }
}
