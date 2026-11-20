package me.Evan52098.genscore.factory;

import me.Evan52098.genscore.keycrates.KeyCratesModule;
import me.Evan52098.genscore.plots.GensPlayer;
import me.Evan52098.genscore.plots.PlotData;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.util.Money;
import me.Evan52098.genscore.util.Msg;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Steady progress on your own plot: belt drops (tokens and caches), breaking caches, and milestones that pay tokens.
 *
 * <p>Tokens and caches are counts in {@link GensPlayer}, never items, so they cannot be duplicated; a cache is taken
 * from the count before its loot is given (checklist item 1).
 */
final class Rewards {

    private final FactoryModule module;

    Rewards(FactoryModule module) {
        this.module = module;
    }

    // ------------------------------------------------------------------ belt drops

    /** A token or a cache reached the sell point. Any thread. */
    void belt(UUID owner, int special) {
        GensPlayer g = module.gensPlayer(owner);
        if (g == null) {
            return;
        }
        String text;
        Sound sound;
        if (special == Factory.TOKEN) {
            g.addTokens(1);
            text = "<yellow>+1 token</yellow> <gray>came off your belt (" + g.tokens() + " now)</gray>";
            sound = Sound.ENTITY_EXPERIENCE_ORB_PICKUP;
        } else {
            int r = special - Factory.CACHE;
            g.addCache(r, 1);
            text = "A " + ProgressSettings.RARITY_COLORS[r] + "<bold>" + ProgressSettings.RARITY_NAMES[r] + " cache</bold></"
                    + ProgressSettings.RARITY_COLORS[r].substring(1) + " <gray>came off your belt. Break it with <white>/caches</white>.</gray>";
            sound = r >= 2 ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_PLAYER_LEVELUP;
        }
        module.saveSoon(owner);
        Player p = Bukkit.getPlayer(owner);
        if (p != null) {
            Tasks.entity.run(p, () -> {
                p.sendMessage(Msg.parse(text));
                p.playSound(p.getLocation(), sound, 0.8f, 1.3f);
            });
        }
    }

    // ------------------------------------------------------------------ caches

    /** About how much the player's factory makes in a minute now (for scaling cash rewards). */
    long incomePerMinute(UUID player) {
        PlotData data = module.plotData(player);
        GensPlayer g = module.gensPlayer(player);
        if (data == null) {
            return 5000;
        }
        FactorySettings s = module.settings();
        double boost = 1 + module.progress().value().perLevel() * (g == null ? 0 : g.valueLevel());
        double perMinute = s.itemValue(Math.max(1, data.beltLength()), data.genLevel()) * s.speed(data.speedLevel()) * 60 * boost;
        return Math.max(5000, Math.round(perMinute)); // at least $50
    }

    /** Breaks one cache of this rarity. Player thread. Returns false if they have none. */
    boolean open(Player p, int rarity) {
        GensPlayer g = module.gensPlayer(p.getUniqueId());
        if (g == null || !g.takeCache(rarity)) { // take first
            return false;
        }
        ProgressSettings ps = module.progress();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        long money = Math.round(ps.cacheMinutes()[rarity] * incomePerMinute(p.getUniqueId()) * (0.5 + r.nextDouble()));
        int[] t = ps.cacheTokens()[rarity];
        int tokens = t[0] + r.nextInt(Math.max(1, t[1] - t[0] + 1));
        List<String> lines = new ArrayList<>();
        module.pay(p.getUniqueId(), money);
        lines.add("<green>+" + Money.compact(money) + "</green>");
        g.addTokens(tokens);
        lines.add("<yellow>+" + tokens + " tokens</yellow>");
        if (r.nextDouble() < ps.keyChance()[rarity] && !ps.keyCrates().isEmpty()) {
            String crate = ps.keyCrates().get(r.nextInt(ps.keyCrates().size()));
            if (KeyCratesModule.giveKeys(p.getUniqueId(), crate, 1, "cache")) {
                lines.add("<aqua>+1 " + Msg.escape(crate) + " key</aqua>");
            }
        }
        // A small chance at a better cache inside the cache.
        if (rarity < 3 && r.nextDouble() < 0.08) {
            g.addCache(rarity + 1, 1);
            lines.add(ProgressSettings.RARITY_COLORS[rarity + 1] + "+1 " + ProgressSettings.RARITY_NAMES[rarity + 1] + " cache");
        }
        module.saveSoon(p.getUniqueId());
        String color = ProgressSettings.RARITY_COLORS[rarity];
        p.playSound(p.getLocation(), Sound.BLOCK_CHEST_LOCKED, 1f, 0.8f);
        p.showTitle(Title.title(Msg.parse(color + "<bold>Cracking...</bold>"), Msg.parse("<gray>" + ProgressSettings.RARITY_NAMES[rarity] + " cache"),
                Title.Times.times(Duration.ZERO, Duration.ofMillis(900), Duration.ZERO)));
        Tasks.entity.later(p, () -> {
            p.showTitle(Title.title(Msg.parse(color + "<bold>" + ProgressSettings.RARITY_NAMES[rarity] + " cache</bold>"),
                    Msg.parse(String.join(" <dark_gray>|</dark_gray> ", lines)),
                    Title.Times.times(Duration.ZERO, Duration.ofMillis(2500), Duration.ofMillis(500))));
            p.sendMessage(Msg.parse(color + ProgressSettings.RARITY_NAMES[rarity] + " cache: " + String.join("<gray>, </gray>", lines)));
            p.playSound(p.getLocation(), rarity >= 2 ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
            p.getWorld().spawnParticle(rarity >= 2 ? Particle.TOTEM_OF_UNDYING : Particle.HAPPY_VILLAGER,
                    p.getLocation().add(0, 1, 0), 40, 0.6, 0.8, 0.6, 0.2);
        }, 18);
        return true;
    }

    // ------------------------------------------------------------------ milestones

    private record Milestone(String id, String name, long tokens, int cacheRarity) {
    }

    /** Checks the owner's milestones and pays any newly reached. Any thread (data is synchronized). */
    void checkMilestones(Player owner, Factory f) {
        GensPlayer g = module.gensPlayer(owner.getUniqueId());
        PlotData data = module.plotData(owner.getUniqueId());
        if (g == null || data == null) {
            return;
        }
        List<Milestone> due = new ArrayList<>();
        int tier = data.genLevel() + 1;
        for (int n = 5; n <= module.settings().genTiers().size(); n += 5) {
            if (tier >= n) {
                due.add(new Milestone("tier" + n, "Gen tier " + n, 2 + n / 5, n % 25 == 0 ? (n >= 100 ? 2 : 1) : -1));
            }
        }
        if (f != null && data.beltLength() >= f.path.size()) {
            due.add(new Milestone("beltfull", "Full-length belt", 10, 1));
        }
        double mult = module.settings().speed(data.speedLevel()) / module.settings().startSpeed();
        for (int k : new int[]{2, 4, 6, 8, 10}) {
            if (mult >= k - 1e-9) {
                due.add(new Milestone("speed" + k, k + "x belt speed", 3L * k / 2, k == 10 ? 2 : -1));
            }
        }
        long dollars = g.lifetimeCents() / 100;
        long e = 1000;
        for (int i = 3; e > 0 && e <= dollars; i++, e *= 10) {
            due.add(new Milestone("earn" + i, "Earned " + Money.compact(e * 100) + " in total", 2L * (i - 2), i % 3 == 0 ? 1 : -1));
            if (e > Long.MAX_VALUE / 10) {
                break;
            }
        }
        for (int n : new int[]{1, 10, 50, 100, 500}) {
            if (g.raidsWon() >= n) {
                due.add(new Milestone("raids" + n, n + (n == 1 ? " raid" : " raids"), 3L + n / 5, n >= 50 ? 2 : -1));
            }
            if (g.raidsDefended() >= n) {
                due.add(new Milestone("defended" + n, n + (n == 1 ? " raider stopped" : " raiders stopped"), 3L + n / 5, n >= 10 ? 1 : -1));
            }
        }
        var pd = module.plugin().playerData().get(owner.getUniqueId());
        if (pd != null) {
            int hours = (int) (pd.playtimeMs() / 3_600_000L);
            if (hours > g.hoursRewarded()) {
                int newHours = hours - g.hoursRewarded();
                g.setHoursRewarded(hours);
                g.addTokens(5L * newHours);
                Tasks.entity.run(owner, () -> owner.sendMessage(Msg.parse("<yellow>+" + (5 * newHours) + " tokens</yellow> <gray>for "
                        + newHours + (newHours == 1 ? " hour" : " hours") + " played.</gray>")));
            }
        }
        boolean any = false;
        for (Milestone m : due) {
            if (!g.claimMilestone(m.id())) {
                continue;
            }
            any = true;
            g.addTokens(m.tokens());
            String cache = "";
            if (m.cacheRarity() >= 0) {
                g.addCache(m.cacheRarity(), 1);
                cache = " <gray>+</gray> " + ProgressSettings.RARITY_COLORS[m.cacheRarity()] + "1 " + ProgressSettings.RARITY_NAMES[m.cacheRarity()] + " cache";
            }
            String line = "<gold><bold>MILESTONE</bold></gold> <white>" + Msg.escape(m.name()) + "</white> <yellow>+" + m.tokens() + " tokens</yellow>" + cache;
            Tasks.entity.run(owner, () -> {
                owner.sendMessage(Msg.parse(line));
                owner.playSound(owner.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
            });
        }
        if (any) {
            module.saveSoon(owner.getUniqueId());
        }
    }

    // ------------------------------------------------------------------ defence

    /** A turret finished off a raider: the owner defended their factory. */
    void turretKill(UUID owner, Player raider) {
        GensPlayer g = module.gensPlayer(owner);
        if (g != null) {
            g.addRaidDefended();
            g.addTokens(3);
            module.saveSoon(owner);
        }
        Player o = Bukkit.getPlayer(owner);
        String name = raider.getName();
        if (o != null) {
            Tasks.entity.run(o, () -> {
                o.sendMessage(Msg.good("Your turrets stopped " + Msg.white(name) + ". " + Msg.white("+3 tokens")));
                o.playSound(o.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.5f);
            });
        }
        raider.sendMessage(Msg.bad("You were stopped by the factory's turrets."));
    }
}
