package me.Evan52098.genscore.rewardbox;

import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.economy.EconomyModule;
import me.Evan52098.genscore.keycrates.KeyCratesModule;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.shards.ShardsModule;
import me.Evan52098.genscore.shards.ShardsService;
import me.Evan52098.genscore.storage.Database;
import me.Evan52098.genscore.storage.Migrations;
import me.Evan52098.genscore.util.Items;
import me.Evan52098.genscore.util.Msg;
import me.Evan52098.genscore.util.PlayerFiles;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The reward box shared by the season pass and the holiday features (group H): every reward is first written as a
 * PENDING row, inside the same transaction that decides the claim, and then given from that row exactly once.
 * Rewards that cannot be given yet (full inventory, player offline, a module switched off) stay PENDING and are
 * retried on the next join or with the box command.
 *
 * <p>State machine per row: PENDING -> GIVING (conditional UPDATE, single winner, checklist 2) -> give -> DONE.
 * If the give cannot happen the row goes back GIVING -> PENDING. Rows left in GIVING by a crash are <b>not</b>
 * given again (that could duplicate); they are logged at startup for staff to check (checklist 7 and 12: a crash can
 * lose at most the one reward in flight, never duplicate it). A normal stop puts every queued row back to PENDING.
 */
public final class RewardBox {

    private static final String PENDING = "PENDING";
    private static final String GIVING = "GIVING";
    private static final String DONE = "DONE";

    private record Row(long id, UUID uuid, String source, Reward.Kind kind, long amount, String payload, String display) {
    }

    private final GensCore plugin;
    private final Database db;
    private final Logger logger;
    private final String prefix;
    private final String boxCommand;
    private final String tag;
    /** Rows handed to a player or global task that has not run yet; reverted to PENDING at shutdown. */
    private final Map<Long, Row> queued = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastFullNotice = new ConcurrentHashMap<>();

    /**
     * @param prefix     source prefix this box handles (e.g. {@code "pass:"}); other modules' rows are left alone
     * @param boxCommand the command players use to retry, shown in messages (e.g. {@code "/pass box"})
     */
    public RewardBox(GensCore plugin, String prefix, String boxCommand, String tag) throws SQLException {
        this.plugin = plugin;
        this.db = plugin.database();
        this.logger = plugin.getLogger();
        this.prefix = prefix;
        this.boxCommand = boxCommand;
        this.tag = tag;
        Migrations.apply(db, "rewardbox", logger, schema(db));
        reportInterrupted();
    }

    private static String schema(Database db) {
        return "CREATE TABLE IF NOT EXISTS pvp_reward_box ("
                + db.autoIdColumn("id") + ", "
                + "uuid TEXT NOT NULL, "
                + "player_name TEXT NOT NULL, "
                + "source TEXT NOT NULL, "
                + "kind TEXT NOT NULL, "
                + "amount BIGINT NOT NULL, "
                + "payload TEXT NOT NULL, "
                + "display TEXT NOT NULL, "
                + "state TEXT NOT NULL, "
                + "created_at BIGINT NOT NULL, "
                + "done_at BIGINT);"
                + "CREATE INDEX IF NOT EXISTS pvp_reward_box_owner ON pvp_reward_box (uuid, state)";
    }

    // ------------------------------------------------------------------ granting

    /**
     * Writes the rewards as PENDING rows. Call inside the transaction that decides the claim, so the claim and its
     * rewards are saved together or not at all (checklist 7). {@code source} must start with this box's prefix.
     */
    public void grant(Connection tx, UUID uuid, String playerName, String source, List<Reward> rewards) throws SQLException {
        if (!source.startsWith(prefix)) {
            throw new IllegalArgumentException("source " + source + " does not start with " + prefix);
        }
        try (PreparedStatement ps = tx.prepareStatement("INSERT INTO pvp_reward_box (uuid, player_name, source, kind, amount, "
                + "payload, display, state, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            long now = System.currentTimeMillis();
            for (Reward r : rewards) {
                String payload = r.kind() == Reward.Kind.COMMAND
                        ? r.payload().replace("{player}", playerName).replace("{uuid}", uuid.toString())
                        : r.payload();
                ps.setString(1, uuid.toString());
                ps.setString(2, playerName);
                ps.setString(3, source);
                ps.setString(4, r.kind().name());
                ps.setLong(5, r.amount());
                ps.setString(6, payload);
                ps.setString(7, r.display());
                ps.setString(8, PENDING);
                ps.setLong(9, now);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    // ------------------------------------------------------------------ giving

    /** Gives everything this player has waiting in this box. Safe from any thread; does the work off the game threads. */
    public void deliver(UUID uuid) {
        Tasks.async.run(() -> {
            List<Row> rows;
            try {
                rows = db.sync(c -> select(c, uuid, PENDING));
            } catch (SQLException e) {
                logger.log(Level.WARNING, "[" + tag + "] Could not read the reward box of " + uuid, e);
                return;
            }
            for (Row row : rows) {
                try {
                    process(row);
                } catch (RuntimeException e) {
                    logger.log(Level.WARNING, "[" + tag + "] Reward #" + row.id() + " for " + row.uuid() + " failed; it stays in the box", e);
                    back(row);
                }
            }
        });
    }

    /** How many rewards wait in this box for the player. Completes on a database thread. */
    public CompletableFuture<Integer> waiting(UUID uuid) {
        return db.async(c -> select(c, uuid, PENDING).size());
    }

    /** Gives the player's box a few seconds after they join, once their wallet and other data are loaded. */
    public void deliverAfterJoin(Player player) {
        Tasks.entity.later(player, () -> deliver(player.getUniqueId()), 60L);
    }

    private void notify(Row row) {
        Player p = Bukkit.getPlayer(row.uuid());
        if (p != null) {
            Tasks.entity.run(p, () -> p.sendMessage(Msg.good("Received " + legacyToMini(row.display()) + ".")));
        }
    }

    private void process(Row row) {
        if (!take(row)) {
            return; // someone else is giving it (checklist 2)
        }
        if (row.kind() == Reward.Kind.MONEY || row.kind() == Reward.Kind.SHARDS || row.kind() == Reward.Kind.KEYS) {
            processCurrency(row);
            return;
        }
        switch (row.kind()) {
            case ITEM -> giveItem(row);
            case COMMAND -> runCommand(row);
            default -> back(row);
        }
    }

    private void processCurrency(Row row) {
        boolean given = switch (row.kind()) {
            case MONEY -> {
                EconomyModule eco = plugin.modules().get(EconomyModule.class);
                yield eco != null && eco.service().deposit(row.uuid(), row.amount(), "reward " + row.source());
            }
            case SHARDS -> {
                ShardsService shards = ShardsModule.service();
                yield shards != null && shards.deposit(row.uuid(), row.amount(), "reward " + row.source());
            }
            // Keys that don't fit go to the key crates' own /claim box, so this never loses them.
            case KEYS -> KeyCratesModule.giveKeys(row.uuid(), row.payload(), (int) row.amount(), "reward " + row.source());
            default -> false;
        };
        if (given) {
            done(row);
            logger.info("[" + tag + "] Gave " + row.uuid() + " reward #" + row.id() + " " + row.kind() + " " + row.amount() + " (" + row.source() + ")");
            notify(row);
        } else {
            back(row); // module off or refused: stays in the box
        }
    }

    private void giveItem(Row row) {
        Player player = Bukkit.getPlayer(row.uuid());
        if (player == null) {
            back(row); // items only go into a live inventory
            return;
        }
        queued.put(row.id(), row);
        ScheduledTask task = Tasks.entity.run(player, () -> {
            if (queued.remove(row.id()) == null) {
                return; // put back at shutdown
            }
            if (!player.isOnline()) {
                async(() -> back(row));
                return;
            }
            ItemStack[] decoded;
            try {
                decoded = Items.decode(row.payload());
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "[" + tag + "] Reward #" + row.id() + " has an unreadable item; it stays in the box", e);
                async(() -> back(row));
                return;
            }
            List<ItemStack> items = Items.nonEmpty(decoded);
            // Checklist 6: only give when everything fits; otherwise the row waits in the box.
            if (!fits(player.getInventory(), items)) {
                async(() -> back(row));
                long now = System.currentTimeMillis();
                Long last = lastFullNotice.get(row.uuid());
                if (last == null || now - last > 5000L) {
                    lastFullNotice.put(row.uuid(), now);
                    player.sendMessage(Msg.bad("Your inventory is full. Rewards are waiting: make room, then use " + Msg.white(boxCommand) + "."));
                }
                return;
            }
            for (ItemStack item : items) {
                player.getInventory().addItem(item);
            }
            PlayerFiles.save(player); // checklist 12: row is GIVING (taken), now the inventory reaches disk
            player.sendMessage(Msg.good("Received " + legacyToMini(row.display()) + "."));
            logger.info("[" + tag + "] Gave " + player.getName() + " item reward #" + row.id() + " (" + row.source() + ")"); // checklist 11
            async(() -> done(row));
        });
        if (task == null && queued.remove(row.id()) != null) {
            back(row); // the player left between the check and the schedule
        }
    }

    private void runCommand(Row row) {
        Player player = Bukkit.getPlayer(row.uuid());
        if (player == null) {
            back(row); // commands often need the player online (give, effects); run them when they are
            return;
        }
        queued.put(row.id(), row);
        Tasks.global.run(() -> {
            if (queued.remove(row.id()) == null) {
                return;
            }
            try {
                if (!Bukkit.dispatchCommand(Bukkit.getConsoleSender(), row.payload())) {
                    logger.severe("[" + tag + "] Reward command #" + row.id() + " failed: /" + row.payload()
                            + ". It was marked given; run it by hand if the player is owed it.");
                }
            } catch (RuntimeException e) {
                logger.log(Level.SEVERE, "[" + tag + "] Reward command #" + row.id() + " threw: /" + row.payload()
                        + ". It was marked given; run it by hand if the player is owed it.", e);
            }
            logger.info("[" + tag + "] Ran reward command #" + row.id() + " for " + row.uuid() + " (" + row.source() + ")");
            async(() -> done(row));
        });
        Player p = Bukkit.getPlayer(row.uuid());
        if (p != null) {
            Tasks.entity.run(p, () -> p.sendMessage(Msg.good("Received " + legacyToMini(row.display()) + ".")));
        }
    }

    private static boolean fits(PlayerInventory inventory, List<ItemStack> items) {
        ItemStack[] slots = inventory.getStorageContents();
        ItemStack[] copy = new ItemStack[slots.length];
        for (int i = 0; i < slots.length; i++) {
            copy[i] = slots[i] == null ? null : slots[i].clone();
        }
        for (ItemStack item : items) {
            int left = item.getAmount();
            int max = item.getMaxStackSize();
            for (int i = 0; i < copy.length && left > 0; i++) {
                ItemStack s = copy[i];
                if (s != null && !s.isEmpty() && s.isSimilar(item) && s.getAmount() < max) {
                    int add = Math.min(left, max - s.getAmount());
                    s.setAmount(s.getAmount() + add);
                    left -= add;
                }
            }
            for (int i = 0; i < copy.length && left > 0; i++) {
                if (copy[i] == null || copy[i].isEmpty()) {
                    ItemStack placed = item.clone();
                    int add = Math.min(left, max);
                    placed.setAmount(add);
                    copy[i] = placed;
                    left -= add;
                }
            }
            if (left > 0) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ row states

    private boolean take(Row row) {
        try {
            return db.sync(c -> setState(c, row.id(), PENDING, GIVING)) == 1;
        } catch (SQLException e) {
            logger.log(Level.WARNING, "[" + tag + "] Could not take reward #" + row.id(), e);
            return false;
        }
    }

    private void back(Row row) {
        try {
            db.sync(c -> setState(c, row.id(), GIVING, PENDING));
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "[" + tag + "] Reward #" + row.id() + " for " + row.uuid() + " (" + row.display()
                    + ") was not given and could not be put back in the box; it is left GIVING. Set its state to PENDING by hand.", e);
        }
    }

    private void done(Row row) {
        try {
            db.sync(c -> {
                try (PreparedStatement ps = c.prepareStatement("UPDATE pvp_reward_box SET state = ?, done_at = ? WHERE id = ? AND state = ?")) {
                    ps.setString(1, DONE);
                    ps.setLong(2, System.currentTimeMillis());
                    ps.setLong(3, row.id());
                    ps.setString(4, GIVING);
                    return ps.executeUpdate();
                }
            });
        } catch (SQLException e) {
            // Left GIVING: it was given, so it will not be given again; startup reports it.
            logger.log(Level.WARNING, "[" + tag + "] Reward #" + row.id() + " was given but could not be marked DONE", e);
        }
    }

    private static int setState(Connection c, long id, String from, String to) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE pvp_reward_box SET state = ? WHERE id = ? AND state = ?")) {
            ps.setString(1, to);
            ps.setLong(2, id);
            ps.setString(3, from);
            return ps.executeUpdate();
        }
    }

    private List<Row> select(Connection c, UUID uuid, String state) throws SQLException {
        List<Row> rows = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT id, uuid, source, kind, amount, payload, display FROM pvp_reward_box "
                + "WHERE uuid = ? AND state = ? AND source LIKE ? ORDER BY id")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, state);
            ps.setString(3, prefix + "%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Reward.Kind kind;
                    try {
                        kind = Reward.Kind.valueOf(rs.getString(4));
                    } catch (IllegalArgumentException e) {
                        continue;
                    }
                    rows.add(new Row(rs.getLong(1), UUID.fromString(rs.getString(2)), rs.getString(3), kind, rs.getLong(5),
                            rs.getString(6), rs.getString(7)));
                }
            }
        }
        return rows;
    }

    private void async(Runnable r) {
        try {
            Tasks.async.run(r);
        } catch (RuntimeException stopping) {
            r.run();
        }
    }

    /** Startup: rows a crash left half-given. Not given again (could duplicate); listed for staff. */
    private void reportInterrupted() {
        try {
            db.sync(c -> {
                try (PreparedStatement ps = c.prepareStatement("SELECT id, player_name, source, display FROM pvp_reward_box "
                        + "WHERE state = ? AND source LIKE ?")) {
                    ps.setString(1, GIVING);
                    ps.setString(2, prefix + "%");
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            logger.warning("[" + tag + "] Reward #" + rs.getLong(1) + " for " + rs.getString(2) + " (" + rs.getString(3)
                                    + ", " + rs.getString(4) + ") was being given when the server stopped. It is not given again; "
                                    + "if the player did not get it, set state = 'PENDING' for that row in pvp_reward_box.");
                        }
                    }
                }
                return null;
            });
        } catch (SQLException e) {
            logger.log(Level.WARNING, "[" + tag + "] Could not check for interrupted rewards", e);
        }
    }

    /** At disable: rows queued for a player/global task that never ran go back to PENDING (checklist 5). */
    public void shutdown() {
        for (Long id : List.copyOf(queued.keySet())) {
            Row row = queued.remove(id);
            if (row != null) {
                back(row);
            }
        }
    }

    /** Reward display text uses {@code &} codes; turn it into MiniMessage for chat. */
    public static String legacyToMini(String legacy) {
        return net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().serialize(Reward.text(legacy));
    }
}
