package me.Evan52098.genscore.rewardbox;

import me.Evan52098.genscore.util.Items;
import me.Evan52098.genscore.util.Money;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * One configured reward: money, shards, crate keys, an item or a console command. Shared by the season pass, the
 * advent calendar and the elf shop (group H).
 *
 * <p>Config shape (a list of maps, one key per entry plus an optional {@code display}):
 * <pre>
 * - money: 500                       # dollars, up to 2 decimals
 * - shards: 25
 * - keys: {crate: vote, amount: 1}   # group F key crates
 * - item: {material: DIAMOND, amount: 3, name: '&amp;bShiny', lore: ['&amp;7line'], enchants: {SHARPNESS: 5}, glow: true}
 * - command: 'lp user {player} permission set some.perm'
 *   display: '&amp;dA special perk'
 * </pre>
 */
public record Reward(Kind kind, long amount, String payload, String display) {

    public enum Kind { MONEY, SHARDS, KEYS, ITEM, COMMAND }

    /** Checklist 8: sane caps so a config typo cannot mint absurd amounts. */
    static final long MAX_MONEY_CENTS = 1_000_000_000_00L; // $1,000,000,000
    static final long MAX_SHARDS = 10_000_000L;
    static final int MAX_KEYS = 10_000;

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    /** Parses a reward list. Bad entries are logged and skipped. */
    public static List<Reward> parseList(@Nullable List<?> raw, Logger logger, String where) {
        List<Reward> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        int index = 0;
        for (Object entry : raw) {
            index++;
            if (!(entry instanceof Map<?, ?> map)) {
                logger.warning("[rewards] " + where + " #" + index + " is not a map; skipped.");
                continue;
            }
            try {
                Reward r = parse(map);
                if (r == null) {
                    logger.warning("[rewards] " + where + " #" + index + " has no money/shards/keys/item/command; skipped.");
                } else {
                    out.add(r);
                }
            } catch (RuntimeException e) {
                logger.warning("[rewards] " + where + " #" + index + " is invalid (" + e.getMessage() + "); skipped.");
            }
        }
        return List.copyOf(out);
    }

    private static @Nullable Reward parse(Map<?, ?> map) {
        String display = map.get("display") == null ? null : String.valueOf(map.get("display"));
        if (map.containsKey("money")) {
            long cents = Money.toCents(Double.parseDouble(String.valueOf(map.get("money"))));
            if (cents <= 0 || cents > MAX_MONEY_CENTS) {
                throw new IllegalArgumentException("money must be above 0 and at most 1,000,000,000");
            }
            return new Reward(Kind.MONEY, cents, "", display != null ? display : "&a" + Money.format(cents));
        }
        if (map.containsKey("shards")) {
            long shards = Long.parseLong(String.valueOf(map.get("shards")));
            if (shards <= 0 || shards > MAX_SHARDS) {
                throw new IllegalArgumentException("shards must be 1 to " + MAX_SHARDS);
            }
            return new Reward(Kind.SHARDS, shards, "", display != null ? display : "&b" + shards + " shards");
        }
        if (map.containsKey("keys")) {
            if (!(map.get("keys") instanceof Map<?, ?> keys) || keys.get("crate") == null) {
                throw new IllegalArgumentException("keys needs {crate: <id>, amount: <n>}");
            }
            String crate = String.valueOf(keys.get("crate")).toLowerCase(Locale.ROOT);
            int amount = keys.get("amount") == null ? 1 : Integer.parseInt(String.valueOf(keys.get("amount")));
            if (amount <= 0 || amount > MAX_KEYS || !crate.matches("[a-z0-9_-]{1,32}")) {
                throw new IllegalArgumentException("keys amount must be 1 to " + MAX_KEYS + " and the crate id simple");
            }
            return new Reward(Kind.KEYS, amount, crate, display != null ? display : "&e" + amount + "x " + crate + " key");
        }
        if (map.containsKey("item")) {
            if (!(map.get("item") instanceof Map<?, ?> spec)) {
                throw new IllegalArgumentException("item needs a map");
            }
            ItemStack item = buildItem(spec);
            String name = display != null ? display : "&f" + item.getAmount() + "x " + pretty(item.getType());
            return new Reward(Kind.ITEM, item.getAmount(), Items.encode(new ItemStack[]{item}), name);
        }
        if (map.containsKey("command")) {
            String command = String.valueOf(map.get("command")).trim();
            if (command.startsWith("/")) {
                command = command.substring(1);
            }
            if (command.isEmpty() || command.contains("\n")) {
                throw new IllegalArgumentException("command is empty or has a line break");
            }
            return new Reward(Kind.COMMAND, 1, command, display != null ? display : "&7a bonus");
        }
        return null;
    }

    /** Builds a plain item from {material, amount, name, lore, enchants, glow}. Checklist 9: real material, amount within one stack. */
    public static ItemStack buildItem(Map<?, ?> spec) {
        Material material = Material.matchMaterial(String.valueOf(spec.get("material")));
        if (material == null || material.isAir() || !material.isItem()) {
            throw new IllegalArgumentException("unknown item material " + spec.get("material"));
        }
        int amount = spec.get("amount") == null ? 1 : Integer.parseInt(String.valueOf(spec.get("amount")));
        if (amount <= 0 || amount > material.getMaxStackSize()) {
            throw new IllegalArgumentException("amount must be 1 to " + material.getMaxStackSize() + " for " + material);
        }
        ItemStack item = new ItemStack(material, amount);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (spec.get("name") != null) {
                meta.displayName(text(String.valueOf(spec.get("name"))));
            }
            if (spec.get("lore") instanceof List<?> lore) {
                List<Component> lines = new ArrayList<>();
                for (Object line : lore) {
                    lines.add(text(String.valueOf(line)));
                }
                meta.lore(lines);
            }
            if (spec.get("enchants") instanceof Map<?, ?> enchants) {
                for (Map.Entry<?, ?> e : enchants.entrySet()) {
                    Enchantment ench = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(String.valueOf(e.getKey()).toLowerCase(Locale.ROOT)));
                    if (ench == null) {
                        throw new IllegalArgumentException("unknown enchantment " + e.getKey());
                    }
                    int level = Integer.parseInt(String.valueOf(e.getValue()));
                    if (level < 1 || level > 255) {
                        throw new IllegalArgumentException("enchantment level must be 1 to 255");
                    }
                    meta.addEnchant(ench, level, true);
                }
            }
            if (Boolean.parseBoolean(String.valueOf(spec.get("glow")))) {
                meta.setEnchantmentGlintOverride(true);
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    /** Reads a map from a config section or a raw map (YAML lists of maps give raw maps). */
    public static Map<?, ?> asMap(Object o) {
        if (o instanceof ConfigurationSection s) {
            return s.getValues(false);
        }
        if (o instanceof Map<?, ?> m) {
            return m;
        }
        return new MemoryConfiguration().getValues(false);
    }

    public static Component text(String legacy) {
        return LEGACY.deserialize(legacy).decoration(TextDecoration.ITALIC, false);
    }

    public static String pretty(Material material) {
        String[] parts = material.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.toString();
    }
}
