package me.Evan52098.genscore.wipe;

import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The wording of wipe receipts (blockville-common's WipeReceipts): templates with {placeholders}, defaults here,
 * overridable under {@code wipe.receipts} in config.yml (e.g. {@code wipe.receipts.core.describe}). A key with no
 * template prints as {@code [wipe.receipts.<key>]}, so nothing is ever hidden.
 */
public final class WipeReceipts {

    private static final Map<String, String> DEFAULTS = new LinkedHashMap<>();

    static {
        DEFAULTS.put("core.describe", "money, homes, player vaults, kit cooldowns, social settings and playtime, plus an inventory, ender chest and experience wipe armed for their next join");
        DEFAULTS.put("staff.describe", "a queued inventory-history restore that would otherwise hand items back after the wipe");
        DEFAULTS.put("core.rows", "{count} rows cleared");
        DEFAULTS.put("core.columns-reset", "{count} rows reset");
        DEFAULTS.put("core.inventory-armed", "inventory wipe armed");
        DEFAULTS.put("core.sections-kept", "{count} sections kept");
        DEFAULTS.put("core.table-missing", "{section}: no table {table} on this database, skipped");
        DEFAULTS.put("core.restore-section", "{module}/{label}: {count} rows");
        DEFAULTS.put("core.restore-pre-copy", "pre-restore copy {file}");
        DEFAULTS.put("core.restore-inventory", "inventory, ender chest and experience given back on their next join");
        DEFAULTS.put("core.restore-pending-cancelled", "pending inventory wipe cancelled");
    }

    private static volatile Map<String, String> templates = Map.copyOf(DEFAULTS);

    private WipeReceipts() {
    }

    static void bind(@Nullable ConfigurationSection overrides) {
        Map<String, String> merged = new LinkedHashMap<>(DEFAULTS);
        if (overrides != null) {
            for (String key : overrides.getKeys(true)) {
                if (!overrides.isConfigurationSection(key)) {
                    merged.put(key, overrides.getString(key, ""));
                }
            }
        }
        templates = Map.copyOf(merged);
    }

    public static String describe(String moduleId) {
        String template = templates.get(moduleId + ".describe");
        return template == null ? "its registered sections" : template;
    }

    public static String line(String key, Object... placeholders) {
        String template = templates.get(key);
        if (template == null) {
            return "[wipe.receipts." + key + "]";
        }
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            Object value = placeholders[i + 1];
            String text = value instanceof Number n && !(value instanceof Double) && !(value instanceof Float)
                    ? String.format("%,d", n.longValue()) : String.valueOf(value);
            template = template.replace("{" + placeholders[i] + "}", text);
        }
        return template;
    }
}
