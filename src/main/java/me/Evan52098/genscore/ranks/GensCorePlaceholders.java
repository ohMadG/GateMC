package me.Evan52098.genscore.ranks;

import me.Evan52098.genscore.cosmetics.CosmeticsModule;
import me.Evan52098.genscore.shards.ShardsModule;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Objects;

/**
 * The one {@code %genscore_*%} PlaceholderAPI expansion (groups E, F, G and N each asked for one; two expansions with
 * the same identifier clash). Every value reads an in-memory cache: never a database query on a render thread.
 * <ul>
 *   <li>ranks (E): badge, badge_text, rank, tag, tag_id</li>
 *   <li>shards (F): shards, shards_formatted (online players only)</li>
 *   <li>cosmetics (N): killrank, kills, namecolor, colored_name</li>
 * </ul>
 */
public final class GensCorePlaceholders extends PlaceholderExpansion {

    private static final LegacyComponentSerializer AMP_HEX =
            LegacyComponentSerializer.builder().character('&').hexCharacter('#').hexColors().build();

    @Override public @NotNull String getIdentifier() { return "genscore"; }
    @Override public @NotNull String getAuthor() { return "GensCore"; }
    @Override public @NotNull String getVersion() { return "1.0"; }
    @Override public boolean persist() { return true; }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) {
            return "";
        }
        String id = params.toLowerCase(Locale.ROOT);
        Player online = player.getPlayer();
        switch (id) {
            case "shards", "shards_formatted" -> {
                var s = ShardsModule.service();
                if (s == null || online == null) {
                    return "0";
                }
                long n = s.balance(player.getUniqueId());
                return id.equals("shards") ? Long.toString(n) : String.format("%,d", n);
            }
            case "killrank" -> {
                return CosmeticsModule.killRank(player.getUniqueId());
            }
            case "kills" -> {
                return String.valueOf(Math.max(0, CosmeticsModule.kills(player.getUniqueId())));
            }
            case "namecolor" -> {
                return CosmeticsModule.nameColor(player.getUniqueId());
            }
            case "colored_name" -> {
                return player.getName() == null ? "" : CosmeticsModule.coloredNameLegacy(player.getUniqueId(), player.getName());
            }
            default -> {
                return RanksModule.placeholder(online, params); // badge, badge_text, rank, tag, tag_id
            }
        }
    }
}
