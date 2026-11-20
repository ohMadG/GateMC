package me.Evan52098.genscore.wipe;

import me.Evan52098.genscore.player.PlayerDataService;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.storage.Database;
import me.Evan52098.genscore.util.Msg;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Wipes and restores (BlockvilleCore's PlayerWipeService, OfflineWipeRunner and WipeRestoreService, for one server).
 *
 * <p>How a wipe stays safe:
 * <ol>
 *   <li><b>Explicit confirmation.</b> {@code wipeplayer ... full confirm} only runs within the confirm window of a
 *       preview by the same person; the menu asks for a staff-only reason; the season wipe and a season restore only
 *       run from the console, after a preview.</li>
 *   <li><b>No live session can save over it.</b> The player is locked out at pre-login (before PlayerDataService loads
 *       anyone), kicked if online, and the wipe waits until PlayerDataService has let go of their data. The database
 *       work then runs through {@code PlayerDataService.runSerialized}, in the same queue as that player's saves, so it
 *       lands after their quit save and before any later load. Open vault views are closed first (their save lands
 *       during the settle wait), and modules can flush live state through {@link WipeParticipant}.</li>
 *   <li><b>Backup first.</b> Every row about to change is streamed to a backup file, synced to disk and renamed into
 *       place; only then is anything deleted. {@code /gensadmin wiperestore} puts a backup back, after first taking a
 *       copy of the current rows, so a restore can itself be undone.</li>
 *   <li><b>Crash-safe.</b> Every run is journalled in {@code pvp_wipe_runs} (STARTED, BACKED_UP, DONE). All deletes,
 *       the armed inventory wipe and the DONE mark are one transaction: a crash leaves either the whole wipe or none of
 *       it. On startup a run that never reached DONE is marked ABANDONED and reported (nothing was deleted; its backup
 *       file stays); the wipe can simply be run again.</li>
 * </ol>
 * Left out from BlockvilleCore: the cross-server lease and data-epoch fence, the kick relay to other servers, the
 * island (BentoBox world) section, PlayerPoints credits and the panel hold key in Redis; none exist on one server.
 */
public final class WipeService {

    /** A module that keeps a player's data in memory can write it down (or close it) before a wipe exports. */
    public interface WipeParticipant {
        /** {@code player} is null for a season wipe (everyone). Runs off the game threads; must not block. */
        CompletableFuture<Void> beforeWipe(@Nullable UUID player);
    }

    record Settings(long settleTicks, long maxWaitTicks, long runTimeoutSeconds, long confirmWindowMinutes,
                    @Nullable Map<String, List<String>> seasonKeep) {
    }

    private record Outcome(File backup, List<String> lines, String receipt, int rows) {
    }

    static final String ALL = "*everyone*";
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private static final Set<String> PLAYER_VISIBLE = Set.of("balance", "homes", "playervaults", "inventory", "enderchest", "experience",
            "playtime", "nickname", "msg_prefs");

    private final Database db;
    private final PlayerDataService players;
    private final WipeRegistry registry;
    private final WipeStore store;
    private final WipeLocks locks;
    private final WipeAudit audit;
    private final Logger logger;
    private final Map<String, Long> armedAt = new ConcurrentHashMap<>();
    private final List<WipeParticipant> participants = new CopyOnWriteArrayList<>();
    private volatile Settings settings;

    WipeService(Database db, PlayerDataService players, WipeRegistry registry, WipeStore store, WipeLocks locks, WipeAudit audit,
                Logger logger, Settings settings) {
        this.db = db;
        this.players = players;
        this.registry = registry;
        this.store = store;
        this.locks = locks;
        this.audit = audit;
        this.logger = logger;
        this.settings = settings;
    }

    void settings(Settings settings) {
        this.settings = settings;
    }

    WipeStore store() {
        return store;
    }

    WipeRegistry registry() {
        return registry;
    }

    public void addParticipant(WipeParticipant participant) {
        participants.add(participant);
    }

    // ================================================================== crash recovery

    /** Startup only (synchronous). Runs that never reached DONE changed nothing (one transaction); say so. */
    void recoverJournal() {
        try {
            List<String[]> open = db.sync(c -> {
                List<String[]> rows = new ArrayList<>();
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT wipe_id, kind, target_name, scope, state, backup_file FROM pvp_wipe_runs WHERE state IN ('STARTED', 'BACKED_UP')")) {
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            rows.add(new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)});
                        }
                    }
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE pvp_wipe_runs SET state = 'ABANDONED', finished_at = ? WHERE state IN ('STARTED', 'BACKED_UP')")) {
                    ps.setLong(1, System.currentTimeMillis());
                    ps.executeUpdate();
                }
                return rows;
            });
            for (String[] r : open) {
                audit.note("The " + r[1] + " " + r[3] + " run " + r[0] + " of " + r[2] + " did not finish before the server stopped (it had reached " + r[4]
                        + "). Its database changes are all-or-nothing, so nothing was changed" + (r[5] == null ? "" : "; the backup file " + r[5] + " stays")
                        + ". Run it again if it is still wanted.");
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "[wipe] Could not check for unfinished wipes", e);
        }
    }

    // ================================================================== plans

    WipePlan plan(WipeScope scope) {
        WipeKeepList keep = WipeKeepList.resolve(settings.seasonKeep(), WipeRegistry.shippedKeep());
        return WipePlan.build(scope, keep, registry.sections());
    }

    private static String keepSource(WipePlan plan) {
        return plan.keepListSource() == WipeKeepList.Source.CONFIG
                ? "wipe.season.keep in config.yml"
                : "the built-in default, because config.yml has no wipe.season.keep block";
    }

    // ================================================================== single player: preview / confirm / custom

    public void preview(CommandSender sender, String targetName, WipeScope scope) {
        UUID senderId = senderId(sender);
        String actor = sender.getName();
        resolve(senderId, targetName, record -> {
            WipePlan plan = plan(scope);
            if (plan.refusedForEmptyKeepList()) {
                notify(senderId, "&cThe season keep-list (" + keepSource(plan) + ") keeps nothing, so this season wipe would take everything. A wipe that keeps "
                        + "nothing is a full wipe: run &e/gensadmin wipeplayer " + record.name() + " full preview &cif that is what you mean. Nothing was deleted.");
                return;
            }
            db.async(c -> counts(c, plan, record.uuid().toString())).whenComplete((counts, error) -> {
                if (error != null) {
                    logger.log(Level.WARNING, "Could not count the rows of " + record.name(), error);
                    notify(senderId, "&cThe rows of " + record.name() + " could not be counted (" + reason(error) + "), so there is no preview and nothing was armed.");
                    return;
                }
                printPreview(senderId, record.name(), record.uuid().toString(), plan, counts,
                        "/gensadmin wipeplayer " + record.name() + " " + scope.id() + " confirm", Bukkit.getPlayer(record.uuid()) != null);
                arm(actor, record.uuid().toString(), scope);
            });
        });
    }

    public void confirm(CommandSender sender, String targetName, WipeScope scope) {
        UUID senderId = senderId(sender);
        String actor = sender.getName();
        resolve(senderId, targetName, record -> {
            String state = check(actor, record.uuid().toString(), scope);
            if (state != null) {
                notify(senderId, state.equals("expired")
                        ? "&cThe preview of " + record.name() + " is older than " + settings.confirmWindowMinutes() + " minutes. Run it again and confirm from what it prints."
                        : "&cRun the preview first: &e/gensadmin wipeplayer " + record.name() + " " + scope.id() + " preview");
                return;
            }
            WipePlan plan = plan(scope);
            if (plan.refusedForEmptyKeepList()) {
                notify(senderId, "&cThe season keep-list keeps nothing; nothing was deleted.");
                audit.refused(actor, record.name(), scope.id(), "the season keep-list keeps nothing");
                return;
            }
            if (!consume(actor, record.uuid().toString(), scope)) {
                notify(senderId, "&cRun the preview first: &e/gensadmin wipeplayer " + record.name() + " " + scope.id() + " preview");
                return;
            }
            notify(senderId, "&cWiping &e" + record.name() + " &c(" + scope.id() + ")...");
            run(senderId, actor, record.uuid(), record.name(), plan, generateId(), null);
        });
    }

    /** The menu's and the panel's pick-and-choose wipe: everything not in the keep-list goes. */
    public void confirmCustom(CommandSender sender, String targetName, WipeKeepList keepList, String reason) {
        UUID senderId = senderId(sender);
        String actor = sender.getName();
        resolve(senderId, targetName, record -> {
            WipePlan plan = WipePlan.build(WipeScope.SEASON, keepList, registry.sections());
            if (plan.wipedSectionCount() == 0) {
                notify(senderId, "&cNothing is set to be wiped, so nothing was done.");
                return;
            }
            notify(senderId, "&cWiping &e" + record.name() + " &c(custom)...");
            run(senderId, actor, record.uuid(), record.name(), plan, generateId(), reason);
        });
    }

    private void run(@Nullable UUID senderId, String actor, UUID playerId, String name, WipePlan plan, String wipeId, @Nullable String reason) {
        if (!locks.begin(playerId)) {
            notify(senderId, "&c" + name + " is being wiped or restored right now. Wait for that to finish; nothing was done.");
            audit.refused(actor, name, plan.scope().id(), "a wipe or restore of them is already running");
            return;
        }
        AtomicReference<Outcome> outcome = new AtomicReference<>();
        evict(senderId, playerId, name)
                .thenCompose(v -> beforeWipe(playerId))
                .thenCompose(v -> settle())
                .thenCompose(v -> players.runSerialized(playerId, c -> outcome.set(wipeRows(c, playerId, name, plan, actor, wipeId, reason))))
                .whenComplete((v, error) -> {
                    locks.end(playerId);
                    if (error != null) {
                        notify(senderId, "&cThe wipe of " + name + " stopped before deleting anything (" + reason(error) + ").");
                        audit.refused(actor, name, plan.scope().id(), reason(error));
                        logger.log(Level.SEVERE, "[wipe] The wipe of " + name + " stopped before deleting", unwrap(error));
                        return;
                    }
                    Outcome done = outcome.get();
                    notify(senderId, "&7Backup written: &e" + done.backup().getName() + " &7(" + size(done.backup().length()) + ")");
                    for (String line : done.lines()) {
                        notify(senderId, "&8- " + line);
                    }
                    notify(senderId, "&aWiped &e" + name + "&a (" + plan.scope().id() + "): &e" + done.rows() + " &arows changed. Backup &e" + done.backup().getName()
                            + "&a. &7Wipe id: &b" + wipeId);
                    audit.wiped(actor, name + " (" + playerId + ")", plan.scope().id(), wipeId, done.backup(), done.receipt(), reason);
                });
    }

    /** Database thread, inside the player's serialized queue. Backup, then one transaction for every change. */
    private Outcome wipeRows(Connection c, UUID playerId, String name, WipePlan plan, String actor, String wipeId, @Nullable String reason) throws SQLException {
        String key = playerId.toString();
        long now = System.currentTimeMillis();
        List<String> values = valuesWiped(plan);
        insertRun(c, wipeId, "player", key, name, plan.scope().id(), actor, reason, now);
        Set<String> missing = new HashSet<>();
        WipeStore.Written written;
        try {
            written = store.export(c, new WipeStore.Header(1, "player", wipeId, plan.scope().id(), playerId, name, actor, now, values), plan.wiped(), missing);
        } catch (IOException e) {
            throw new SQLException("the backup could not be written (" + e.getMessage() + "), so nothing was deleted", e);
        }
        updateRun(c, wipeId, "BACKED_UP", written.file().getName(), null);
        List<String> lines = new ArrayList<>();
        int[] total = {0};
        String receipt = Database.transaction(c, tx -> {
            WipeStore.statementTimeout(tx, db.isPostgres(), settings.runTimeoutSeconds());
            List<String> parts = applyPlan(tx, plan, key, missing, lines, total);
            if (!values.isEmpty()) {
                armPending(tx, playerId, wipeId, actor, plan, false);
            }
            String r = String.join(" | ", parts) + " | wipe id " + wipeId;
            updateRun(tx, wipeId, "DONE", written.file().getName(), r);
            return r;
        });
        return new Outcome(written.file(), lines, receipt, total[0]);
    }

    /** Deletes / resets every wiped table section. Returns receipt parts; fills the per-module lines. */
    private List<String> applyPlan(Connection tx, WipePlan plan, @Nullable String key, Set<String> missing, List<String> lines, int[] total) throws SQLException {
        List<String> parts = new ArrayList<>();
        for (WipePlan.Line line : plan.lines()) {
            if (line.skipped()) {
                lines.add("&e" + line.moduleId() + " &7kept");
                parts.add(line.moduleId() + ": kept");
                continue;
            }
            int deleted = 0;
            int reset = 0;
            List<String> notes = new ArrayList<>();
            boolean inventory = false;
            for (WipeSection section : line.wiped()) {
                if (!section.isTable()) {
                    inventory = true;
                    continue;
                }
                if (missing.contains(section.table())) {
                    notes.add(WipeReceipts.line("core.table-missing", "section", section.label(), "table", section.table()));
                    continue;
                }
                int changed = WipeStore.apply(tx, section, key);
                if (section.isReset()) {
                    reset += changed;
                } else {
                    deleted += changed;
                }
            }
            total[0] += deleted + reset;
            List<String> detail = new ArrayList<>();
            detail.add(WipeReceipts.line("core.rows", "count", deleted));
            if (reset > 0) {
                detail.add(WipeReceipts.line("core.columns-reset", "count", reset));
            }
            if (inventory) {
                detail.add(WipeReceipts.line("core.inventory-armed"));
            }
            if (!line.kept().isEmpty()) {
                detail.add(WipeReceipts.line("core.sections-kept", "count", line.kept().size()));
            }
            detail.addAll(notes);
            lines.add("&e" + line.moduleId() + " &7" + String.join(", ", detail));
            parts.add(line.moduleId() + ": " + String.join(", ", detail));
        }
        return parts;
    }

    private static List<String> valuesWiped(WipePlan plan) {
        List<String> values = new ArrayList<>();
        for (WipeSection s : plan.wiped()) {
            if (!s.isTable()) {
                values.add(s.key());
            }
        }
        return values;
    }

    /** Arms the inventory / ender chest / experience wipe for the next join (one player, or everyone known). */
    private void armPending(Connection tx, @Nullable UUID player, String wipeId, String actor, WipePlan plan, boolean everyone) throws SQLException {
        boolean inv = plan.wipes(WipeRegistry.CORE, "inventory");
        boolean ender = plan.wipes(WipeRegistry.CORE, "enderchest");
        boolean xp = plan.wipes(WipeRegistry.CORE, "experience");
        StringBuilder labels = new StringBuilder();
        for (WipeSection s : plan.wiped()) {
            if (PLAYER_VISIBLE.contains(s.id())) {
                labels.append(labels.isEmpty() ? "" : ", ").append(s.label());
            }
        }
        String merge = " ON CONFLICT (uuid) DO UPDATE SET wipe_id = excluded.wipe_id, requested_by = excluded.requested_by, requested_at = excluded.requested_at, "
                + "wipe_inventory = CASE WHEN pvp_wipe_pending.wipe_inventory = 1 OR excluded.wipe_inventory = 1 THEN 1 ELSE 0 END, "
                + "wipe_ender = CASE WHEN pvp_wipe_pending.wipe_ender = 1 OR excluded.wipe_ender = 1 THEN 1 ELSE 0 END, "
                + "wipe_xp = CASE WHEN pvp_wipe_pending.wipe_xp = 1 OR excluded.wipe_xp = 1 THEN 1 ELSE 0 END, "
                + "labels = excluded.labels, full_wipe = excluded.full_wipe";
        String sql = everyone
                ? "INSERT INTO pvp_wipe_pending (uuid, wipe_id, requested_by, requested_at, wipe_inventory, wipe_ender, wipe_xp, labels, full_wipe) "
                + "SELECT uuid, ?, ?, ?, ?, ?, ?, ?, ? FROM pvp_players WHERE 1 = 1" + merge
                : "INSERT INTO pvp_wipe_pending (uuid, wipe_id, requested_by, requested_at, wipe_inventory, wipe_ender, wipe_xp, labels, full_wipe) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)" + merge;
        try (PreparedStatement ps = tx.prepareStatement(sql)) {
            int i = 1;
            if (!everyone) {
                ps.setString(i++, String.valueOf(player));
            }
            ps.setString(i++, wipeId);
            ps.setString(i++, actor);
            ps.setLong(i++, System.currentTimeMillis());
            ps.setInt(i++, inv ? 1 : 0);
            ps.setInt(i++, ender ? 1 : 0);
            ps.setInt(i++, xp ? 1 : 0);
            ps.setString(i++, labels.toString());
            ps.setInt(i, plan.scope() == WipeScope.FULL ? 1 : 0);
            ps.executeUpdate();
        }
    }

    private void printPreview(@Nullable UUID senderId, String name, String uuid, WipePlan plan, Map<String, Long> counts, String confirmCommand, boolean online) {
        notify(senderId, "&e" + plan.scope().id() + " wipe preview for " + name + " &7(" + uuid + ")&e, " + plan.lines().size() + " modules answered:");
        if (plan.scope() == WipeScope.SEASON) {
            notify(senderId, "&7Keep-list in force: &e" + keepSource(plan));
        }
        long rows = 0;
        for (WipePlan.Line line : plan.lines()) {
            if (line.skipped()) {
                notify(senderId, "&e" + line.moduleId() + "&7: everything kept");
                continue;
            }
            notify(senderId, "&e" + line.moduleId() + "&7: " + WipeReceipts.describe(line.moduleId()));
            for (WipeSection section : line.wiped()) {
                rows += Math.max(0, printSection(senderId, section, counts, true));
            }
            for (WipeSection section : line.kept()) {
                printSection(senderId, section, counts, false);
            }
        }
        notify(senderId, "&7" + plan.wipedSectionCount() + " sections (" + String.format("%,d", rows) + " rows) would go, " + plan.keptSectionCount() + " sections stay.");
        if (online) {
            notify(senderId, "&7They are online: confirming kicks them first, so their session cannot save anything back over the wipe.");
        }
        notify(senderId, "&7Run &e" + confirmCommand + " &7within " + settings.confirmWindowMinutes() + " minutes to carry this out. A backup is written to "
                + store.folder().getPath() + " before anything is deleted.");
    }

    private long printSection(@Nullable UUID senderId, WipeSection section, Map<String, Long> counts, boolean wiped) {
        long count;
        String rows;
        if (!section.isTable()) {
            count = 0;
            rows = "emptied on their next join";
        } else {
            count = counts.getOrDefault(section.key(), 0L);
            if (count == -1L) {
                notify(senderId, "&8  - &7" + section.label() + " &8(" + section.category().id() + ") no such table on this database");
                return -1;
            }
            rows = String.format("%,d", count) + (count == 1L ? " row" : " rows") + (section.isReset() ? " (reset, not deleted)" : "");
        }
        notify(senderId, "&8  - &7" + section.label() + " &8(" + section.category().id() + ") &7" + rows + " &8- " + (wiped ? "&cgoes" : "&astays"));
        if (section.note() != null) {
            notify(senderId, "&8    &6! &7" + section.note());
        }
        return count;
    }

    private Map<String, Long> counts(Connection c, WipePlan plan, @Nullable String key) throws SQLException {
        Map<String, Long> out = new LinkedHashMap<>();
        for (WipePlan.Line line : plan.lines()) {
            for (List<WipeSection> list : List.of(line.wiped(), line.kept())) {
                for (WipeSection s : list) {
                    if (s.isTable()) {
                        out.put(s.key(), WipeStore.count(c, s, key));
                    }
                }
            }
        }
        return out;
    }

    // ================================================================== season (everyone)

    public void seasonPreview(CommandSender sender) {
        UUID senderId = senderId(sender);
        WipePlan plan = plan(WipeScope.SEASON);
        if (plan.refusedForEmptyKeepList()) {
            notify(senderId, "&cThe season keep-list (" + keepSource(plan) + ") keeps nothing. Fill wipe.season.keep in config.yml (a season wipe that keeps "
                    + "nothing is not allowed). Nothing was deleted.");
            return;
        }
        db.async(c -> {
            Map<String, Long> counts = counts(c, plan, null);
            long known;
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM pvp_players"); ResultSet rs = ps.executeQuery()) {
                known = rs.next() ? rs.getLong(1) : 0;
            }
            counts.put("*players", known);
            return counts;
        }).whenComplete((counts, error) -> {
            if (error != null) {
                notify(senderId, "&cThe rows could not be counted (" + reason(error) + "), so there is no preview and nothing was armed.");
                return;
            }
            notify(senderId, "&cSEASON WIPE &7of every player who ever joined (&e" + String.format("%,d", counts.get("*players")) + "&7).");
            printPreview(senderId, "everyone", "all players", plan, counts, "/gensadmin wipeseason confirm", !Bukkit.getOnlinePlayers().isEmpty());
            notify(senderId, "&7Everyone online is kicked and logins stay closed until it finishes. Confirm from the console.");
            arm(sender.getName(), ALL, WipeScope.SEASON);
        });
    }

    public void seasonConfirm(CommandSender sender) {
        if (sender instanceof Player player) {
            player.sendMessage(LEGACY.deserialize("&cThe season wipe kicks everyone, you included: run it from the console."));
            return;
        }
        String actor = sender.getName();
        String state = check(actor, ALL, WipeScope.SEASON);
        if (state != null) {
            notify(null, "&cRun &e/gensadmin wipeseason preview &cfirst" + (state.equals("expired") ? " (the last one is older than " + settings.confirmWindowMinutes() + " minutes)" : "") + ".");
            return;
        }
        WipePlan plan = plan(WipeScope.SEASON);
        if (plan.refusedForEmptyKeepList()) {
            notify(null, "&cThe season keep-list keeps nothing; nothing was deleted.");
            return;
        }
        if (!locks.beginEveryone()) {
            notify(null, "&cA wipe or restore is running right now. Wait for it to finish; nothing was done.");
            return;
        }
        consume(actor, ALL, WipeScope.SEASON);
        String wipeId = generateId();
        notify(null, "&cSeason wipe " + wipeId + " started: kicking everyone; logins are closed until it finishes.");
        AtomicReference<Outcome> outcome = new AtomicReference<>();
        evictEveryone("The season is being reset. Please reconnect in a minute.")
                .thenCompose(v -> beforeWipe(null))
                .thenCompose(v -> settle())
                .thenCompose(v -> db.async(c -> {
                    outcome.set(seasonRows(c, plan, actor, wipeId));
                    return null;
                }))
                .whenComplete((v, error) -> {
                    locks.endEveryone();
                    if (error != null) {
                        notify(null, "&cThe season wipe stopped before deleting anything (" + reason(error) + "). Logins are open again.");
                        audit.refused(actor, "everyone", "season", reason(error));
                        logger.log(Level.SEVERE, "[wipe] The season wipe stopped before deleting", unwrap(error));
                        return;
                    }
                    Outcome done = outcome.get();
                    for (String line : done.lines()) {
                        notify(null, "&8- " + line);
                    }
                    notify(null, "&aSeason wipe done: &e" + String.format("%,d", done.rows()) + " &arows changed. Backup &e" + done.backup().getName() + " &a("
                            + size(done.backup().length()) + "). Wipe id &b" + wipeId + "&a. Logins are open again.");
                    audit.wiped(actor, "everyone", "season", wipeId, done.backup(), done.receipt(), null);
                });
    }

    private Outcome seasonRows(Connection c, WipePlan plan, String actor, String wipeId) throws SQLException {
        long now = System.currentTimeMillis();
        List<String> values = valuesWiped(plan);
        insertRun(c, wipeId, "season", null, "everyone", "season", actor, null, now);
        Set<String> missing = new HashSet<>();
        WipeStore.Written written;
        try {
            written = store.export(c, new WipeStore.Header(1, "season", wipeId, "season", null, "everyone", actor, now, values), plan.wiped(), missing);
        } catch (IOException e) {
            throw new SQLException("the backup could not be written (" + e.getMessage() + "), so nothing was deleted", e);
        }
        updateRun(c, wipeId, "BACKED_UP", written.file().getName(), null);
        List<String> lines = new ArrayList<>();
        int[] total = {0};
        String receipt = Database.transaction(c, tx -> {
            List<String> parts = applyPlan(tx, plan, null, missing, lines, total);
            if (!values.isEmpty()) {
                armPending(tx, null, wipeId, actor, plan, true);
            }
            String r = String.join(" | ", parts) + " | wipe id " + wipeId;
            updateRun(tx, wipeId, "DONE", written.file().getName(), r);
            return r;
        });
        return new Outcome(written.file(), lines, receipt, total[0]);
    }

    // ================================================================== restore

    public void list(CommandSender sender, String targetName) {
        UUID senderId = senderId(sender);
        if (targetName.equalsIgnoreCase("season")) {
            Tasks.async.run(() -> describeFiles(senderId, "season", store.listSeason(), "/gensadmin wiperestore season <file>"));
            return;
        }
        resolve(senderId, targetName, record -> describeFiles(senderId, record.name(), store.list(record.uuid()),
                "/gensadmin wiperestore " + record.name() + " <file>"));
    }

    private void describeFiles(@Nullable UUID senderId, String name, List<File> files, String usage) {
        if (files.isEmpty()) {
            notify(senderId, "&7There is no backup of &e" + name + "&7.");
            return;
        }
        notify(senderId, "&e" + files.size() + " &7backups of &e" + name + "&7. Run &e" + usage + "&7:");
        for (File file : files) {
            try {
                int[] tables = {0};
                long[] rows = {0};
                WipeStore.Header h = store.read(file, t -> {
                    tables[0]++;
                    rows[0] += t.rows().size();
                });
                notify(senderId, "&8- &e" + file.getName() + " &7" + h.scope() + " " + (h.kind().equals("restore") ? "copy" : "wipe") + ", "
                        + WHEN.format(Instant.ofEpochMilli(h.createdAt())) + ", " + tables[0] + " tables, " + String.format("%,d", rows[0]) + " rows, id " + h.wipeId());
            } catch (IOException | SQLException | RuntimeException unreadable) {
                notify(senderId, "&8- &e" + file.getName() + " &7unreadable");
                logger.log(Level.WARNING, "Could not read the backup " + file, unreadable);
            }
        }
    }

    public void restore(CommandSender sender, String targetName, String fileName) {
        UUID senderId = senderId(sender);
        String actor = sender.getName();
        if (targetName.equalsIgnoreCase("season")) {
            restoreSeason(sender, fileName);
            return;
        }
        resolve(senderId, targetName, record -> {
            UUID playerId = record.uuid();
            String name = record.name();
            File file = store.resolve(playerId, fileName);
            if (file == null) {
                notify(senderId, "&c" + fileName + " is not a backup of " + name + ".");
                return;
            }
            if (Bukkit.getPlayer(playerId) != null) {
                notify(senderId, "&c" + name + " is online. They must be offline to be restored.");
                return;
            }
            List<String> refusals = new ArrayList<>();
            WipeStore.Header header;
            try {
                header = checkBackup(file, refusals);
            } catch (IOException | SQLException | RuntimeException unreadable) {
                notify(senderId, "&cThe restore of " + name + " failed and was rolled back, nothing changed: " + reason(unreadable));
                return;
            }
            if (header.player() == null || !header.player().equals(playerId)) {
                notify(senderId, "&c" + fileName + " is not a backup of " + name + ".");
                return;
            }
            if (!refusals.isEmpty()) {
                refusals.forEach(r -> notify(senderId, "&cThe backup of " + name + " was refused before anything ran: " + r));
                audit.refused(actor, name, "restore", "restore refused: " + String.join("; ", refusals));
                return;
            }
            if (!locks.begin(playerId)) {
                notify(senderId, "&c" + name + " is being wiped or restored right now. Wait for that to finish; nothing was done.");
                return;
            }
            notify(senderId, "&7Restoring &e" + name + " &7from &e" + file.getName() + "&7...");
            String restoreId = generateId();
            AtomicReference<Outcome> outcome = new AtomicReference<>();
            evict(senderId, playerId, name)
                    .thenCompose(v -> beforeWipe(playerId))
                    .thenCompose(v -> settle())
                    .thenCompose(v -> players.runSerialized(playerId, c -> outcome.set(restoreRows(c, file, header, playerId, name, actor, restoreId))))
                    .whenComplete((v, error) -> {
                        locks.end(playerId);
                        if (error != null) {
                            notify(senderId, "&cThe restore of " + name + " failed and was rolled back, nothing changed: " + reason(error));
                            audit.refused(actor, name, "restore", "restore rolled back: " + reason(error));
                            logger.log(Level.SEVERE, "[wipe] The restore of " + name + " failed and was rolled back", unwrap(error));
                            return;
                        }
                        Outcome done = outcome.get();
                        notify(senderId, "&7What " + name + " held right now was written to &e" + done.backup().getName() + " &7first, so this restore can itself be undone.");
                        done.lines().forEach(line -> notify(senderId, "&8- " + line));
                        notify(senderId, "&aRestored &e" + name + " &afrom &e" + file.getName() + "&a: " + String.format("%,d", done.rows())
                                + " rows. Their next join loads exactly that.");
                        audit.restored(actor, name + " (" + playerId + ")", file, done.receipt());
                    });
        });
    }

    /** Season restore: console only, and asked twice (the first call explains and arms, the second runs). */
    private void restoreSeason(CommandSender sender, String fileName) {
        if (sender instanceof Player player) {
            player.sendMessage(LEGACY.deserialize("&cA season restore kicks everyone: run it from the console."));
            return;
        }
        String actor = sender.getName();
        File file = store.resolve(null, fileName);
        if (file == null) {
            notify(null, "&c" + fileName + " is not a season backup.");
            return;
        }
        List<String> refusals = new ArrayList<>();
        WipeStore.Header header;
        try {
            header = checkBackup(file, refusals);
        } catch (IOException | SQLException | RuntimeException unreadable) {
            notify(null, "&cThat backup could not be read: " + reason(unreadable));
            return;
        }
        if (!refusals.isEmpty()) {
            refusals.forEach(r -> notify(null, "&cThe season backup was refused before anything ran: " + r));
            return;
        }
        String gateKey = ALL + "|" + file.getName();
        if (check(actor, gateKey, WipeScope.RESTORE) != null) {
            arm(actor, gateKey, WipeScope.RESTORE);
            notify(null, "&eThis puts back, for every player in &f" + file.getName() + "&e (wipe " + header.wipeId() + "), the rows it holds, replacing what they have "
                    + "now in those tables; players not in it are not touched. Their emptied inventories are given back on their next join. Everyone is kicked "
                    + "while it runs. Run the same command again within " + settings.confirmWindowMinutes() + " minutes to do it.");
            return;
        }
        if (!locks.beginEveryone()) {
            notify(null, "&cA wipe or restore is running right now. Wait for it to finish; nothing was done.");
            return;
        }
        consume(actor, gateKey, WipeScope.RESTORE);
        String restoreId = generateId();
        AtomicReference<Outcome> outcome = new AtomicReference<>();
        notify(null, "&7Restoring the season from &e" + file.getName() + "&7: kicking everyone; logins are closed until it finishes.");
        evictEveryone("The server is restoring data. Please reconnect in a minute.")
                .thenCompose(v -> beforeWipe(null))
                .thenCompose(v -> settle())
                .thenCompose(v -> db.async(c -> {
                    outcome.set(restoreRows(c, file, header, null, "everyone", actor, restoreId));
                    return null;
                }))
                .whenComplete((v, error) -> {
                    locks.endEveryone();
                    if (error != null) {
                        notify(null, "&cThe season restore failed and was rolled back, nothing changed: " + reason(error) + ". Logins are open again.");
                        logger.log(Level.SEVERE, "[wipe] The season restore failed and was rolled back", unwrap(error));
                        return;
                    }
                    Outcome done = outcome.get();
                    notify(null, "&7What everyone held was written to &e" + done.backup().getName() + " &7first.");
                    done.lines().forEach(line -> notify(null, "&8- " + line));
                    notify(null, "&aSeason restored from &e" + file.getName() + "&a: " + String.format("%,d", done.rows()) + " rows. Logins are open again.");
                    audit.restored(actor, "everyone", file, done.receipt());
                });
    }

    /** Reads the backup's tables (no database) and checks each is declared here (BlockvilleCore's WipeRestoreKeys). */
    private WipeStore.Header checkBackup(File file, List<String> refusals) throws IOException, SQLException {
        Set<String> declared = new HashSet<>();
        for (WipeSection s : registry.sections()) {
            if (s.isTable() && s.safe()) {
                declared.add(s.table() + "|" + s.keyColumn());
            }
        }
        return store.read(file, t -> {
            if (!declared.contains(t.table() + "|" + t.keyColumn())) {
                refusals.add(t.label() + ": no module on this server declares the table " + t.table() + " by " + t.keyColumn());
            }
        });
    }

    /**
     * Database thread. Takes a copy of the current rows of every table in the backup, then in one transaction puts the
     * backup's rows back, cancels the armed inventory wipe and arms the give-back of what that wipe emptied.
     */
    private Outcome restoreRows(Connection c, File file, WipeStore.Header header, @Nullable UUID playerId, String name, String actor, String restoreId) throws SQLException {
        long now = System.currentTimeMillis();
        Set<String> tables = new LinkedHashSet<>();
        try {
            store.read(file, t -> tables.add(t.table() + "|" + t.keyColumn()));
        } catch (IOException e) {
            throw new SQLException("the backup could not be read: " + e.getMessage(), e);
        }
        List<WipeSection> current = new ArrayList<>();
        for (WipeSection s : registry.sections()) {
            if (s.isTable() && tables.contains(s.table() + "|" + s.keyColumn())) {
                current.add(s);
            }
        }
        String kind = playerId == null ? "season" : "player";
        insertRun(c, restoreId, "restore", playerId == null ? null : playerId.toString(), name, "restore", actor, "restore of " + file.getName(), now);
        WipeStore.Written pre;
        try {
            pre = store.export(c, new WipeStore.Header(1, kind, restoreId, "restore", playerId, name, actor, now, List.of()), current, new HashSet<>());
        } catch (IOException e) {
            throw new SQLException("the pre-restore copy could not be written (" + e.getMessage() + "), so nothing was changed", e);
        }
        updateRun(c, restoreId, "BACKED_UP", pre.file().getName(), null);
        List<String> lines = new ArrayList<>();
        int[] rows = {0};
        String receipt = Database.transaction(c, tx -> {
            WipeStore.statementTimeout(tx, db.isPostgres(), settings.runTimeoutSeconds());
            List<String> parts = new ArrayList<>();
            try {
                store.read(file, t -> {
                    int inserted = WipeStore.restore(tx, t);
                    rows[0] += inserted;
                    String line = WipeReceipts.line("core.restore-section", "module", t.sections().isEmpty() ? "?" : t.sections().get(0).split(":")[0],
                            "label", t.label(), "count", inserted);
                    lines.add("&7" + line + " put back");
                    parts.add(line);
                });
            } catch (IOException e) {
                throw new SQLException("the backup could not be read: " + e.getMessage(), e);
            }
            int cancelled;
            String pendingSql = playerId == null ? "DELETE FROM pvp_wipe_pending WHERE wipe_id = ?" : "DELETE FROM pvp_wipe_pending WHERE uuid = ?";
            try (PreparedStatement ps = tx.prepareStatement(pendingSql)) {
                ps.setString(1, playerId == null ? header.wipeId() : playerId.toString());
                cancelled = ps.executeUpdate();
            }
            if (cancelled > 0) {
                parts.add(WipeReceipts.line("core.restore-pending-cancelled"));
            }
            String givebackSql = "INSERT INTO pvp_wipe_giveback (uuid, wipe_id, restore_id) SELECT uuid, wipe_id, ? FROM pvp_wipe_cleared WHERE wipe_id = ?"
                    + (playerId == null ? "" : " AND uuid = ?")
                    + " ON CONFLICT (uuid) DO UPDATE SET wipe_id = excluded.wipe_id, restore_id = excluded.restore_id";
            int armed;
            try (PreparedStatement ps = tx.prepareStatement(givebackSql)) {
                ps.setString(1, restoreId);
                ps.setString(2, header.wipeId());
                if (playerId != null) {
                    ps.setString(3, playerId.toString());
                }
                armed = ps.executeUpdate();
            }
            if (armed > 0) {
                String line = WipeReceipts.line("core.restore-inventory");
                lines.add("&7" + line + (playerId == null ? " (" + armed + " players)" : ""));
                parts.add(line);
            }
            parts.add(WipeReceipts.line("core.restore-pre-copy", "file", pre.file().getName()));
            String r = String.join(" | ", parts);
            updateRun(tx, restoreId, "DONE", pre.file().getName(), r);
            return r;
        });
        return new Outcome(pre.file(), lines, receipt, rows[0]);
    }

    // ================================================================== wipe reasons

    public void reason(CommandSender sender, String wipeId) {
        String id = wipeId.trim().toUpperCase(Locale.ROOT);
        db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT target_name, actor, created_at, reason, scope, state FROM pvp_wipe_runs WHERE wipe_id = ?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new String[]{rs.getString(1), rs.getString(2), String.valueOf(rs.getLong(3)), rs.getString(4), rs.getString(5), rs.getString(6)} : null;
                }
            }
        }).whenComplete((row, error) -> Tasks.forSender(sender, () -> {
            if (error != null || row == null) {
                sender.sendMessage(Msg.bad("No wipe found with id " + Msg.white(id) + "."));
                return;
            }
            DateTimeFormatter date = DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm").withZone(java.time.ZoneOffset.UTC);
            sender.sendMessage(LEGACY.deserialize("&8&m--------&r &7Wipe &e" + id + " &8&m--------"));
            sender.sendMessage(Msg.neutral("Player: " + Msg.white(row[0])));
            sender.sendMessage(Msg.neutral("Wiped by: " + Msg.white(row[1])));
            sender.sendMessage(Msg.neutral("When: " + Msg.white(date.format(Instant.ofEpochMilli(Long.parseLong(row[2]))) + " UTC")));
            sender.sendMessage(Msg.neutral("Scope: " + Msg.white(row[4]) + ", state: " + Msg.white(row[5])));
            sender.sendMessage(Msg.neutral("Reason: " + Msg.white(row[3] == null || row[3].isBlank() ? "-" : row[3])));
        }));
    }

    // ================================================================== journal

    private static void insertRun(Connection c, String wipeId, String kind, @Nullable String target, String targetName, String scope, String actor,
                                  @Nullable String reason, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO pvp_wipe_runs (wipe_id, kind, target, target_name, scope, actor, reason, state, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, 'STARTED', ?)")) {
            ps.setString(1, wipeId);
            ps.setString(2, kind);
            ps.setString(3, target);
            ps.setString(4, targetName);
            ps.setString(5, scope);
            ps.setString(6, actor);
            ps.setString(7, reason);
            ps.setLong(8, now);
            ps.executeUpdate();
        }
    }

    private static void updateRun(Connection c, String wipeId, String state, @Nullable String backup, @Nullable String receipt) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE pvp_wipe_runs SET state = ?, backup_file = COALESCE(?, backup_file), receipt = COALESCE(?, receipt), finished_at = ? WHERE wipe_id = ?")) {
            ps.setString(1, state);
            ps.setString(2, backup);
            ps.setString(3, receipt);
            ps.setLong(4, System.currentTimeMillis());
            ps.setString(5, wipeId);
            ps.executeUpdate();
        }
    }

    // ================================================================== evicting and waiting

    /** Kicks the player if online and waits until PlayerDataService has let go of their data. */
    private CompletableFuture<Void> evict(@Nullable UUID senderId, UUID playerId, String name) {
        boolean[] told = {false};
        return waitUntil(() -> {
            Player online = Bukkit.getPlayer(playerId);
            if (online != null) {
                if (!told[0]) {
                    told[0] = true;
                    notify(senderId, "&7" + name + " was online here and has been kicked, so their session cannot save anything back over the wipe.");
                }
                Tasks.onEntity(online, () -> online.kick(Msg.bad("Your data has been wiped by an administrator.")));
                return false;
            }
            return players.get(playerId) == null;
        }, name + " is still online after " + settings.maxWaitTicks() / 20 + " seconds, so nothing was deleted. Try again in a minute.");
    }

    /** Kicks everyone and waits until nobody is online and every kicked player's quit save has landed. */
    private CompletableFuture<Void> evictEveryone(String message) {
        Set<UUID> kicked = ConcurrentHashMap.newKeySet();
        return waitUntil(() -> {
            boolean anyone = false;
            for (Player online : Bukkit.getOnlinePlayers()) {
                anyone = true;
                kicked.add(online.getUniqueId());
                Tasks.onEntity(online, () -> online.kick(Msg.bad(message)));
            }
            if (anyone) {
                return false;
            }
            for (UUID id : kicked) {
                if (players.get(id) != null) {
                    return false;
                }
            }
            // Players who were mid-login (data loaded, not joined) would otherwise join after the wipe and save their
            // old data back over it. Drop that data; their join is then refused for lack of data.
            players.dropUnjoined();
            return players.loadedCount() == 0;
        }, "players were still online after " + settings.maxWaitTicks() / 20 + " seconds, so nothing was changed. Try again.")
                .thenCompose(v -> {
                    // Barrier: a no-op in each kicked player's save queue completes after their quit save.
                    List<CompletableFuture<Void>> barriers = new ArrayList<>();
                    for (UUID id : kicked) {
                        barriers.add(players.runSerialized(id, c -> {
                        }));
                    }
                    return CompletableFuture.allOf(barriers.toArray(new CompletableFuture[0]));
                });
    }

    private CompletableFuture<Void> waitUntil(BooleanSupplier done, String failure) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        long deadline = System.currentTimeMillis() + Math.max(1, settings.maxWaitTicks()) * 50L;
        Tasks.async.timer(task -> {
            if (future.isDone()) {
                task.cancel();
                return;
            }
            try {
                if (done.getAsBoolean()) {
                    task.cancel();
                    future.complete(null);
                } else if (System.currentTimeMillis() > deadline) {
                    task.cancel();
                    future.completeExceptionally(new IllegalStateException(failure));
                }
            } catch (RuntimeException e) {
                task.cancel();
                future.completeExceptionally(e);
            }
        }, 50, 250, TimeUnit.MILLISECONDS);
        return future;
    }

    /**
     * Lets modules write live state down, and closes every open player-vault view so its save lands before the export
     * (the vault module saves a vault when its view closes). The vault module has no public "is this vault open"
     * check yet, so every vault view is closed; wipes are rare.
     */
    private CompletableFuture<Void> beforeWipe(@Nullable UUID player) {
        List<CompletableFuture<Void>> waits = new ArrayList<>();
        for (WipeParticipant participant : participants) {
            try {
                waits.add(participant.beforeWipe(player).exceptionally(e -> {
                    logger.log(Level.WARNING, "[wipe] A module could not write its live state down before the wipe; the backup holds its last save", e);
                    return null;
                }));
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "[wipe] A module failed before the wipe", e);
            }
        }
        Set<Player> online = new LinkedHashSet<>(Bukkit.getOnlinePlayers());
        AtomicInteger left = new AtomicInteger(online.size());
        CompletableFuture<Void> closed = new CompletableFuture<>();
        if (online.isEmpty()) {
            closed.complete(null);
        }
        for (Player viewer : online) {
            Runnable countDown = () -> {
                if (left.decrementAndGet() == 0) {
                    closed.complete(null);
                }
            };
            Tasks.entity.later(viewer, () -> {
                try {
                    InventoryHolder holder = viewer.getOpenInventory().getTopInventory().getHolder();
                    if (holder != null && holder.getClass().getName().endsWith("VaultsModule$VaultHolder")) {
                        viewer.closeInventory();
                    }
                } finally {
                    countDown.run();
                }
            }, countDown, 1);
        }
        waits.add(closed);
        return CompletableFuture.allOf(waits.toArray(new CompletableFuture[0]));
    }

    private CompletableFuture<Void> settle() {
        CompletableFuture<Void> future = new CompletableFuture<>();
        Tasks.async.later(() -> future.complete(null), Math.max(1, settings.settleTicks()) * 50L, TimeUnit.MILLISECONDS);
        return future;
    }

    // ================================================================== confirmation gate (BlockvilleCore's WipeConfirmationGate)

    private static String gateKey(String actor, String target, WipeScope scope) {
        return actor.trim().toLowerCase(Locale.ROOT) + "|" + target + "|" + scope.id();
    }

    private void arm(String actor, String target, WipeScope scope) {
        long now = System.currentTimeMillis();
        armedAt.put(gateKey(actor, target, scope), now);
        long window = settings.confirmWindowMinutes() * 60_000L;
        armedAt.values().removeIf(at -> now - at > window);
    }

    /** null when armed and fresh; "not-armed" or "expired" otherwise. */
    private @Nullable String check(String actor, String target, WipeScope scope) {
        Long at = armedAt.get(gateKey(actor, target, scope));
        if (at == null) {
            return "not-armed";
        }
        if (System.currentTimeMillis() - at > settings.confirmWindowMinutes() * 60_000L) {
            armedAt.remove(gateKey(actor, target, scope));
            return "expired";
        }
        return null;
    }

    /** Single winner: two confirms at once cannot both run (checklist 2, 3). */
    private boolean consume(String actor, String target, WipeScope scope) {
        return armedAt.remove(gateKey(actor, target, scope)) != null;
    }

    // ================================================================== helpers

    private void resolve(@Nullable UUID senderId, String targetName, Consumer<PlayerDataService.PlayerRecord> action) {
        players.lookup(targetName).whenComplete((found, error) -> {
            if (error != null) {
                logger.log(Level.WARNING, "Player lookup failed for " + targetName, error);
            }
            if (error != null || found == null || found.isEmpty()) {
                notify(senderId, "&cPlayer not found.");
                return;
            }
            action.accept(found.get());
        });
    }

    static String generateId() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private static @Nullable UUID senderId(CommandSender sender) {
        return sender instanceof Player p ? p.getUniqueId() : null;
    }

    /** Messages in BlockvilleCore's &-colour style, on the receiver's thread. */
    void notify(@Nullable UUID senderId, String message) {
        if (senderId == null) {
            Tasks.global.run(() -> Bukkit.getConsoleSender().sendMessage(LEGACY.deserialize(message)));
            return;
        }
        Player staff = Bukkit.getPlayer(senderId);
        if (staff != null) {
            Tasks.onEntity(staff, () -> staff.sendMessage(LEGACY.deserialize(message)));
        }
    }

    private static Throwable unwrap(Throwable error) {
        return error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
    }

    static String reason(Throwable error) {
        Throwable cause = unwrap(error);
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    static String size(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024L) {
            return String.format("%,d KB", bytes / 1024L);
        }
        return String.format("%,.1f MB", bytes / 1048576.0);
    }
}
