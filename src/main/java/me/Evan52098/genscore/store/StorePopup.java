package me.Evan52098.genscore.store;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import me.Evan52098.genscore.info.Bedrock;
import me.Evan52098.genscore.info.Text;
import me.Evan52098.genscore.util.Cooldowns;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * HxCore's {@code store-popup}: walking into one of the configured boxes (e.g. the crate area) shows a window with
 * "Open store" / "Not now" (Paper's dialog API) and, if enabled, the clickable store line in chat. Bedrock players get
 * the chat line only. At most once per player per cooldown, and only when entering a box, not while standing in it.
 * Events run on the moving player's thread; the state is in concurrent collections.
 */
final class StorePopup implements Listener {

    private final Supplier<StoreSettings> settings;
    private final Supplier<Component> storeLine;
    private final Set<UUID> inside = ConcurrentHashMap.newKeySet();
    private final Cooldowns cooldowns = new Cooldowns();

    StorePopup(Supplier<StoreSettings> settings, Supplier<Component> storeLine) {
        this.settings = settings;
        this.storeLine = storeLine;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!event.hasChangedBlock()) {
            return; // only work when the block position changes
        }
        check(event.getPlayer(), event.getTo());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        // Teleporting into the area counts as entering; teleporting out clears the flag.
        check(event.getPlayer(), event.getTo());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        inside.remove(event.getPlayer().getUniqueId());
    }

    private void check(Player player, Location to) {
        StoreSettings s = settings.get();
        StoreSettings.Popup popup = s.popup();
        if (!popup.enabled() || popup.zones().isEmpty() || to.getWorld() == null) {
            return;
        }
        boolean now = inZone(popup.zones(), to);
        UUID id = player.getUniqueId();
        if (!now) {
            inside.remove(id);
            return;
        }
        if (!inside.add(id)) {
            return; // already inside
        }
        if (player.hasPermission("genscore.store.popup.exempt")) {
            return;
        }
        if (!cooldowns.tryStart(id, "popup", popup.cooldownMillis())) {
            return;
        }
        show(player, s);
    }

    private static boolean inZone(List<StoreSettings.Zone> zones, Location at) {
        String world = at.getWorld().getName();
        int x = at.getBlockX();
        int y = at.getBlockY();
        int z = at.getBlockZ();
        for (StoreSettings.Zone zone : zones) {
            if (zone.contains(world, x, y, z)) {
                return true;
            }
        }
        return false;
    }

    /** Shows the pop-up now. Player's thread. */
    void show(Player player, StoreSettings s) {
        StoreSettings.Popup popup = s.popup();
        if (popup.chatLink() || Bedrock.is(player.getUniqueId())) {
            player.sendMessage(storeLine.get());
        }
        if (Bedrock.is(player.getUniqueId()) || !Text.isWebLink(s.url())) {
            return; // Geyser can't show Java dialogs; without a link the window has nothing to open
        }
        ActionButton open = ActionButton.builder(Text.legacy("&a" + popup.openButton()))
                .tooltip(Text.legacy("&7" + s.url()))
                .action(DialogAction.staticAction(ClickEvent.openUrl(s.url().trim())))
                .build();
        ActionButton close = ActionButton.builder(Text.legacy("&7" + popup.closeButton())).build();
        Dialog dialog = Dialog.create(b -> b.empty()
                .base(DialogBase.builder(Text.legacy(popup.title()))
                        .canCloseWithEscape(true)
                        .body(List.of(DialogBody.plainMessage(Text.legacy(popup.message()))))
                        .build())
                .type(DialogType.confirmation(open, close)));
        player.showDialog(dialog);
    }

    void forgetAll() {
        inside.clear();
    }
}
