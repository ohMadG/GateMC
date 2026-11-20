package me.Evan52098.genscore.wipe;

import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

/**
 * Which tables a wipe touches, in one data-driven list. BlockvilleCore had one wiper class per module; here every
 * module's player data is a row in this registry:
 * <ol>
 *   <li>the built-in sections below, for the tables GensCore has today;</li>
 *   <li>sections listed under {@code wipe.sections} in config.yml (same fields), for tables added later or by hand;</li>
 *   <li>sections registered in code by a module with {@link #register} (e.g. factions, deathban).</li>
 * </ol>
 * Each section names a wipe category, so a keep-list entry like {@code categories: [identity]} covers future modules.
 *
 * <p>Never wiped, on purpose: the economy ledger, inventory snapshots, reports and anti-cheat records (staff audit
 * trails), and kit definitions (server data, not player data).
 */
public final class WipeRegistry {

    public static final String CORE = "core";
    public static final String STAFF = "staff";

    private final List<WipeSection> builtIn = new ArrayList<>();
    private final List<WipeSection> registered = new CopyOnWriteArrayList<>();
    private volatile List<WipeSection> configured = List.of();

    WipeRegistry() {
        // Items and the places they are kept
        builtIn.add(WipeSection.value(CORE, "inventory", "inventory", WipeCategory.ITEMS));
        builtIn.add(WipeSection.value(CORE, "enderchest", "ender chest", WipeCategory.ITEMS));
        builtIn.add(WipeSection.value(CORE, "experience", "experience", WipeCategory.ITEMS));
        builtIn.add(WipeSection.delete(CORE, "playervaults", "player vaults", "pvp_vaults", "uuid", WipeCategory.ITEMS));
        builtIn.add(WipeSection.delete(STAFF, "inv_pending", "queued inventory-history restore", "pvp_inv_pending", "uuid", WipeCategory.ITEMS)
                .withNote("A restore staff queued while they were offline; it would hand items back after the wipe."));
        // Money
        builtIn.add(WipeSection.reset(CORE, "balance", "money", "pvp_balances", "uuid", WipeCategory.ECONOMY, Map.of("balance_cents", 0L)));
        builtIn.add(WipeSection.delete(CORE, "balance_pending", "money waiting for them while offline", "pvp_balance_pending", "uuid", WipeCategory.ECONOMY));
        // Places and progress
        builtIn.add(WipeSection.delete(CORE, "homes", "homes", "pvp_homes", "uuid", WipeCategory.PLACES));
        builtIn.add(WipeSection.delete(CORE, "kits", "kit cooldowns", "pvp_kit_uses", "uuid", WipeCategory.PROGRESS));
        builtIn.add(WipeSection.delete(CORE, "vault_slots", "bought vault slots", "pvp_vault_slots", "uuid", WipeCategory.PURCHASES)
                .withNote("Bought with in-game money (/buyvault), so a season wipe takes them unless the keep-list names them."));
        // Identity and settings
        builtIn.add(WipeSection.reset(CORE, "economy_prefs", "payment settings", "pvp_balances", "uuid", WipeCategory.IDENTITY,
                Map.of("pay_enabled", 1L, "pay_confirm", 1L)));
        Map<String, Object> nick = new LinkedHashMap<>();
        nick.put("nickname", null);
        nick.put("nick_changed_at", 0L);
        builtIn.add(WipeSection.reset(CORE, "nickname", "nickname", "pvp_social", "uuid", WipeCategory.IDENTITY, nick));
        builtIn.add(WipeSection.reset(CORE, "msg_prefs", "message settings", "pvp_social", "uuid", WipeCategory.IDENTITY,
                Map.of("msg_enabled", 1L, "social_spy", 0L)));
        builtIn.add(WipeSection.delete(CORE, "ignores", "ignore list", "pvp_ignores", "uuid", WipeCategory.IDENTITY));
        builtIn.add(WipeSection.reset(CORE, "playtime", "playtime", "pvp_players", "uuid", WipeCategory.IDENTITY, Map.of("playtime_ms", 0L)));
    }

    /** The season keep-list used when config.yml has no wipe.season.keep block (BlockvilleCore's WipeKeepDefaults). */
    static Map<String, List<String>> shippedKeep() {
        Map<String, List<String>> keep = new LinkedHashMap<>();
        keep.put(CORE, List.of("playtime", "nickname", "msg_prefs", "economy_prefs", "ignores"));
        return keep;
    }

    /** For other modules: add their player tables to wipes. Safe to call while enabling. */
    public void register(WipeSection section) {
        if (!section.safe()) {
            throw new IllegalArgumentException("Wipe section " + section.key() + " names a table or column that is not a plain lowercase identifier");
        }
        registered.add(section);
    }

    /** Re-reads {@code wipe.sections}. Bad entries are logged and skipped. */
    void loadConfig(@Nullable List<Map<?, ?>> entries, Logger logger) {
        List<WipeSection> out = new ArrayList<>();
        if (entries != null) {
            for (Map<?, ?> raw : entries) {
                WipeSection section = parse(raw);
                if (section == null || !section.safe()) {
                    logger.warning("[wipe] Skipped a wipe.sections entry (needs module, id, label, table, key-column, category; plain lowercase names): " + raw);
                    continue;
                }
                out.add(section);
            }
        }
        configured = List.copyOf(out);
    }

    private static @Nullable WipeSection parse(Map<?, ?> raw) {
        String module = str(raw.get("module"));
        String id = str(raw.get("id"));
        String table = str(raw.get("table"));
        String key = raw.containsKey("key-column") ? str(raw.get("key-column")) : "uuid";
        WipeCategory category = WipeCategory.parse(str(raw.get("category")));
        if (module == null || id == null || table == null || key == null || category == null) {
            return null;
        }
        String label = str(raw.get("label"));
        Map<String, Object> reset = new LinkedHashMap<>();
        if (raw.get("reset") instanceof Map<?, ?> columns) {
            for (Map.Entry<?, ?> e : columns.entrySet()) {
                Object value = e.getValue();
                if (value instanceof Integer i) {
                    value = i.longValue();
                }
                reset.put(String.valueOf(e.getKey()), value);
            }
        }
        WipeSection section = new WipeSection(module.toLowerCase(), id.toLowerCase(), label == null ? id : label, table, key, category, null, reset);
        String note = str(raw.get("note"));
        return note == null ? section : section.withNote(note);
    }

    private static @Nullable String str(@Nullable Object o) {
        return o == null || String.valueOf(o).isBlank() ? null : String.valueOf(o).trim();
    }

    /** Every section, built-in first; a later entry with the same module:id replaces an earlier one. */
    public List<WipeSection> sections() {
        Map<String, WipeSection> byKey = new LinkedHashMap<>();
        for (Collection<WipeSection> source : List.of(builtIn, configured, registered)) {
            for (WipeSection section : source) {
                byKey.put(section.key(), section);
            }
        }
        return List.copyOf(byKey.values());
    }

    public @Nullable WipeSection find(String module, String id) {
        for (WipeSection section : sections()) {
            if (section.module().equalsIgnoreCase(module) && section.id().equalsIgnoreCase(id)) {
                return section;
            }
        }
        return null;
    }
}
