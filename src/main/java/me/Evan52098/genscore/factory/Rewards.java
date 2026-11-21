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
            int got = java.util.concurrent.ThreadLocalRandom.current().nextDouble()
                    < me.Evan52098.genscore.powers.PowersModule.perk(owner, "token-double") ? 2 : 1;
            got = (int) RankPerks.tokens(owner, got);
            g.addTokens(got);
            text = "<yellow>+" + got + (got == 1 ? " token" : " tokens") + "</yellow> <gray>came off your belt (" + g.tokens() + " now)</gray>";
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

    /** What came out of a cache: chat lines, and the icon of the best part (where the reel stops). */
    record Loot(List<String> lines, org.bukkit.Material icon, String iconName) {
    }

    /** Breaks one cache of this rarity with titles (used for breaking several at once). Player thread. */
    boolean open(Player p, int rarity) {
        Loot loot = roll(p, rarity);
        if (loot == null) {
            return false;
        }
        showTitles(p, rarity, loot.lines());
        return true;
    }

    /**
     * Breaks one cache with a spinning reel: a row of prizes scrolls past, slows down and stops on what came out.
     * The loot is given before the reel starts, so closing the menu early loses nothing. Player thread.
     */
    boolean openAnimated(Player p, int rarity) {
        Loot loot = roll(p, rarity);
        if (loot == null) {
            return false;
        }
        String color = ProgressSettings.RARITY_COLORS[rarity];
        me.Evan52098.genscore.menu.Menu menu = new me.Evan52098.genscore.menu.Menu(3,
                Msg.parse("<dark_gray>Opening " + ProgressSettings.RARITY_NAMES[rarity] + " cache"));
        org.bukkit.Material[] pool = {org.bukkit.Material.GOLD_INGOT, org.bukkit.Material.SUNFLOWER, org.bukkit.Material.TRIPWIRE_HOOK,
                org.bukkit.Material.EMERALD, org.bukkit.Material.DIAMOND, org.bukkit.Material.CHEST, org.bukkit.Material.GOLD_NUGGET,
                org.bukkit.Material.ENDER_CHEST, org.bukkit.Material.AMETHYST_SHARD};
        int finalOffset = 26;
        org.bukkit.Material[] strip = new org.bukkit.Material[finalOffset + 9];
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < strip.length; i++) {
            strip[i] = pool[r.nextInt(pool.length)];
        }
        strip[finalOffset + 4] = loot.icon();
        org.bukkit.Material frame = rarity == 0 ? org.bukkit.Material.WHITE_STAINED_GLASS_PANE : rarity == 1 ? org.bukkit.Material.LIGHT_BLUE_STAINED_GLASS_PANE
                : rarity == 2 ? org.bukkit.Material.MAGENTA_STAINED_GLASS_PANE : org.bukkit.Material.ORANGE_STAINED_GLASS_PANE;
        for (int i = 0; i < 27; i++) {
            if (i < 9 || i >= 18) {
                menu.set(i, pane(i == 4 || i == 22 ? org.bukkit.Material.HOPPER : org.bukkit.Material.GRAY_STAINED_GLASS_PANE, " "), null);
            }
        }
        menu.open(p);
        spin(p, menu, strip, 0, finalOffset, rarity, loot, frame, color);
        return true;
    }

    private void spin(Player p, me.Evan52098.genscore.menu.Menu menu, org.bukkit.Material[] strip, int offset, int finalOffset,
                      int rarity, Loot loot, org.bukkit.Material frame, String color) {
        if (!p.isOnline()) {
            return;
        }
        for (int i = 0; i < 9; i++) {
            menu.set(9 + i, pane(strip[offset + i], " "), null);
        }
        if (offset >= finalOffset) {
            // Landed: light the frame in the rarity's colour and show the prize.
            for (int i = 0; i < 27; i++) {
                if (i < 9 || i >= 18) {
                    menu.set(i, pane(frame, " "), null);
                }
            }
            org.bukkit.inventory.ItemStack prize = me.Evan52098.genscore.menu.Lore.item(loot.icon(), "&f" + loot.iconName(),
                    loot.lines().stream().map(l -> "&7" + net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                            .serialize(Msg.parse(l))).toList());
            menu.set(13, prize, null);
            showTitles(p, rarity, loot.lines());
            return;
        }
        float pitch = 0.6f + 1.2f * offset / finalOffset;
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, pitch);
        // Fast at first, slowing down for the last few steps.
        int left = finalOffset - offset;
        long delay = left > 12 ? 1 : left > 6 ? 2 : left > 3 ? 4 : 7;
        Tasks.entity.later(p, () -> spin(p, menu, strip, offset + 1, finalOffset, rarity, loot, frame, color), delay);
    }

    private static org.bukkit.inventory.ItemStack pane(org.bukkit.Material m, String name) {
        return me.Evan52098.genscore.menu.Lore.item(m, name, List.of());
    }

    /** Takes one cache and gives what is in it. Returns null if they have none. Player thread. */
    Loot roll(Player p, int rarity) {
        GensPlayer g = module.gensPlayer(p.getUniqueId());
        if (g == null || !g.takeCache(rarity)) { // take first
            return null;
        }
        me.Evan52098.genscore.hub.HubModule.progress(p.getUniqueId(), "CACHE", 1);
        ProgressSettings ps = module.progress();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        long money = Math.round(ps.cacheMinutes()[rarity] * incomePerMinute(p.getUniqueId()) * (0.5 + r.nextDouble())
                * (1 + me.Evan52098.genscore.powers.PowersModule.perk(p.getUniqueId(), "cache-money")));
        int[] t = ps.cacheTokens()[rarity];
        int tokens = (int) RankPerks.tokens(p.getUniqueId(), t[0] + r.nextInt(Math.max(1, t[1] - t[0] + 1)));
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
        String egg = me.Evan52098.genscore.pets.PetsModule.cacheEgg(p.getUniqueId(), rarity);
        if (egg != null) {
            lines.add(egg);
        }
        org.bukkit.Material icon = org.bukkit.Material.GOLD_INGOT;
        String iconName = Money.compact(money);
        if (lines.size() > 2) {
            icon = org.bukkit.Material.TRIPWIRE_HOOK;
            iconName = "Crate key";
        }
        // A small chance at a better cache inside the cache.
        if (rarity < 3 && r.nextDouble() < 0.08 + me.Evan52098.genscore.powers.PowersModule.perk(p.getUniqueId(), "cache-upgrade")) {
            g.addCache(rarity + 1, 1);
            lines.add(ProgressSettings.RARITY_COLORS[rarity + 1] + "+1 " + ProgressSettings.RARITY_NAMES[rarity + 1] + " cache");
            icon = org.bukkit.Material.ENDER_CHEST;
            iconName = ProgressSettings.RARITY_NAMES[rarity + 1] + " cache";
        }
        module.saveSoon(p.getUniqueId());
        return new Loot(List.copyOf(lines), icon, iconName);
    }

    private void showTitles(Player p, int rarity, List<String> lines) {
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
            if (rarity >= 2) {
                module.celebrate(p, color + "<bold>" + ProgressSettings.RARITY_NAMES[rarity].toUpperCase() + " CACHE",
                        String.join(" <dark_gray>|</dark_gray> ", lines), true);
            }
        }, 18);
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
                long earned = RankPerks.tokens(owner.getUniqueId(), (2L + RankPerks.of(owner.getUniqueId()).hourlyTokens()) * newHours);
                g.addTokens(earned);
                Tasks.entity.run(owner, () -> owner.sendMessage(Msg.parse("<yellow>+" + earned + " tokens</yellow> <gray>for "
                        + newHours + (newHours == 1 ? " hour" : " hours") + " played.</gray>")));
            }
        }
        boolean any = false;
        for (Milestone m : due) {
            if (!g.claimMilestone(m.id())) {
                continue;
            }
            any = true;
            g.addTokens(RankPerks.tokens(owner.getUniqueId(), m.tokens()));
            String cache = "";
            if (m.cacheRarity() >= 0) {
                g.addCache(m.cacheRarity(), 1);
                cache = " <gray>+</gray> " + ProgressSettings.RARITY_COLORS[m.cacheRarity()] + "1 " + ProgressSettings.RARITY_NAMES[m.cacheRarity()] + " cache";
            }
            String line = Msg.headerText("Milestone") + "<white>" + Msg.escape(m.name()) + "</white> <yellow>+" + m.tokens() + " tokens</yellow>" + cache;
            Tasks.entity.run(owner, () -> {
                owner.sendMessage(Msg.parse(line));
                module.celebrate(owner, "<gold><bold>MILESTONE", "<white>" + Msg.escape(m.name()) + " <yellow>+" + m.tokens() + " tokens", true);
            });
        }
        if (any) {
            module.saveSoon(owner.getUniqueId());
        }
    }

    // ------------------------------------------------------------------ defence

    /** A turret finished off a raider: the owner defended their factory. */
    private final java.util.Map<String, Long> defended = new java.util.concurrent.ConcurrentHashMap<>();

    void turretKill(UUID owner, Player raider) {
        long now = System.currentTimeMillis();
        defended.values().removeIf(t -> now - t > 600_000);
        if (defended.putIfAbsent(owner + "|" + raider.getUniqueId(), now) != null) {
            raider.sendMessage(Msg.bad("You were stopped by the factory's turrets."));
            return;
        }
        GensPlayer g = module.gensPlayer(owner);
        if (g != null) {
            g.addRaidDefended();
            g.addTokens(RankPerks.tokens(owner, 3));
            me.Evan52098.genscore.hub.HubModule.progress(owner, "DEFEND", 1);
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
