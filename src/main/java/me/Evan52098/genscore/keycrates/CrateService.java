package me.Evan52098.genscore.keycrates;

import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.economy.EconomyModule;
import me.Evan52098.genscore.player.PlayerData;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.shards.ShardsModule;
import me.Evan52098.genscore.shards.ShardsService;
import me.Evan52098.genscore.util.Cooldowns;
import me.Evan52098.genscore.util.Items;
import me.Evan52098.genscore.util.Money;
import me.Evan52098.genscore.util.Msg;
import me.Evan52098.genscore.util.PlayerFiles;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Opening crates, giving keys, the claim box, retired keys, playtime and vote keys, and the welcome spin.
 *
 * <p>Keys are treated as money. Opening is, in order (checklist numbers in brackets):
 * <ol>
 *   <li>on the player's thread, refuse if an open is already running for them (3) or the cooldown is on (10);</li>
 *   <li>count and remove the keys from the live inventory (1, 9), then save the player file at once (12);</li>
 *   <li>roll the rewards and write them as PENDING rows in one transaction (7); if that fails the keys are given back;</li>
 *   <li>play the animation (cosmetic only: the result was decided in step 3);</li>
 *   <li>mark the rows DONE with a conditional UPDATE (2), then give exactly the rows won, on the player's thread, and
 *       save the player file (12). Items that do not fit go to the claim box (6).</li>
 * </ol>
 * A quit, kick, death or stop anywhere in between leaves PENDING rows that are delivered at the next join (5). A crash
 * can at most lose the one step in flight, never duplicate it.
 */
final class CrateService {

    private final GensCore plugin;
    private final CrateStore store;
    private final KeyItems keys;
    private final KeyProgress.Section progress;
    private final Logger logger;
    private final Set<UUID> busy = ConcurrentHashMap.newKeySet();
    private final Set<UUID> claiming = ConcurrentHashMap.newKeySet();
    private final Cooldowns cooldowns = new Cooldowns();
    private volatile CrateConfig config;
    private CrateMenus menus;

    CrateService(GensCore plugin, CrateStore store, KeyItems keys, KeyProgress.Section progress, CrateConfig config) {
        this.plugin = plugin;
        this.store = store;
        this.keys = keys;
        this.progress = progress;
        this.config = config;
        this.logger = plugin.getLogger();
    }

    private volatile CrateBlocks blocks;
    private final java.util.Map<UUID, CrateBlocks.Pos> origin = new java.util.concurrent.ConcurrentHashMap<>();

    void blocks(CrateBlocks blocks) {
        this.blocks = blocks;
    }

    void menus(CrateMenus menus) {
        this.menus = menus;
    }

    CrateConfig config() {
        return config;
    }

    void config(CrateConfig c) {
        this.config = c;
    }

    KeyItems keys() {
        return keys;
    }

    CrateStore store() {
        return store;
    }

    /** Called on quit: a pending async step finishes through its own retired callback; these just free the player. */
    void forget(UUID uuid) {
        busy.remove(uuid);
        claiming.remove(uuid);
    }

    // =================================================================== opening

    /** Opens up to {@code requested} keys of a crate. On the player's thread. */
    void open(Player player, String crateId, int requested) {
        open(player, crateId, requested, null);
    }

    void open(Player player, String crateId, int requested, @Nullable CrateBlocks.Pos at) {
        if (at == null) {
            origin.remove(player.getUniqueId());
        } else if (!busy.contains(player.getUniqueId())) {
            origin.put(player.getUniqueId(), at);
        }
        CrateConfig cfg = config;
        CrateConfig.Crate crate = cfg.crate(crateId);
        if (crate == null) {
            player.sendMessage(Msg.bad("That crate is closed."));
            return;
        }
        UUID uuid = player.getUniqueId();
        if (busy.contains(uuid)) {
            player.sendMessage(Msg.bad("Your last crate is still opening."));
            return;
        }
        if (!cooldowns.tryStart(uuid, "open", cfg.openCooldownMs)) {
            return; // checklist 10: click spam
        }
        if (!busy.add(uuid)) { // checklist 3: one open at a time per player
            return;
        }
        boolean handedOff = false;
        try {
            PlayerInventory inv = player.getInventory();
            int have = keys.count(inv, crate.id());
            if (have <= 0) {
                player.sendMessage(Msg.bad("You need a " + legacyToMini(crate.keyName()) + " <reset>" + "to open this crate."));
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                return;
            }
            int n = Math.max(1, Math.min(Math.min(requested, have), cfg.openAllMax));
            int removed = keys.remove(inv, crate.id(), n); // checklist 1 and 9: take first, from the live inventory
            if (removed <= 0) {
                return;
            }
            PlayerFiles.save(player); // checklist 12: the take is on disk before the rewards exist
            logger.info("[keycrates] " + player.getName() + " used " + removed + "x " + crate.id() + " key(s).");
            me.Evan52098.genscore.seasonpass.SeasonPassModule.progress(uuid, // group H: crates opened
                    me.Evan52098.genscore.seasonpass.Objective.OPEN_CRATES, crate.id(), removed);
            handedOff = true;
            int spent = removed;
            store.createOpens(uuid, crate, spent, spent == 1 ? "key" : "key-bulk", false).whenComplete((batch, err) ->
                    later(player, () -> {
                        if (err != null) {
                            logger.log(Level.SEVERE, "[keycrates] Could not record an open for " + player.getName()
                                    + "; giving back " + spent + "x " + crate.id() + " key(s)", err);
                            giveKeys(player, crate.id(), spent, "refund after a failed open");
                            player.sendMessage(Msg.bad("Something went wrong opening the crate. Your keys were given back."));
                            busy.remove(uuid);
                            return;
                        }
                        afterRoll(player, crate, batch, true);
                    }, () -> {
                        if (err != null) {
                            refundToClaims(uuid, crate.id(), spent, "refund after a failed open (offline)");
                        }
                        busy.remove(uuid); // PENDING rows are delivered at the next join
                    }, 1));
        } finally {
            if (!handedOff) {
                busy.remove(uuid);
            }
        }
    }

    /** On the player's thread, after the rewards are written. */
    private void afterRoll(Player player, CrateConfig.Crate crate, CrateStore.OpenBatch batch, boolean announce) {
        UUID uuid = player.getUniqueId();
        if (batch.refunded() > 0) {
            player.sendMessage(Msg.neutral(Msg.white(batch.refunded() + "") + " key(s) went to " + Msg.white("/claim")
                    + ": you already own everything in this crate."));
        }
        List<CrateStore.OpenRow> rows = batch.rows();
        if (rows.isEmpty()) {
            busy.remove(uuid);
            return;
        }
        CrateConfig.Reward shown = rows.size() == 1 ? crate.reward(rows.get(0).reward()) : null;
        CrateBlocks.Pos at = origin.remove(uuid);
        CrateBlocks b = blocks;
        if (shown != null && config.animation && config.worldAnimation && at != null && b != null
                && b.play(at, player, crate, shown, shown.chance() <= config.broadcastUnder, () -> deliver(player, rows, announce), () -> busy.remove(uuid))) {
            player.closeInventory();
            return;
        }
        if (shown != null && config.animation && menus != null) {
            menus.animate(player, crate, shown, () -> deliver(player, rows, announce), () -> busy.remove(uuid));
        } else {
            player.closeInventory();
            deliver(player, rows, announce);
        }
    }

    /**
     * Wins the rows (checklist 2) and gives exactly those on the player's thread. Ends the player's busy state. If the
     * player leaves before the give, the rows go back to PENDING for the next join.
     */
    private void deliver(Player player, List<CrateStore.OpenRow> rows, boolean announce) {
        UUID uuid = player.getUniqueId();
        store.markDelivered(rows).whenComplete((won, err) -> {
            if (err != null) {
                logger.log(Level.WARNING, "[keycrates] Could not mark rewards delivered for " + player.getName() + "; they stay pending", err);
                busy.remove(uuid);
                return;
            }
            later(player, () -> {
                try {
                    give(player, won, announce);
                } finally {
                    busy.remove(uuid);
                }
            }, () -> {
                store.revertDelivered(won);
                busy.remove(uuid);
            }, 1);
        });
    }

    /** On the player's thread. */
    private void give(Player player, List<CrateStore.OpenRow> won, boolean announce) {
        if (won.isEmpty()) {
            return;
        }
        CrateConfig cfg = config;
        Map<String, Map<CrateConfig.Reward, Integer>> byCrate = new LinkedHashMap<>();
        for (CrateStore.OpenRow row : won) {
            CrateConfig.Crate crate = cfg.crate(row.crate());
            CrateConfig.Reward reward = crate == null ? null : crate.reward(row.reward());
            if (reward == null) {
                store.markMissing(row);
                logger.warning("[keycrates] " + player.getName() + " won " + row.crate() + "/" + row.reward()
                        + " (open #" + row.id() + "), which is no longer in crates.yml. Marked MISSING for staff.");
                player.sendMessage(Msg.bad("A crate reward you won no longer exists. Staff can see it as open #" + row.id() + "."));
                continue;
            }
            grant(player, crate, reward, "crate " + crate.id() + "/" + reward.id() + " #" + row.id());
            byCrate.computeIfAbsent(crate.id(), k -> new LinkedHashMap<>()).merge(reward, 1, Integer::sum);
        }
        PlayerFiles.save(player); // checklist 12: rows are DONE, items given, file on disk
        plugin.playerData().saveSoon(player.getUniqueId());
        for (Map.Entry<String, Map<CrateConfig.Reward, Integer>> e : byCrate.entrySet()) {
            CrateConfig.Crate crate = cfg.crate(e.getKey());
            int total = e.getValue().values().stream().mapToInt(Integer::intValue).sum();
            tellWins(player, crate, e.getValue(), total);
            if (announce) {
                announce(player, crate, e.getValue(), total);
            }
        }
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.4f);
    }

    private void tellWins(Player player, CrateConfig.Crate crate, Map<CrateConfig.Reward, Integer> wins, int total) {
        CrateConfig cfg = config;
        if (total == 1) {
            CrateConfig.Reward r = wins.keySet().iterator().next();
            player.sendMessage(CrateConfig.text("&6&lCRATES &8» &7You won " + r.name() + " &7from the " + crate.name()
                    + "&7! " + cfg.rarity(r.chance())));
            return;
        }
        player.sendMessage(CrateConfig.text("&6&lCRATES &8» &7You opened &f" + total + "x " + crate.name() + "&7:"));
        int lines = 0;
        for (Map.Entry<CrateConfig.Reward, Integer> w : wins.entrySet()) {
            if (++lines > 15) {
                player.sendMessage(CrateConfig.text("&8  ...and " + (wins.size() - 15) + " more kinds of reward."));
                break;
            }
            player.sendMessage(CrateConfig.text("&8 - &f" + w.getValue() + "x &r" + w.getKey().name() + " &8(" + cfg.rarity(w.getKey().chance()) + "&8)"));
        }
    }

    /** Every win is announced; under {@code broadcast-under}% it gets the rare line; bulk opens get one line plus rares. */
    private void announce(Player player, CrateConfig.Crate crate, Map<CrateConfig.Reward, Integer> wins, int total) {
        CrateConfig cfg = config;
        List<String> lines = new ArrayList<>();
        if (total == 1) {
            CrateConfig.Reward r = wins.keySet().iterator().next();
            lines.add(fill(r.chance() < cfg.broadcastUnder ? cfg.broadcastRare : cfg.broadcast, player, crate, r, total));
        } else {
            lines.add(fill(cfg.broadcastBulk, player, crate, null, total));
            int rare = 0;
            for (CrateConfig.Reward r : wins.keySet()) {
                if (r.chance() < cfg.broadcastUnder && rare++ < cfg.rareLinesMax) {
                    lines.add(fill(cfg.broadcastRare, player, crate, r, total));
                }
            }
        }
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            Component c = CrateConfig.text(line);
            for (Player p : Bukkit.getOnlinePlayers()) {
                Tasks.onEntity(p, () -> p.sendMessage(c));
            }
            logger.info("[keycrates] " + net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(c));
        }
    }

    private static String fill(String template, Player player, CrateConfig.Crate crate, @Nullable CrateConfig.Reward r, int amount) {
        if (template == null) {
            return null;
        }
        return template.replace("{player}", player.getName())
                .replace("{crate}", crate.name())
                .replace("{reward}", r == null ? "" : r.name())
                .replace("{chance}", r == null ? "" : CrateConfig.chanceText(r.chance()))
                .replace("{amount}", Integer.toString(amount));
    }

    /** Gives one reward. On the player's thread; the reward's row is already DONE. */
    private void grant(Player player, CrateConfig.Crate crate, CrateConfig.Reward reward, String cause) {
        UUID uuid = player.getUniqueId();
        if (!reward.items().isEmpty()) {
            List<ItemStack> copies = new ArrayList<>();
            for (ItemStack s : reward.items()) {
                copies.add(s.clone());
            }
            giveItems(player, copies, crate.id(), cause);
        }
        if (reward.moneyCents() > 0) {
            EconomyModule eco = plugin.modules().get(EconomyModule.class);
            if (eco == null || !eco.service().deposit(uuid, reward.moneyCents(), cause)) {
                logger.warning("[keycrates] Could not pay " + Money.format(reward.moneyCents()) + " to " + player.getName() + " (" + cause + ").");
            }
        }
        if (reward.shards() > 0) {
            ShardsService shards = ShardsModule.service();
            if (shards == null || !shards.deposit(uuid, reward.shards(), cause)) {
                logger.warning("[keycrates] Could not give " + reward.shards() + " shards to " + player.getName() + " (" + cause + ").");
            }
        }
        for (Map.Entry<String, Integer> k : reward.keys().entrySet()) {
            giveKeys(player, k.getKey(), k.getValue(), cause);
        }
        runCommands(player.getName(), uuid, reward.commands(), cause);
        logger.info("[keycrates] Gave " + player.getName() + " " + cause + ".");
    }

    private void runCommands(String name, UUID uuid, List<String> commands, String cause) {
        for (String command : commands) {
            String resolved = command.replace("{player}", name).replace("{uuid}", uuid.toString());
            Tasks.global.run(() -> {
                try {
                    if (!Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolved)) {
                        logger.warning("[keycrates] Reward command failed for " + name + " (" + cause + "): /" + resolved);
                    }
                } catch (RuntimeException e) {
                    logger.warning("[keycrates] Reward command threw for " + name + " (" + cause + "): /" + resolved + " (" + e.getMessage() + ")");
                }
            });
        }
    }

    /** Puts items in the inventory; whatever does not fit goes to the claim box (checklist 6). Player's thread. */
    private void giveItems(Player player, List<ItemStack> items, String crate, String cause) {
        Map<Integer, ItemStack> left = player.getInventory().addItem(items.toArray(new ItemStack[0]));
        if (left.isEmpty()) {
            return;
        }
        for (ItemStack s : left.values()) {
            store.addClaim(player.getUniqueId(), CrateStore.ITEM, crate, s.getAmount(), Items.encode(new ItemStack[]{s}), "did not fit: " + cause)
                    .exceptionally(e -> {
                        logger.log(Level.SEVERE, "[keycrates] LOST an item for " + player.getName() + " (" + cause + "): " + s, e);
                        return null;
                    });
        }
        player.sendMessage(Msg.neutral("Your inventory is full: some items went to " + Msg.white("/claim") + "."));
    }

    // =================================================================== keys

    /**
     * Gives keys to an online player on their thread; keys that do not fit go to the claim box (checklist 6). Keys of a
     * retired crate become shards. Returns false if the crate does not exist.
     */
    boolean giveKeys(Player player, String crateId, int amount, String reason) {
        if (amount <= 0) {
            return false;
        }
        CrateConfig cfg = config;
        CrateConfig.Crate crate = cfg.crate(crateId);
        if (crate == null) {
            long each = cfg.retiredValue(crateId.toLowerCase());
            ShardsService shards = ShardsModule.service();
            if (each > 0 && shards != null && shards.deposit(player.getUniqueId(), each * amount, "retired " + crateId + " keys: " + reason)) {
                player.sendMessage(Msg.neutral("The " + Msg.white(crateId) + " crate is closed: you got " + Msg.white(each * amount + "") + " shards instead."));
                return true;
            }
            logger.warning("[keycrates] Not giving " + amount + "x " + crateId + " keys to " + player.getName() + ": no such crate (" + reason + ").");
            return false;
        }
        Map<Integer, ItemStack> left = player.getInventory().addItem(keys.create(crate, amount).toArray(new ItemStack[0]));
        int over = 0;
        for (ItemStack s : left.values()) {
            over += s.getAmount();
        }
        if (over > 0) {
            int lost = over;
            store.addClaim(player.getUniqueId(), CrateStore.KEY, crate.id(), over, null, "did not fit: " + reason).exceptionally(e -> {
                logger.log(Level.SEVERE, "[keycrates] LOST " + lost + "x " + crate.id() + " keys for " + player.getName() + " (" + reason + ")", e);
                return null;
            });
            player.sendMessage(Msg.neutral(Msg.white(over + "") + " key(s) did not fit and are waiting in " + Msg.white("/claim") + "."));
        }
        logger.info("[keycrates] Gave " + amount + "x " + crate.id() + " key(s) to " + player.getName() + " (" + reason + ").");
        return true;
    }

    /** Any thread. Online players get the keys on their thread; offline players get them in the claim box. */
    boolean giveKeys(UUID uuid, String crateId, int amount, String reason) {
        CrateConfig cfg = config;
        String id = crateId.toLowerCase();
        if (amount <= 0 || (cfg.crate(id) == null && cfg.retiredValue(id) <= 0)) {
            return false;
        }
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            later(online, () -> giveKeys(online, id, amount, reason),
                    () -> refundToClaims(uuid, id, amount, reason + " (left before delivery)"), 1);
        } else {
            refundToClaims(uuid, id, amount, reason);
        }
        return true;
    }

    private void refundToClaims(UUID uuid, String crateId, int amount, String reason) {
        store.addClaim(uuid, CrateStore.KEY, crateId, amount, null, reason).whenComplete((v, e) -> {
            if (e != null) {
                logger.log(Level.SEVERE, "[keycrates] LOST " + amount + "x " + crateId + " keys for " + uuid + " (" + reason + ")", e);
            } else {
                logger.info("[keycrates] " + amount + "x " + crateId + " key(s) for " + uuid + " put in the claim box (" + reason + ").");
            }
        });
    }

    // =================================================================== claim box

    /** /claim. On the player's thread. */
    void claim(Player player) {
        UUID uuid = player.getUniqueId();
        if (!cooldowns.tryStart(uuid, "claim", 1500)) {
            player.sendMessage(Msg.bad("Slow down a little."));
            return;
        }
        if (!claiming.add(uuid)) { // checklist 3
            return;
        }
        CrateConfig cfg = config;
        store.claims(uuid, cfg.claimRowsPerUse).whenComplete((rows, err) -> later(player, () -> {
            if (err != null || rows == null) {
                logger.log(Level.WARNING, "[keycrates] Could not read the claim box of " + player.getName(), err);
                player.sendMessage(Msg.bad("Could not open your claim box. Try again in a moment."));
                claiming.remove(uuid);
                return;
            }
            planClaim(player, rows);
        }, () -> claiming.remove(uuid), 1));
    }

    private void planClaim(Player player, List<CrateStore.ClaimRow> rows) {
        UUID uuid = player.getUniqueId();
        if (rows.isEmpty()) {
            player.sendMessage(Msg.neutral("Your claim box is empty."));
            claiming.remove(uuid);
            return;
        }
        CrateConfig cfg = config;
        ItemStack[] slots = KeyItems.storageCopy(player.getInventory());
        List<CrateStore.ClaimTake> plan = new ArrayList<>();
        int closed = 0;
        for (CrateStore.ClaimRow row : rows) {
            if (CrateStore.KEY.equals(row.kind())) {
                CrateConfig.Crate crate = cfg.crate(row.crate());
                if (crate == null) {
                    if (cfg.retiredValue(row.crate()) > 0 && ShardsModule.service() != null) {
                        plan.add(new CrateStore.ClaimTake(row, row.amount())); // becomes shards, needs no room
                    } else {
                        closed++;
                    }
                    continue;
                }
                int fitted = 0;
                for (ItemStack stack : keys.create(crate, row.amount())) {
                    fitted += KeyItems.fit(slots, stack);
                }
                if (fitted > 0) {
                    plan.add(new CrateStore.ClaimTake(row, fitted));
                }
            } else {
                ItemStack item = decode(row);
                if (item == null) {
                    continue;
                }
                ItemStack[] trial = copy(slots);
                if (KeyItems.fit(trial, item) == item.getAmount()) { // items are claimed whole or not at all
                    slots = trial;
                    plan.add(new CrateStore.ClaimTake(row, row.amount()));
                }
            }
        }
        if (plan.isEmpty()) {
            player.sendMessage(Msg.bad(closed == rows.size()
                    ? "What is waiting belongs to closed crates. Ask staff about it."
                    : "Make some room in your inventory first."));
            claiming.remove(uuid);
            return;
        }
        int waiting = rows.size();
        store.take(uuid, plan).whenComplete((won, err) -> later(player, () -> {
            try {
                if (err != null) {
                    logger.log(Level.WARNING, "[keycrates] Claim failed for " + player.getName(), err);
                    player.sendMessage(Msg.bad("Could not claim right now. Nothing was lost; try again."));
                    return;
                }
                giveClaims(player, won, waiting);
            } finally {
                claiming.remove(uuid);
            }
        }, () -> {
            if (err == null && won != null) {
                putBack(uuid, won); // left before the give: the claimed rows come back as new rows (checklist 5)
            }
            claiming.remove(uuid);
        }, 1));
    }

    /** On the player's thread; these rows are already marked claimed. */
    private void giveClaims(Player player, List<CrateStore.ClaimTake> won, int waiting) {
        CrateConfig cfg = config;
        int keyCount = 0;
        int itemCount = 0;
        long shardTotal = 0;
        for (CrateStore.ClaimTake t : won) {
            CrateStore.ClaimRow row = t.row();
            if (CrateStore.KEY.equals(row.kind())) {
                CrateConfig.Crate crate = cfg.crate(row.crate());
                if (crate == null) {
                    long each = cfg.retiredValue(row.crate());
                    ShardsService shards = ShardsModule.service();
                    if (each > 0 && shards != null && shards.deposit(player.getUniqueId(), each * t.take(), "retired " + row.crate() + " keys from claim box")) {
                        shardTotal += each * t.take();
                    } else {
                        putBack(player.getUniqueId(), List.of(t));
                    }
                    continue;
                }
                giveKeys(player, crate.id(), t.take(), "claim box #" + row.id());
                keyCount += t.take();
            } else {
                ItemStack item = decode(row);
                if (item != null) {
                    giveItems(player, List.of(item), row.crate(), "claim box #" + row.id());
                    itemCount++;
                }
            }
        }
        PlayerFiles.save(player); // checklist 12: rows claimed first, items given, then saved
        StringBuilder sb = new StringBuilder("Claimed");
        if (keyCount > 0) {
            sb.append(' ').append(Msg.white(keyCount + "")).append(" key(s)");
        }
        if (itemCount > 0) {
            sb.append(keyCount > 0 ? " and " : " ").append(Msg.white(itemCount + "")).append(" item stack(s)");
        }
        if (shardTotal > 0) {
            sb.append(keyCount + itemCount > 0 ? ", plus " : " ").append(Msg.white(shardTotal + "")).append(" shards for closed-crate keys");
        }
        sb.append('.');
        player.sendMessage(Msg.good(sb.toString()));
        if (won.size() < waiting) {
            player.sendMessage(Msg.neutral("More is waiting: make room and use " + Msg.white("/claim") + " again."));
        }
    }

    private void putBack(UUID uuid, List<CrateStore.ClaimTake> takes) {
        for (CrateStore.ClaimTake t : takes) {
            store.addClaim(uuid, t.row().kind(), t.row().crate(), t.take(), t.row().item(), "returned: claim #" + t.row().id() + " not delivered")
                    .exceptionally(e -> {
                        logger.log(Level.SEVERE, "[keycrates] Could not return claim #" + t.row().id() + " for " + uuid, e);
                        return null;
                    });
        }
    }

    private @Nullable ItemStack decode(CrateStore.ClaimRow row) {
        if (row.item() == null) {
            return null;
        }
        try {
            ItemStack[] items = Items.decode(row.item());
            return items.length > 0 && items[0] != null && !items[0].isEmpty() ? items[0] : null;
        } catch (RuntimeException e) {
            logger.warning("[keycrates] Claim #" + row.id() + " has an unreadable item; left for staff.");
            return null;
        }
    }

    private static ItemStack[] copy(ItemStack[] slots) {
        ItemStack[] out = new ItemStack[slots.length];
        for (int i = 0; i < slots.length; i++) {
            out[i] = slots[i] == null ? null : slots[i].clone();
        }
        return out;
    }

    // =================================================================== retired keys

    /**
     * Keys of crates that are closed and listed under {@code retired-keys} turn into shards wherever they turn up
     * (inventory, ender chest). Removed first, file saved, then shards paid (checklist 1 and 12). Player's thread.
     */
    void convertRetired(Player player) {
        CrateConfig cfg = config;
        if (cfg.retiredKeys.isEmpty()) {
            return;
        }
        ShardsService shards = ShardsModule.service();
        if (shards == null) {
            return; // keep the keys until shards can be paid
        }
        Map<String, Integer> found = new LinkedHashMap<>();
        found.putAll(takeRetired(player.getInventory(), cfg));
        takeRetired(player.getEnderChest(), cfg).forEach((k, v) -> found.merge(k, v, Integer::sum));
        if (found.isEmpty()) {
            return;
        }
        PlayerFiles.save(player);
        long total = 0;
        for (Map.Entry<String, Integer> e : found.entrySet()) {
            long value = cfg.retiredValue(e.getKey()) * e.getValue();
            if (shards.deposit(player.getUniqueId(), value, "retired " + e.getKey() + " keys x" + e.getValue())) {
                total += value;
            } else {
                logger.warning("[keycrates] Could not pay " + value + " shards for retired keys to " + player.getName() + "; putting the keys in the claim box.");
                refundToClaims(player.getUniqueId(), e.getKey(), e.getValue(), "retired keys, shards refused");
            }
        }
        if (total > 0) {
            player.sendMessage(Msg.neutral("Keys of closed crates turned into " + Msg.white(String.format("%,d", total)) + " shards."));
        }
    }

    private Map<String, Integer> takeRetired(Inventory inv, CrateConfig cfg) {
        Map<String, Integer> found = new LinkedHashMap<>();
        ItemStack[] contents = inv.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            String id = keys.crateOf(contents[slot]);
            if (id != null && cfg.retiredValue(id) > 0) {
                found.merge(id, contents[slot].getAmount(), Integer::sum);
                inv.setItem(slot, null);
            }
        }
        return found;
    }

    // =================================================================== join, playtime, votes, welcome spin

    /** Shortly after join, on the player's thread. */
    void onJoined(Player player) {
        UUID uuid = player.getUniqueId();
        convertRetired(player);
        // Rewards won but never given (left mid-open, crash, stop).
        if (busy.add(uuid)) {
            store.pendingOpens(uuid).whenComplete((rows, err) -> {
                if (err != null || rows == null || rows.isEmpty()) {
                    if (err != null) {
                        logger.log(Level.WARNING, "[keycrates] Could not check unopened rewards of " + player.getName(), err);
                    }
                    busy.remove(uuid);
                    return;
                }
                later(player, () -> player.sendMessage(Msg.neutral("Delivering crate rewards from your last visit.")),
                        null, 1);
                deliver(player, rows, false);
            });
        }
        store.claimCounts(uuid).thenAccept(c -> {
            if (c[0] + c[1] > 0) {
                later(player, () -> player.sendMessage(Msg.neutral("You have " + (c[0] > 0 ? Msg.white(c[0] + "") + " key(s) " : "")
                        + (c[1] > 0 ? (c[0] > 0 ? "and " : "") + Msg.white(c[1] + "") + " item(s) " : "") + "waiting: " + Msg.white("/claim"))), null, 20);
            }
        });
        PlayerData data = plugin.playerData().get(uuid);
        CrateConfig cfg = config;
        if (data != null && data.isFirstTime() && cfg.welcomeEnabled && cfg.welcomeCrate != null) {
            later(player, () -> welcomeSpin(player), null, cfg.welcomeDelaySeconds * 20L);
        }
    }

    /** HxCrates' WelcomeSpin: one free open for a first join, at most once per player (welcome row, checklist 2). */
    private void welcomeSpin(Player player) {
        CrateConfig cfg = config;
        CrateConfig.Crate crate = cfg.crate(cfg.welcomeCrate);
        UUID uuid = player.getUniqueId();
        if (crate == null || !busy.add(uuid)) {
            if (crate != null) {
                later(player, () -> welcomeSpin(player), null, 60); // an open is running: try again shortly
            }
            return;
        }
        store.createOpens(uuid, crate, 1, "welcome", true).whenComplete((batch, err) -> later(player, () -> {
            if (err != null || batch.rows().isEmpty()) {
                if (err != null) {
                    logger.log(Level.WARNING, "[keycrates] Welcome spin failed for " + player.getName(), err);
                }
                busy.remove(uuid);
                return;
            }
            player.sendMessage(CrateConfig.text(cfg.welcomeMessage.replace("{crate}", crate.name()).replace("{player}", player.getName())));
            afterRoll(player, crate, batch, true);
        }, () -> busy.remove(uuid), 1));
    }

    /** Every minute, on the player's thread: one key per {@code playtime.minutes} of non-AFK time. */
    void playtimeMinute(Player player, boolean afk) {
        CrateConfig cfg = config;
        if (cfg.playtimeCrate == null || (afk && !cfg.playtimeCountAfk)) {
            return;
        }
        PlayerData data = plugin.playerData().get(player.getUniqueId());
        if (data == null) {
            return;
        }
        if (!data.get(progress).tick(cfg.playtimeMinutes)) {
            return;
        }
        String crateId = cfg.playtimeCrate;
        UUID uuid = player.getUniqueId();
        // The counter reset is saved before the key is given, so a crash can't pay the same hour twice.
        plugin.playerData().saveNow(uuid).thenRun(() -> {
            Player online = Bukkit.getPlayer(uuid);
            if (online == null) {
                refundToClaims(uuid, crateId, 1, "playtime");
                return;
            }
            later(online, () -> {
                CrateConfig.Crate crate = config.crate(crateId);
                if (giveKeys(online, crateId, 1, "playtime") && crate != null) {
                    online.sendMessage(CrateConfig.text("&6&lCRATES &8» &7+1 " + crate.keyName() + " &7for " + cfg.playtimeMinutes + " minutes of playtime!"));
                    online.playSound(online.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
                }
            }, () -> refundToClaims(uuid, crateId, 1, "playtime (left before delivery)"), 1);
        });
    }

    /** A vote was paid. On the voter's thread (called from the vote module's hook, see group-f.md). */
    void onVote(Player player) {
        CrateConfig cfg = config;
        StringBuilder keyText = new StringBuilder();
        if (cfg.voteCrate != null && cfg.voteKeys > 0) {
            CrateConfig.Crate crate = cfg.crate(cfg.voteCrate);
            if (crate != null && giveKeys(player, crate.id(), cfg.voteKeys, "vote")) {
                keyText.append(" &7and &f").append(cfg.voteKeys).append("x ").append(crate.keyName());
            }
        }
        long shards = 0;
        ShardsService service = ShardsModule.service();
        if (cfg.voteShards > 0 && service != null && service.deposit(player.getUniqueId(), cfg.voteShards, "vote")) {
            shards = cfg.voteShards;
        }
        if ((shards > 0 || keyText.length() > 0) && cfg.voteMessage != null && !cfg.voteMessage.isBlank()) {
            player.sendMessage(CrateConfig.text(cfg.voteMessage.replace("{shards}", Long.toString(shards)).replace("{keys}", keyText.toString())
                    .replace("{player}", player.getName())));
        }
    }

    /**
     * {@code Tasks.entity.later}, but the retired callback also runs when the player is already gone at scheduling time
     * (Paper returns null then and never calls it), so a hand-off can never be dropped silently (checklist 5).
     */
    static void later(Player player, Runnable task, @Nullable Runnable retired, long delayTicks) {
        if (Tasks.entity.later(player, task, retired, delayTicks) == null && retired != null) {
            retired.run();
        }
    }

    private static String legacyToMini(String legacy) {
        return net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().serialize(CrateConfig.text(legacy));
    }
}
