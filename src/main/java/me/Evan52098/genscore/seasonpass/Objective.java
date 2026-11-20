package me.Evan52098.genscore.seasonpass;

import org.bukkit.Material;

/**
 * What a challenge counts. The first six are counted from Bukkit events by {@link PassListener}; the rest need a hook
 * in another module (see integration/group-h.md) or the console command {@code /pass progress}.
 */
public enum Objective {
    KILL_PLAYERS(Material.IRON_SWORD, "Kill %d players"),
    KILL_MOBS(Material.ZOMBIE_HEAD, "Kill %d mobs"),
    MINE_BLOCKS(Material.IRON_PICKAXE, "Mine %d blocks"),
    FISH(Material.FISHING_ROD, "Catch %d fish"),
    BREED_ANIMALS(Material.WHEAT, "Breed %d animals"),
    PLAYTIME_MINUTES(Material.CLOCK, "Play for %d minutes"),
    SELL_ITEMS(Material.GOLD_INGOT, "Sell %d items"),
    OPEN_CRATES(Material.TRIPWIRE_HOOK, "Open %d crates"),
    VOTE(Material.PAPER, "Vote %d times"),
    CUSTOM(Material.NETHER_STAR, "Complete %d tasks");

    final Material icon;
    private final String text;

    Objective(Material icon, String text) {
        this.icon = icon;
        this.text = text;
    }

    String describe(long amount) {
        return String.format(text, amount);
    }
}
