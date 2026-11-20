package me.Evan52098.genscore.vouchers;

import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.storage.Database;
import me.Evan52098.genscore.util.Items;
import me.Evan52098.genscore.util.Msg;
import me.Evan52098.genscore.util.PlayerFiles;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Vouchers, ported from BlockvilleCore's VoucherService: an admin makes a voucher type from the item in their hand and
 * a console command, gives out copies, and a player right-clicks one to run the command. Every physical voucher has its
 * own ID and database record, so each can be redeemed once.
 *
 * <p>Safety (numbers are the porting guide's checklist):
 * <ul>
 *   <li>A voucher is recognised only by its PersistentDataContainer ID, never by name or lore, and the ID must exist in
 *       the database: a renamed paper or a forged item is worth nothing. What it runs comes from the database record,
 *       not from the item. (9)</li>
 *   <li>Redeeming takes exactly one item from the main hand on the player's thread first (1), then one conditional
 *       UPDATE ({@code WHERE redeemed = 0}) decides whether this copy wins (2). A second copy of the same ID, a stacked
 *       duplicate, a double-click or a second thread all lose and the copy stays removed. A per-player in-flight flag
 *       ignores clicks while one redeem is running (3).</li>
 *   <li>If the database fails, the taken item is given back; if the player already left, it is stored and returned on
 *       their next join (5, 6).</li>
 *   <li>The reward is marked given ({@code reward_given}) right before it runs; a reward that was redeemed but not yet
 *       given when the server stopped or crashed is given on the player's next join (7).</li>
 *   <li>A sweep every five minutes removes copies of the same ID held more than once (the original issuee keeps one).</li>
 * </ul>
 */
public final class VoucherService implements VoucherBridge {

    static final String STAFF_PERMISSION = "genscore.admin.voucher";
    static final int MAX_GIVE = 64; // checklist 10: cap on copies per command
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final String STATUS_LABEL = "ѕᴛᴀᴛᴜѕ:";
    private static final String OLD_STATUS_LABEL = "Status:";
    private static final String OLD_ID_PREFIX = "ID: ";
    static final String STATUS_UNREDEEMED = "&fStatus: &aUnredeemed";
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    record Template(String name, String command, String item) {
    }

    private enum Status { REDEEMED, ALREADY, UNKNOWN }

    private record RedeemResult(Status status, @Nullable String kind, @Nullable String redeemedByName,
                                @Nullable UUID redeemedBy, long redeemedAt) {
    }

    private record Delivery(String voucherId, String kind, @Nullable String command) {
    }

    private final GensCore plugin;
    private final Database db;
    private final Logger logger;
    private final NamespacedKey idKey;
    private final NamespacedKey templateKey;
    private final SecureRandom random = new SecureRandom();
    private final Set<UUID> redeeming = ConcurrentHashMap.newKeySet();
    private final Map<String, KindHandler> kindHandlers = new ConcurrentHashMap<>();
    /** Rewards marked given whose console commands have not run yet; reverted at shutdown so they are retried. */
    private final Map<String, Runnable> pendingDispatch = new ConcurrentHashMap<>();

    VoucherService(GensCore plugin) {
        this.plugin = plugin;
        this.db = plugin.database();
        this.logger = plugin.getLogger();
        this.idKey = new NamespacedKey(plugin, "voucher_id");
        this.templateKey = new NamespacedKey(plugin, "voucher_template");
    }

    static String schema(Database db) {
        return "CREATE TABLE IF NOT EXISTS pvp_voucher_templates ("
                + "name TEXT PRIMARY KEY, "
                + "command TEXT NOT NULL, "
                + "item TEXT NOT NULL, "
                + "created_by TEXT, "
                + "created_by_name TEXT, "
                + "created_at BIGINT NOT NULL);"
                + "CREATE TABLE IF NOT EXISTS pvp_voucher_instances ("
                + "voucher_id TEXT PRIMARY KEY, "
                + "template_name TEXT NOT NULL, "
                + "issued_by TEXT, "
                + "issued_by_name TEXT, "
                + "issued_to TEXT, "
                + "issued_to_name TEXT, "
                + "issued_at BIGINT NOT NULL, "
                + "redeemed INTEGER NOT NULL DEFAULT 0, "
                + "redeemed_by TEXT, "
                + "redeemed_by_name TEXT, "
                + "redeemed_at BIGINT, "
                + "reward_given INTEGER NOT NULL DEFAULT 0);"
                + "CREATE INDEX IF NOT EXISTS pvp_voucher_instances_owed ON pvp_voucher_instances (redeemed_by, reward_given);"
                + "CREATE TABLE IF NOT EXISTS pvp_voucher_returns ("
                + db.autoIdColumn("id") + ", "
                + "uuid TEXT NOT NULL, "
                + "voucher_id TEXT NOT NULL, "
                + "item TEXT NOT NULL, "
                + "created_at BIGINT NOT NULL);"
                + "CREATE INDEX IF NOT EXISTS pvp_voucher_returns_uuid ON pvp_voucher_returns (uuid)";
    }

    void start() {
        Tasks.async.timer(t -> scanForDuplicates(), 5, 5, TimeUnit.MINUTES);
    }

    // ------------------------------------------------------------------ templates (admin)

    static boolean validName(String name) {
        return NAME.matcher(name).matches();
    }

    /** Call on the admin's thread (reads their main hand). */
    void createTemplate(Player admin, String name, String command, CommandSender reply) {
        String look = captureAppearance(name, admin.getInventory().getItemInMainHand());
        UUID by = admin.getUniqueId();
        String byName = admin.getName();
        db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO pvp_voucher_templates (name, command, item, created_by, "
                    + "created_by_name, created_at) VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (name) DO NOTHING")) {
                ps.setString(1, name);
                ps.setString(2, command);
                ps.setString(3, look);
                ps.setString(4, by.toString());
                ps.setString(5, byName);
                ps.setLong(6, System.currentTimeMillis());
                return ps.executeUpdate();
            }
        }).whenComplete((rows, e) -> {
            if (e != null) {
                logger.log(Level.WARNING, "Failed to create voucher template " + name, e);
                reply(reply, Msg.bad("Something went wrong creating that voucher - check the console."));
            } else if (rows == 0) {
                reply(reply, Msg.bad("A voucher named <white>" + name + "</white> already exists."));
            } else {
                logger.info("[voucher] " + byName + " created voucher " + name + " running: " + command);
                reply(reply, Msg.good("Created voucher <white>" + name + "</white>. Give it out with /voucher give <white>" + name + "</white> <player>."));
            }
        });
    }

    /** Call on the admin's thread. */
    void editAppearance(Player admin, String name, CommandSender reply) {
        String look = captureAppearance(name, admin.getInventory().getItemInMainHand());
        db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE pvp_voucher_templates SET item = ? WHERE name = ?")) {
                ps.setString(1, look);
                ps.setString(2, name);
                return ps.executeUpdate();
            }
        }).whenComplete((rows, e) -> {
            if (e != null) {
                logger.log(Level.WARNING, "Failed to edit voucher template " + name, e);
                reply(reply, Msg.bad("Something went wrong editing that voucher - check the console."));
            } else if (rows == 0) {
                reply(reply, Msg.bad("No voucher named <white>" + name + "</white>."));
            } else {
                reply(reply, Msg.good("Updated the look of <white>" + name + "</white> from what you're holding."));
            }
        });
    }

    void setCommand(String name, String command, String staffName, CommandSender reply) {
        db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE pvp_voucher_templates SET command = ? WHERE name = ?")) {
                ps.setString(1, command);
                ps.setString(2, name);
                return ps.executeUpdate();
            }
        }).whenComplete((rows, e) -> {
            if (e != null) {
                logger.log(Level.WARNING, "Failed to set command for voucher template " + name, e);
                reply(reply, Msg.bad("Something went wrong - check the console."));
            } else if (rows == 0) {
                reply(reply, Msg.bad("No voucher named <white>" + name + "</white>."));
            } else {
                logger.info("[voucher] " + staffName + " set the command of " + name + " to: " + command);
                reply(reply, Msg.good("Command for <white>" + name + "</white> set to " + Msg.escape(command) + "."));
            }
        });
    }

    void deleteTemplate(String name, String staffName, CommandSender reply) {
        db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM pvp_voucher_templates WHERE name = ?")) {
                ps.setString(1, name);
                return ps.executeUpdate();
            }
        }).whenComplete((rows, e) -> {
            if (e != null) {
                logger.log(Level.WARNING, "Failed to delete voucher template " + name, e);
                reply(reply, Msg.bad("Something went wrong - check the console."));
            } else if (rows == 0) {
                reply(reply, Msg.bad("No voucher named <white>" + name + "</white>."));
            } else {
                logger.info("[voucher] " + staffName + " deleted voucher " + name);
                reply(reply, Msg.good("Deleted voucher <white>" + name + "</white>. Any copies already given out that haven't been redeemed will no longer work."));
            }
        });
    }

    CompletableFuture<List<Template>> listTemplates() {
        return db.async(c -> {
            List<Template> list = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT name, command, item FROM pvp_voucher_templates");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new Template(rs.getString(1), rs.getString(2), rs.getString(3)));
                }
            }
            list.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.name(), b.name()));
            return list;
        });
    }

    /** The held item as the voucher's look (one item, with any old voucher tags removed). Base64 item text. */
    private String captureAppearance(String name, ItemStack held) {
        ItemStack look;
        if (held == null || held.getType().isAir()) {
            look = new ItemStack(Material.PAPER);
        } else {
            look = held.clone();
            look.setAmount(1);
        }
        ItemMeta meta = look.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().remove(idKey);
            meta.getPersistentDataContainer().remove(templateKey);
            if (!meta.hasDisplayName()) {
                meta.displayName(LEGACY.deserialize("&d&l" + name).decoration(TextDecoration.ITALIC, false));
            }
            look.setItemMeta(meta);
        }
        return Items.encode(new ItemStack[]{look});
    }

    // ------------------------------------------------------------------ issuing

    @Override
    public void registerHandler(String kindPrefix, KindHandler handler) {
        kindHandlers.put(kindPrefix, handler);
    }

    private @Nullable KindHandler handlerFor(@Nullable String kind) {
        if (kind == null) {
            return null;
        }
        for (Map.Entry<String, KindHandler> entry : kindHandlers.entrySet()) {
            if (kind.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    @Override
    public CompletableFuture<List<String>> issue(String kind, @Nullable Player issuedBy, UUID issuedToUuid, String issuedToName,
                                                 int amount, @Nullable String source) {
        UUID byUuid = issuedBy != null ? issuedBy.getUniqueId() : new UUID(0L, 0L);
        String byName = issuedBy != null ? issuedBy.getName() : source != null && !source.isBlank() ? source : "CONSOLE";
        int count = Math.max(1, Math.min(MAX_GIVE, amount));
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(newVoucherId());
        }
        return db.async(c -> Database.transaction(c, tx -> {
            insertInstances(tx, ids, kind, byUuid, byName, issuedToUuid, issuedToName);
            return List.copyOf(ids);
        })).thenApply(list -> {
            logger.info("[voucher] " + byName + " issued " + list.size() + "x " + kind + " to " + issuedToName + " " + list);
            return list;
        });
    }

    @Override
    public void tag(ItemStack item, String voucherId, String kind) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        List<Component> lore = meta.hasLore() && meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        meta.lore(withStatus(lore, STATUS_UNREDEEMED));
        meta.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, voucherId);
        meta.getPersistentDataContainer().set(templateKey, PersistentDataType.STRING, kind);
        item.setItemMeta(meta);
        item.setAmount(1);
    }

    /** /voucher give: records the copies first, then hands them over on the target's thread. */
    void give(@Nullable Player admin, String templateName, Player target, int amount, CommandSender reply) {
        if (amount < 1 || amount > MAX_GIVE) {
            reply(reply, Msg.bad("Amount must be between 1 and " + MAX_GIVE + "."));
            return;
        }
        UUID byUuid = admin != null ? admin.getUniqueId() : new UUID(0L, 0L);
        String byName = admin != null ? admin.getName() : "CONSOLE";
        UUID toUuid = target.getUniqueId();
        String toName = target.getName();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < amount; i++) {
            ids.add(newVoucherId());
        }
        db.async(c -> Database.transaction(c, tx -> {
            Template template = template(tx, templateName);
            if (template != null) {
                insertInstances(tx, ids, template.name(), byUuid, byName, toUuid, toName);
            }
            return template;
        })).whenComplete((template, e) -> {
            if (e != null) {
                logger.log(Level.WARNING, "Failed to give voucher " + templateName + " to " + toName, e);
                reply(reply, Msg.bad("Something went wrong giving that voucher - check the console."));
                return;
            }
            if (template == null) {
                reply(reply, Msg.bad("No voucher named <white>" + templateName + "</white>."));
                return;
            }
            Tasks.entity.later(target, () -> {
                PlayerInventory inventory = target.getInventory();
                for (String id : ids) {
                    // Checklist 6: a full inventory drops the rest at the player's feet.
                    for (ItemStack overflow : inventory.addItem(buildItem(template, id)).values()) {
                        target.getWorld().dropItem(target.getLocation(), overflow);
                    }
                }
                target.sendMessage(Msg.good("You received <white>" + amount + "</white>x <white>" + template.name() + "</white> voucher(s)!"));
                logger.info("[voucher] " + byName + " gave " + amount + "x " + template.name() + " to " + toName + " " + ids); // checklist 11
                reply(reply, Msg.good("Gave <white>" + amount + "</white>x <white>" + template.name() + "</white> to <white>" + Msg.escape(toName) + "</white>."));
            }, () -> {
                // The target left before the items could be handed over: the records have no physical copy, so drop them.
                db.asyncRun(c -> deleteInstances(c, ids));
                reply(reply, Msg.bad(Msg.escape(toName) + " left before the vouchers could be given; nothing was given."));
            }, 1L);
        });
    }

    private ItemStack buildItem(Template template, String voucherId) {
        ItemStack item;
        try {
            ItemStack[] decoded = Items.decode(template.item());
            item = decoded.length > 0 && decoded[0] != null && !decoded[0].isEmpty() ? decoded[0].clone() : new ItemStack(Material.PAPER);
        } catch (RuntimeException ex) {
            logger.log(Level.WARNING, "[voucher] The look of voucher " + template.name() + " could not be read; using paper", ex);
            item = new ItemStack(Material.PAPER);
        }
        tag(item, voucherId, template.name());
        return item;
    }

    private String newVoucherId() {
        byte[] bytes = new byte[6];
        random.nextBytes(bytes);
        return HexFormat.of().withUpperCase().formatHex(bytes);
    }

    // ------------------------------------------------------------------ reading tags

    /** The voucher ID stored in the item's PersistentDataContainer, or null. Names and lore are ignored. */
    @Nullable String voucherIdOf(@Nullable ItemStack item) {
        if (item == null || item.isEmpty() || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
    }

    private @Nullable String kindOf(@Nullable ItemStack item) {
        if (item == null || item.isEmpty() || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(templateKey, PersistentDataType.STRING);
    }

    // ------------------------------------------------------------------ redeeming

    /** Redeems the voucher in the player's main hand. Call on the player's thread (from the interact event). */
    void redeem(Player player) {
        PlayerInventory inventory = player.getInventory();
        ItemStack held = inventory.getItemInMainHand();
        String voucherId = voucherIdOf(held);
        if (voucherId == null) {
            return;
        }
        UUID uuid = player.getUniqueId();
        if (!redeeming.add(uuid)) {
            return; // checklist 3: one redeem in flight per player
        }
        String itemKind = kindOf(held);
        KindHandler preHandler = handlerFor(itemKind);
        if (preHandler != null) {
            Component refusal = preHandler.refuse(player, itemKind);
            if (refusal != null) {
                redeeming.remove(uuid);
                player.sendMessage(refusal);
                return;
            }
        }
        // Checklist 1 and 9: take exactly one, from the real stack in the hand, before anything is granted.
        ItemStack taken = held.clone();
        taken.setAmount(1);
        if (held.getAmount() > 1) {
            held.setAmount(held.getAmount() - 1);
            inventory.setItemInMainHand(held);
        } else {
            inventory.setItemInMainHand(null);
        }
        String name = player.getName();
        db.async(c -> Database.transaction(c, tx -> {
            String kind;
            boolean redeemed;
            String byName;
            String by;
            long at;
            try (PreparedStatement ps = tx.prepareStatement("SELECT template_name, redeemed, redeemed_by_name, redeemed_by, "
                    + "COALESCE(redeemed_at, 0) FROM pvp_voucher_instances WHERE voucher_id = ?")) {
                ps.setString(1, voucherId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return new RedeemResult(Status.UNKNOWN, null, null, null, 0);
                    }
                    kind = rs.getString(1);
                    redeemed = rs.getInt(2) != 0;
                    byName = rs.getString(3);
                    by = rs.getString(4);
                    at = rs.getLong(5);
                }
            }
            if (redeemed) {
                return new RedeemResult(Status.ALREADY, kind, byName, by == null ? null : UUID.fromString(by), at);
            }
            // Checklist 2: the single winner for this ID.
            try (PreparedStatement ps = tx.prepareStatement("UPDATE pvp_voucher_instances SET redeemed = 1, redeemed_by = ?, "
                    + "redeemed_by_name = ?, redeemed_at = ?, reward_given = 0 WHERE voucher_id = ? AND redeemed = 0")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, name);
                ps.setLong(3, System.currentTimeMillis());
                ps.setString(4, voucherId);
                if (ps.executeUpdate() != 1) {
                    return new RedeemResult(Status.ALREADY, kind, byName, null, 0);
                }
            }
            return new RedeemResult(Status.REDEEMED, kind, name, uuid, System.currentTimeMillis());
        })).whenComplete((result, e) -> {
            redeeming.remove(uuid);
            if (e != null) {
                logger.log(Level.WARNING, "Failed to redeem voucher " + voucherId + " for " + name, e);
                returnItem(player, voucherId, taken); // checklist 5: nothing was used, so the copy goes back
                Tasks.entity.run(player, () -> player.sendMessage(Msg.bad("Something went wrong redeeming that voucher - nothing was consumed, try again.")));
                return;
            }
            switch (result.status()) {
                case REDEEMED -> {
                    logger.info("[voucher] " + name + " redeemed voucher " + voucherId + " (" + result.kind() + ")"); // checklist 11
                    deliver(uuid, name, voucherId, true);
                }
                case UNKNOWN -> {
                    logger.warning("[voucher] " + name + " tried to redeem unknown voucher id " + voucherId + " - item removed.");
                    Tasks.entity.run(player, () -> player.sendMessage(Msg.bad("That voucher isn't valid.")));
                }
                case ALREADY -> {
                    Tasks.entity.run(player, () -> player.sendMessage(Msg.bad("That voucher has already been redeemed - a duplicate copy was detected and removed.")));
                    boolean own = uuid.equals(result.redeemedBy());
                    String when = result.redeemedAt() > 0 ? java.time.Instant.ofEpochMilli(result.redeemedAt()).toString() : "unknown time";
                    logger.warning("[voucher] " + name + " tried to redeem voucher " + voucherId + " (" + result.kind() + "), already redeemed by "
                            + result.redeemedByName() + " at " + when + (own ? " (their own leftover copy)" : "") + " - copy removed.");
                    if (!own) {
                        alertOnlineStaff("Duplicate voucher detected", name + " tried to redeem voucher " + voucherId + " (template '" + result.kind()
                                + "'), already redeemed by " + result.redeemedByName() + " at " + when + ". The duplicate copy was removed.");
                    }
                }
            }
        });
    }

    /**
     * Gives the reward of a redeemed voucher once. {@code reward_given} is flipped with a conditional UPDATE first, so
     * the immediate path and the next-join path can never both give it. Checklist 2 and 7.
     */
    private void deliver(UUID uuid, String playerName, String voucherId, boolean announce) {
        db.async(c -> Database.transaction(c, tx -> {
            try (PreparedStatement ps = tx.prepareStatement("UPDATE pvp_voucher_instances SET reward_given = 1 "
                    + "WHERE voucher_id = ? AND redeemed = 1 AND reward_given = 0 AND redeemed_by = ?")) {
                ps.setString(1, voucherId);
                ps.setString(2, uuid.toString());
                if (ps.executeUpdate() != 1) {
                    return null;
                }
            }
            String kind;
            try (PreparedStatement ps = tx.prepareStatement("SELECT template_name FROM pvp_voucher_instances WHERE voucher_id = ?")) {
                ps.setString(1, voucherId);
                try (ResultSet rs = ps.executeQuery()) {
                    kind = rs.next() ? rs.getString(1) : "";
                }
            }
            Template template = template(tx, kind);
            return new Delivery(voucherId, kind, template == null ? null : template.command());
        })).whenComplete((delivery, e) -> {
            if (e != null) {
                logger.log(Level.WARNING, "[voucher] Could not give the reward of voucher " + voucherId + " to " + playerName
                        + "; it is retried on their next join", e);
                return;
            }
            if (delivery == null) {
                return; // already given
            }
            Runnable revert = () -> revertGiven(voucherId);
            KindHandler handler = handlerFor(delivery.kind());
            Player player = Bukkit.getPlayer(uuid);
            if (handler != null) {
                if (player == null) {
                    revert.run(); // the handler needs the player online; retried on next join
                    return;
                }
                boolean scheduled;
                pendingDispatch.put(voucherId, revert);
                Runnable retired = () -> {
                    if (pendingDispatch.remove(voucherId) != null) {
                        revert.run();
                    }
                };
                try {
                    scheduled = Tasks.entity.later(player, () -> {
                        if (pendingDispatch.remove(voucherId) == null) {
                            return; // re-queued at shutdown
                        }
                        try {
                            handler.redeem(player, delivery.kind(), voucherId);
                        } catch (RuntimeException ex) {
                            logger.log(Level.WARNING, "[voucher] The reward for voucher " + voucherId + " (" + delivery.kind() + ") failed for " + playerName, ex);
                            player.sendMessage(Msg.bad("Voucher redeemed, but its reward could not be given - let staff know your voucher ID: <white>" + voucherId + "</white>"));
                            alertOnlineStaff("Voucher reward failed", playerName + " redeemed voucher " + voucherId + " (" + delivery.kind() + ") but the reward threw an error - see the console.");
                        }
                    }, retired, 1L) != null;
                } catch (RuntimeException notRunning) {
                    scheduled = false;
                }
                if (!scheduled) {
                    retired.run(); // player gone or plugin stopping: retried on next join
                }
                return;
            }
            if (delivery.command() == null) {
                if (player != null) {
                    Tasks.entity.run(player, () -> player.sendMessage(Msg.bad("Voucher redeemed, but its reward has since been removed by an admin - let staff know your voucher ID: <white>" + voucherId + "</white>")));
                }
                alertOnlineStaff("Voucher redeemed with no reward", playerName + " redeemed voucher " + voucherId + " (template '" + delivery.kind() + "'), but that template no longer exists - nothing was run.");
                return;
            }
            dispatch(voucherId, delivery.command(), uuid, playerName, revert);
            if (player != null && announce) {
                Tasks.entity.run(player, () -> player.sendMessage(Msg.good("Voucher redeemed!")));
            }
        });
    }

    private void revertGiven(String voucherId) {
        try {
            db.sync(c -> {
                try (PreparedStatement ps = c.prepareStatement("UPDATE pvp_voucher_instances SET reward_given = 0 WHERE voucher_id = ?")) {
                    ps.setString(1, voucherId);
                    return ps.executeUpdate();
                }
            });
        } catch (SQLException ex) {
            logger.log(Level.SEVERE, "[voucher] The reward of voucher " + voucherId + " was not given and could not be re-queued; give it by hand", ex);
        }
    }

    /** Runs the console command on the global thread. If it never gets to run (shutdown), the reward is re-queued. */
    private void dispatch(String voucherId, String command, UUID uuid, String playerName, Runnable revert) {
        String resolved = command.replace("%player%", playerName).replace("{player}", playerName).replace("{uuid}", uuid.toString());
        if (resolved.startsWith("/")) {
            resolved = resolved.substring(1);
        }
        if (resolved.isBlank()) {
            return;
        }
        String finalCommand = resolved;
        pendingDispatch.put(voucherId, revert);
        try {
            Tasks.global.run(() -> {
                if (pendingDispatch.remove(voucherId) == null) {
                    return; // re-queued at shutdown
                }
                try {
                    if (!Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCommand)) {
                        logger.severe("[voucher] Command for voucher " + voucherId + " failed for " + playerName + ": /" + finalCommand
                                + " (unknown command). The voucher is used, so run it by hand if the player is owed it.");
                    }
                } catch (Throwable failure) {
                    logger.log(Level.SEVERE, "[voucher] Command for voucher " + voucherId + " threw for " + playerName + ": /" + finalCommand, failure);
                }
            });
        } catch (RuntimeException notRunning) {
            if (pendingDispatch.remove(voucherId) != null) {
                revert.run();
            }
        }
    }

    // ------------------------------------------------------------------ returns and next-join delivery

    /** Gives a taken copy back on the player's thread; if they left, stores it for their next join. Checklist 5 and 6. */
    private void returnItem(Player player, String voucherId, ItemStack item) {
        UUID uuid = player.getUniqueId();
        String data = Items.encode(new ItemStack[]{item});
        Runnable store = () -> db.asyncRun(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO pvp_voucher_returns (uuid, voucher_id, item, created_at) VALUES (?, ?, ?, ?)")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, voucherId);
                ps.setString(3, data);
                ps.setLong(4, System.currentTimeMillis());
                ps.executeUpdate();
            }
        }).exceptionally(e -> {
            logger.log(Level.SEVERE, "[voucher] Could not store voucher " + voucherId + " to return to " + uuid + "; it is unredeemed, re-issue it by hand", e);
            return null;
        });
        try {
            if (Tasks.entity.later(player, () -> giveOrDrop(player, item), store, 1L) == null) {
                store.run();
            }
        } catch (RuntimeException notRunning) {
            store.run();
        }
    }

    private static void giveOrDrop(Player player, ItemStack item) {
        for (ItemStack overflow : player.getInventory().addItem(item).values()) {
            player.getWorld().dropItem(player.getLocation(), overflow);
        }
    }

    /** On join: returns stored copies and gives rewards that were redeemed but never given. */
    void onJoin(Player player) {
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        db.async(c -> {
            List<StoredReturn> candidates = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id, voucher_id, item FROM pvp_voucher_returns WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        candidates.add(new StoredReturn(rs.getLong(1), rs.getString(2), rs.getString(3)));
                    }
                }
            }
            // Checklist 2 and 12: delete each row first and keep only the rows this call deleted, so two quick joins
            // (or a join and a sweep) can never both give the same copy. Items are given only after this.
            List<StoredReturn> returns = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM pvp_voucher_returns WHERE id = ?")) {
                for (StoredReturn row : candidates) {
                    ps.setLong(1, row.id());
                    if (ps.executeUpdate() == 1) {
                        returns.add(row);
                    }
                }
            }
            List<String> owed = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT voucher_id FROM pvp_voucher_instances WHERE redeemed_by = ? AND redeemed = 1 AND reward_given = 0")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        owed.add(rs.getString(1));
                    }
                }
            }
            return Map.entry(returns, owed);
        }).whenComplete((found, e) -> {
            if (e != null) {
                logger.log(Level.WARNING, "[voucher] Could not check returned vouchers for " + name, e);
                return;
            }
            for (String voucherId : found.getValue()) {
                deliver(uuid, name, voucherId, true);
            }
            List<StoredReturn> won = found.getKey();
            if (won.isEmpty()) {
                return;
            }
            // The rows are already deleted: if the player is gone before the give runs, store them again (checklist 5).
            Runnable putBack = () -> restoreReturns(uuid, won);
            try {
                if (Tasks.entity.later(player, () -> {
                    if (!player.isOnline()) {
                        putBack.run();
                        return;
                    }
                    boolean gaveAny = false;
                    for (StoredReturn row : won) {
                        ItemStack[] items;
                        try {
                            items = Items.decode(row.item());
                        } catch (RuntimeException bad) {
                            logger.log(Level.SEVERE, "[voucher] Could not read returned voucher " + row.voucherId() + " for " + name
                                    + "; it is unredeemed, re-issue it by hand", bad);
                            continue;
                        }
                        if (items.length > 0 && items[0] != null && !items[0].isEmpty()) {
                            giveOrDrop(player, items[0]);
                            gaveAny = true;
                        }
                    }
                    if (gaveAny) {
                        PlayerFiles.save(player); // checklist 12: rows deleted, items given, now persist the give
                        player.sendMessage(Msg.neutral("A voucher that couldn't be redeemed earlier was returned to you."));
                    }
                }, putBack, 1L) == null) {
                    putBack.run();
                }
            } catch (RuntimeException notRunning) {
                putBack.run();
            }
        });
    }

    /** A row of pvp_voucher_returns, item still encoded. */
    private record StoredReturn(long id, String voucherId, String item) {
    }

    /** Re-stores returns this server deleted but could not give (the player left first). */
    private void restoreReturns(UUID uuid, List<StoredReturn> rows) {
        db.asyncRun(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO pvp_voucher_returns (uuid, voucher_id, item, created_at) VALUES (?, ?, ?, ?)")) {
                for (StoredReturn row : rows) {
                    ps.setString(1, uuid.toString());
                    ps.setString(2, row.voucherId());
                    ps.setString(3, row.item());
                    ps.setLong(4, System.currentTimeMillis());
                    ps.executeUpdate();
                }
            }
        }).exceptionally(e -> {
            logger.log(Level.SEVERE, "[voucher] Could not re-store " + rows.size() + " returned voucher(s) for " + uuid
                    + "; they are unredeemed, re-issue them by hand", e);
            return null;
        });
    }

    // ------------------------------------------------------------------ /voucher check

    /** Call on the player's thread. */
    void check(Player player) {
        String voucherId = voucherIdOf(player.getInventory().getItemInMainHand());
        if (voucherId == null) {
            player.sendMessage(Msg.neutral("You're not holding a voucher."));
            return;
        }
        db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT template_name, issued_to_name, issued_by_name, redeemed, "
                    + "redeemed_by_name FROM pvp_voucher_instances WHERE voucher_id = ?")) {
                ps.setString(1, voucherId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new String[]{rs.getString(1), rs.getString(2), rs.getString(3),
                            String.valueOf(rs.getInt(4)), rs.getString(5)} : null;
                }
            }
        }).whenComplete((row, e) -> Tasks.entity.run(player, () -> {
            if (e != null) {
                logger.log(Level.WARNING, "Failed to check voucher " + voucherId + " for " + player.getName(), e);
                player.sendMessage(Msg.bad("Something went wrong checking that voucher - try again."));
                return;
            }
            ItemStack held = player.getInventory().getItemInMainHand();
            if (!voucherId.equals(voucherIdOf(held))) {
                return; // they switched items meanwhile
            }
            if (row == null) {
                player.sendMessage(Msg.bad("That voucher isn't valid - no record of it exists."));
                return;
            }
            if (player.hasPermission(STAFF_PERMISSION)) {
                player.sendMessage(Msg.neutral("Voucher ID <white>" + voucherId + "</white> (" + Msg.escape(row[0]) + "), issued to <white>"
                        + Msg.escape(String.valueOf(row[1])) + "</white> by " + Msg.escape(String.valueOf(row[2])) + "."));
            }
            if ("1".equals(row[3])) {
                refreshLore(player, held, "&fStatus: &cRedeemed &8(" + row[4] + ")");
                player.sendMessage(Msg.bad("This voucher has <white>already been redeemed</white> by <white>" + Msg.escape(String.valueOf(row[4]))
                        + "</white> - redeeming it now will fail. Let a staff member know."));
            } else {
                refreshLore(player, held, STATUS_UNREDEEMED);
                player.sendMessage(Msg.good("This voucher is <white>still valid</white> and hasn't been redeemed yet."));
            }
        }));
    }

    private static void refreshLore(Player player, ItemStack held, String statusLine) {
        ItemMeta meta = held.getItemMeta();
        if (meta == null) {
            return;
        }
        List<Component> lore = meta.hasLore() && meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        meta.lore(withStatus(lore, statusLine));
        held.setItemMeta(meta);
        player.getInventory().setItemInMainHand(held);
    }

    static List<Component> withStatus(List<Component> lore, String statusLine) {
        List<Component> out = new ArrayList<>();
        for (Component line : lore) {
            String plain = PLAIN.serialize(line);
            if (plain.contains(STATUS_LABEL) || plain.contains(OLD_STATUS_LABEL) || plain.trim().startsWith(OLD_ID_PREFIX)) {
                continue;
            }
            out.add(line);
        }
        Component status = LEGACY.deserialize(statusLine).decoration(TextDecoration.ITALIC, false);
        int last = out.size() - 1;
        if (last >= 1 && isRedeemPrompt(out.get(last)) && PLAIN.serialize(out.get(last - 1)).isBlank()) {
            out.add(last - 1, status);
            return out;
        }
        if (!out.isEmpty() && !PLAIN.serialize(out.get(out.size() - 1)).isBlank()) {
            out.add(Component.empty());
        }
        out.add(status);
        return out;
    }

    private static boolean isRedeemPrompt(Component line) {
        String plain = PLAIN.serialize(line).toLowerCase(Locale.ROOT);
        return plain.contains("redeem") || plain.contains("ʀᴇᴅᴇᴇᴍ");
    }

    // ------------------------------------------------------------------ duplicate sweep

    /**
     * Finds voucher IDs held more than once across online players' inventories and ender chests, and removes the extra
     * copies (the original issuee keeps one). Each player's items are read and changed on that player's thread.
     */
    CompletableFuture<Integer> scanForDuplicates() {
        Map<String, Integer> totals = new ConcurrentHashMap<>();
        Map<String, Set<UUID>> holders = new ConcurrentHashMap<>();
        List<CompletableFuture<Void>> reads = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            CompletableFuture<Void> read = new CompletableFuture<>();
            reads.add(read);
            try {
                if (Tasks.entity.later(player, () -> {
                    try {
                        count(player, player.getInventory(), totals, holders);
                        count(player, player.getEnderChest(), totals, holders);
                    } finally {
                        read.complete(null);
                    }
                }, () -> read.complete(null), 1L) == null) {
                    read.complete(null);
                }
            } catch (RuntimeException notRunning) {
                read.complete(null);
            }
        }
        return CompletableFuture.allOf(reads.toArray(new CompletableFuture[0])).thenCompose(v -> {
            List<String> duplicates = new ArrayList<>();
            totals.forEach((id, n) -> {
                if (n >= 2) {
                    duplicates.add(id);
                }
            });
            if (duplicates.isEmpty()) {
                return CompletableFuture.completedFuture(0);
            }
            return db.async(c -> {
                Map<String, UUID> issuedTo = new HashMap<>();
                try (PreparedStatement ps = c.prepareStatement("SELECT issued_to FROM pvp_voucher_instances WHERE voucher_id = ?")) {
                    for (String id : duplicates) {
                        ps.setString(1, id);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next() && rs.getString(1) != null) {
                                issuedTo.put(id, UUID.fromString(rs.getString(1)));
                            }
                        }
                    }
                }
                return issuedTo;
            }).thenApply(issuedTo -> {
                for (String id : duplicates) {
                    resolveDuplicate(id, totals.get(id), holders.getOrDefault(id, Set.of()), issuedTo.get(id));
                }
                return duplicates.size();
            });
        });
    }

    private void count(Player player, Inventory inventory, Map<String, Integer> totals, Map<String, Set<UUID>> holders) {
        for (ItemStack item : inventory.getContents()) {
            String id = voucherIdOf(item);
            if (id != null) {
                totals.merge(id, item.getAmount(), Integer::sum);
                holders.computeIfAbsent(id, k -> ConcurrentHashMap.newKeySet()).add(player.getUniqueId());
            }
        }
    }

    private void resolveDuplicate(String voucherId, int total, Set<UUID> holderIds, @Nullable UUID issuedTo) {
        // The keeper is chosen up front so no two threads both decide to keep a copy.
        UUID keeper = issuedTo != null && holderIds.contains(issuedTo) ? issuedTo : null;
        List<String> names = new ArrayList<>();
        for (UUID id : holderIds) {
            Player player = Bukkit.getPlayer(id);
            if (player == null) {
                continue;
            }
            names.add(player.getName());
            boolean keepOne = id.equals(keeper);
            Tasks.entity.run(player, () -> {
                boolean[] kept = {!keepOne};
                strip(player.getInventory(), voucherId, kept);
                strip(player.getEnderChest(), voucherId, kept);
                if (!keepOne) {
                    player.sendMessage(Msg.bad("A duplicate voucher was found in your inventory and has been removed."));
                }
            });
        }
        String outcome = keeper != null ? " - the original recipient's copy was kept, extras removed" : " - all copies removed";
        logger.warning("[voucher] Duplicate voucher " + voucherId + " found physically existing " + total + " time(s) (held by "
                + String.join(", ", names) + ")" + outcome + ".");
        alertOnlineStaff("Duplicate voucher confiscated", "Voucher " + voucherId + " was found existing " + total + " time(s) (held by "
                + String.join(", ", names) + ")" + outcome + ".");
    }

    /** Removes every copy of the ID, except one if {@code kept[0]} is still false. Player's thread. */
    private void strip(Inventory inventory, String voucherId, boolean[] kept) {
        ItemStack[] contents = inventory.getContents();
        BiConsumer<Integer, ItemStack> setter = inventory::setItem;
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (!voucherId.equals(voucherIdOf(item))) {
                continue;
            }
            if (!kept[0]) {
                if (item.getAmount() > 1) {
                    item.setAmount(1);
                    setter.accept(slot, item);
                }
                kept[0] = true;
                continue;
            }
            setter.accept(slot, null);
        }
    }

    // ------------------------------------------------------------------ helpers

    private void alertOnlineStaff(String title, String description) {
        Component headline = Msg.bad(Msg.escape(title));
        Component body = Msg.parse("<gray>" + Msg.escape(description) + "</gray>");
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission(STAFF_PERMISSION)) {
                Tasks.entity.run(staff, () -> {
                    staff.sendMessage(headline);
                    staff.sendMessage(body);
                });
            }
        }
    }

    private static void reply(CommandSender to, Component message) {
        Tasks.forSender(to, () -> to.sendMessage(message));
    }

    private static @Nullable Template template(Connection c, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT name, command, item FROM pvp_voucher_templates WHERE name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Template(rs.getString(1), rs.getString(2), rs.getString(3)) : null;
            }
        }
    }

    private static void insertInstances(Connection c, List<String> ids, String kind, UUID byUuid, String byName,
                                        UUID toUuid, String toName) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO pvp_voucher_instances (voucher_id, template_name, issued_by, "
                + "issued_by_name, issued_to, issued_to_name, issued_at) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            long now = System.currentTimeMillis();
            for (String id : ids) {
                ps.setString(1, id);
                ps.setString(2, kind);
                ps.setString(3, byUuid.toString());
                ps.setString(4, byName);
                ps.setString(5, toUuid.toString());
                ps.setString(6, toName);
                ps.setLong(7, now);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static void deleteInstances(Connection c, List<String> ids) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM pvp_voucher_instances WHERE voucher_id = ? AND redeemed = 0")) {
            for (String id : ids) {
                ps.setString(1, id);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** At shutdown: re-queues rewards whose console command had not run yet, so they are given on next join. */
    void shutdown() {
        for (String voucherId : List.copyOf(pendingDispatch.keySet())) {
            Runnable revert = pendingDispatch.remove(voucherId);
            if (revert != null) {
                revert.run();
            }
        }
    }
}
