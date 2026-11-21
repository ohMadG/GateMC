package me.Evan52098.genscore.powers;

import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.shards.ShardsModule;
import me.Evan52098.genscore.shards.ShardsService;
import me.Evan52098.genscore.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

final class PowersService {

    private static final Logger LOG = Logger.getLogger("GensCore");

    private final PowersModule m;
    private final Set<UUID> delivering = ConcurrentHashMap.newKeySet();

    PowersService(PowersModule m) {
        this.m = m;
    }

    void click(Player p, PowerDef def) {
        UUID uuid = p.getUniqueId();
        PowerData d = m.data(uuid);
        if (d == null) {
            p.sendMessage(Msg.bad("Your data is still loading, try again in a moment."));
            return;
        }
        if (d.owns(def.id())) {
            toggle(p, d, def);
            return;
        }
        if (!def.enabled()) {
            p.sendMessage(Msg.bad("That power is turned off right now."));
            return;
        }
        buy(p, d, def);
    }

    private void buy(Player p, PowerData d, PowerDef def) {
        UUID uuid = p.getUniqueId();
        boolean free = !def.op() && m.settings().firstFree() && !d.freeUsed();
        long price = free ? 0 : def.cost();
        String unit = def.op() ? "credits" : "tokens";
        if (def.op() && price <= 0) {
            p.sendMessage(Msg.bad("That super power isn't for sale. Check " + Msg.white("/store") + "."));
            return;
        }
        if (price > 0 && !take(uuid, def.op(), price, "power " + def.id())) {
            p.sendMessage(Msg.bad("You need " + Msg.white(fmt(price)) + " " + unit + " for this power (you have "
                    + Msg.white(fmt(balance(uuid, def.op()))) + ")."
                    + (def.op() ? " Get credits at " + Msg.white("/store") + "." : "")));
            return;
        }
        if (!d.addOwned(def.id())) {
            if (price > 0) {
                give(uuid, def.op(), price, "power refund " + def.id());
            }
            return;
        }
        if (free) {
            d.markFreeUsed();
        }
        d.setActive(def.id());
        m.players().saveSoon(uuid);
        LOG.info("[powers] " + p.getName() + " (" + uuid + ") bought " + def.id() + (free ? " (first power, free)" : " for " + price + " " + unit));
        p.sendMessage(Msg.good("You unlocked " + Msg.escape(Text.plain(def.name())) + (free ? " for free" : " for " + Msg.white(fmt(price)) + " " + unit)
                + ". It is now your active power." + (def.isActive() ? " Double-tap sneak to use it." : "")));
        m.runtime().refreshNow(p);
    }

    long balance(UUID uuid, boolean credits) {
        if (credits) {
            ShardsService s = ShardsModule.service();
            return s == null ? 0 : s.balance(uuid);
        }
        var plots = m.plugin().modules().get(me.Evan52098.genscore.plots.PlotsModule.class);
        var g = plots == null ? null : plots.gensPlayer(uuid);
        return g == null ? 0 : g.tokens();
    }

    private boolean take(UUID uuid, boolean credits, long amount, String reason) {
        if (credits) {
            ShardsService s = ShardsModule.service();
            return s != null && s.withdraw(uuid, amount, reason);
        }
        var plots = m.plugin().modules().get(me.Evan52098.genscore.plots.PlotsModule.class);
        var g = plots == null ? null : plots.gensPlayer(uuid);
        if (g == null || !g.takeTokens(amount)) {
            return false;
        }
        plots.saveSoon(uuid);
        return true;
    }

    private void give(UUID uuid, boolean credits, long amount, String reason) {
        if (credits) {
            ShardsService s = ShardsModule.service();
            if (s != null) {
                s.deposit(uuid, amount, reason);
            }
            return;
        }
        var plots = m.plugin().modules().get(me.Evan52098.genscore.plots.PlotsModule.class);
        var g = plots == null ? null : plots.gensPlayer(uuid);
        if (g != null) {
            g.addTokens(amount);
            plots.saveSoon(uuid);
        }
    }

    private void toggle(Player p, PowerData d, PowerDef def) {
        if (def.id().equals(d.active())) {
            d.setActive(null);
            p.sendMessage(Msg.neutral(Msg.escape(Text.plain(def.name())) + " is now off."));
        } else {
            if (!def.enabled()) {
                p.sendMessage(Msg.bad("That power is turned off right now."));
                return;
            }
            d.setActive(def.id());
            p.sendMessage(Msg.good(Msg.escape(Text.plain(def.name())) + " is now your active power."
                    + (def.isActive() ? " Double-tap sneak to use it." : "")));
        }
        m.players().saveSoon(p.getUniqueId());
        m.runtime().refreshNow(p);
    }

    void turnOff(Player p) {
        PowerData d = m.data(p.getUniqueId());
        if (d == null || d.active() == null) {
            p.sendMessage(Msg.neutral("You have no active power."));
            return;
        }
        d.setActive(null);
        m.players().saveSoon(p.getUniqueId());
        m.runtime().refreshNow(p);
        p.sendMessage(Msg.neutral("Your power is now off."));
    }

    CompletableFuture<Boolean> grant(UUID uuid, String powerId, String source) {
        PowerDef def = m.settings().power(powerId);
        if (def == null) {
            return CompletableFuture.completedFuture(false);
        }
        String id = def.id();
        return m.plugin().database().async(c -> {
            PowersSection.insertPending(c, uuid, id, source);
            return true;
        }).exceptionally(e -> {
            LOG.log(Level.WARNING, "[powers] Could not record a grant of " + id + " to " + uuid, e);
            return false;
        }).thenApply(ok -> {
            if (ok) {
                LOG.info("[powers] Granted " + id + " to " + uuid + " (" + source + ")");
                deliver(uuid);
            }
            return ok;
        });
    }

    void deliver(UUID uuid) {
        Player p = Bukkit.getPlayer(uuid);
        if (p == null || m.data(uuid) == null || !delivering.add(uuid)) {
            return;
        }
        m.plugin().database().async(c -> PowersSection.pending(c, uuid)).whenComplete((rows, error) -> {
            if (error != null || rows == null || rows.isEmpty()) {
                delivering.remove(uuid);
                return;
            }
            var task = p.getScheduler().run(m.plugin(), t -> {
                PowerData d = m.data(uuid);
                if (d == null) {
                    delivering.remove(uuid);
                    return;
                }
                for (PowersSection.Pending row : rows) {
                    PowerDef def = m.settings().power(row.power());
                    if (d.addOwned(row.power())) {
                        String name = def == null ? row.power() : Text.plain(def.name());
                        p.sendMessage(Msg.good("You unlocked the power " + Msg.white(name) + "! Open " + Msg.white("/powers") + " to use it."));
                        if (d.active() == null && def != null && def.enabled()) {
                            d.setActive(def.id());
                            m.runtime().refreshNow(p);
                        }
                    }
                }
                m.players().saveNow(uuid)
                        .thenCompose(v -> m.plugin().database().asyncRun(c -> PowersSection.deletePending(c, uuid, rows)))
                        .whenComplete((v, e) -> {
                            delivering.remove(uuid);
                            if (e != null) {
                                LOG.log(Level.WARNING, "[powers] Delivering grants to " + uuid + " not finished, will retry", e);
                            }
                        });
            }, () -> delivering.remove(uuid));
            if (task == null) {
                delivering.remove(uuid);
            }
        });
    }

    void sweepPending() {
        m.plugin().database().async(c -> {
            List<UUID> players = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT DISTINCT uuid FROM pvp_powers_pending LIMIT 500");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try {
                        players.add(UUID.fromString(rs.getString(1)));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
            return players;
        }).whenComplete((players, e) -> {
            if (e != null) {
                LOG.log(Level.WARNING, "[powers] Pending grant sweep failed", e);
                return;
            }
            for (UUID uuid : players) {
                if (Bukkit.getPlayer(uuid) != null) {
                    deliver(uuid);
                }
            }
        });
    }

    CompletableFuture<Boolean> revoke(UUID uuid, String powerId) {
        String id = powerId.toLowerCase(java.util.Locale.ROOT);
        boolean[] had = {false};
        return m.players().runSerialized(uuid, c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM pvp_powers_pending WHERE uuid = ? AND power = ?")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, id);
                if (ps.executeUpdate() > 0) {
                    had[0] = true;
                }
            }
            if (m.data(uuid) != null) {
                return;
            }
            Set<String> owned = new LinkedHashSet<>();
            String active = null;
            boolean free = false;
            String seen = "";
            boolean exists = false;
            try (PreparedStatement ps = c.prepareStatement("SELECT owned, active, free_used, seen_update FROM pvp_powers WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        exists = true;
                        owned.addAll(PowersSection.split(rs.getString(1)));
                        active = rs.getString(2);
                        free = rs.getInt(3) != 0;
                        seen = rs.getString(4);
                    }
                }
            }
            if (exists && owned.remove(id)) {
                had[0] = true;
                PowerData d = new PowerData(owned, id.equals(active) ? null : active, free, seen);
                PowersSection.write(c, uuid, d.snapshot());
            }
        }).thenApply(v -> {
            PowerData d = m.data(uuid);
            if (d != null && d.removeOwned(id)) {
                had[0] = true;
                m.players().saveSoon(uuid);
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) {
                    Tasks.entity.run(p, () -> m.runtime().refreshNow(p));
                }
            }
            LOG.info("[powers] Revoked " + id + " from " + uuid + (had[0] ? "" : " (did not have it)"));
            return had[0];
        });
    }

    static String fmt(long n) {
        return String.format("%,d", n);
    }
}
