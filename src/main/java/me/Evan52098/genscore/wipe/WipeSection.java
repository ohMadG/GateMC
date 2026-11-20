package me.Evan52098.genscore.wipe;

import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * One piece of a player's data a wipe can take (blockville-common's WipeSection), keyed by the player's UUID stored as
 * TEXT in {@code keyColumn}.
 *
 * <ul>
 *   <li>A <b>delete</b> section removes the player's rows from {@code table}.</li>
 *   <li>A <b>reset</b> section (non-empty {@code reset}) sets those columns back to the given values instead, for data
 *       that shares a row with something else (money and the pay settings share {@code pvp_balances}).</li>
 *   <li>A <b>value</b> section has no table: inventory, ender chest and experience live in the server's own player
 *       files, so they are emptied on the player's next join.</li>
 * </ul>
 * Table and column names are put into SQL, so only plain lowercase identifiers are accepted ({@link #isSafe}).
 */
public record WipeSection(String module, String id, String label, @Nullable String table, @Nullable String keyColumn,
                          WipeCategory category, @Nullable String note, Map<String, Object> reset) {

    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[a-z][a-z0-9_]{0,62}$");

    public WipeSection {
        reset = reset == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(reset));
    }

    public static WipeSection delete(String module, String id, String label, String table, String keyColumn, WipeCategory category) {
        return new WipeSection(module, id, label, table, keyColumn, category, null, Map.of());
    }

    public static WipeSection reset(String module, String id, String label, String table, String keyColumn, WipeCategory category, Map<String, Object> columns) {
        return new WipeSection(module, id, label, table, keyColumn, category, null, columns);
    }

    public static WipeSection value(String module, String id, String label, WipeCategory category) {
        return new WipeSection(module, id, label, null, null, category, null, Map.of());
    }

    public WipeSection withNote(String newNote) {
        return new WipeSection(module, id, label, table, keyColumn, category, newNote, reset);
    }

    public boolean isTable() {
        return table != null && keyColumn != null;
    }

    public boolean isReset() {
        return isTable() && !reset.isEmpty();
    }

    /** "module:id", how the keep-list, the custom wipe command and the preview name a section. */
    public String key() {
        return module + ":" + id;
    }

    public static boolean isSafe(@Nullable String identifier) {
        return identifier != null && SAFE_IDENTIFIER.matcher(identifier).matches();
    }

    /** True when every identifier this section puts into SQL is safe. */
    public boolean safe() {
        if (!isTable()) {
            return true;
        }
        if (!isSafe(table) || !isSafe(keyColumn)) {
            return false;
        }
        for (String column : reset.keySet()) {
            if (!isSafe(column)) {
                return false;
            }
        }
        return true;
    }
}
