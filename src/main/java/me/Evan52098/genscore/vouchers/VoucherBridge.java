package me.Evan52098.genscore.vouchers;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The voucher system opened to other modules (blockville-common's VoucherBridge), so their vouchers get the same
 * unique IDs, issue records, single-use redemption and duplicate sweeps instead of a second, untracked system.
 *
 * <p>A module's vouchers are told apart by a "kind" stored where a normal voucher keeps its template name, e.g.
 * {@code "rank:vip"}. The module registers a handler for its prefix ({@code "rank:"}); the voucher system asks it whether
 * a player may redeem one, and hands it the reward once the voucher's ID has been atomically marked used.
 * Get it with {@code plugin.modules().get(VouchersModule.class).bridge()}.
 */
public interface VoucherBridge {

    /** Records {@code amount} new vouchers of this kind; completes with their IDs (one per physical item). */
    CompletableFuture<List<String>> issue(String kind, @Nullable Player issuedBy, UUID issuedToUuid, String issuedToName, int amount,
                                          @Nullable String source);

    /** Turns an item into the physical voucher with this ID: tags it and adds the status line to its lore. */
    void tag(ItemStack item, String voucherId, String kind);

    void registerHandler(String kindPrefix, KindHandler handler);

    interface KindHandler {
        /** Why this player cannot redeem it right now (the voucher is kept), or null when they can. Player's thread. */
        @Nullable
        Component refuse(Player player, String kind);

        /** Give the reward. Called on the player's thread after the ID was marked redeemed and the item removed. */
        void redeem(Player player, String kind, String voucherId);
    }
}
