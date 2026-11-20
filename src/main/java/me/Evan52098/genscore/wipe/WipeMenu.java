package me.Evan52098.genscore.wipe;

import me.Evan52098.genscore.menu.Lore;
import me.Evan52098.genscore.menu.Menu;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * BlockvilleCore's WipeGui on GensCore's {@link Menu}: a toggle per kind of data (inventory, ender chest, experience,
 * player vaults, money, everything else), "Wipe Everything", and "Confirm Wipe", which closes the menu and asks for a
 * staff-only reason in chat before anything runs. The island toggle is gone (no islands here). Slots follow
 * BlockvilleCore's gui.yml layout.
 */
final class WipeMenu {

    private static final String EVERYTHING_ELSE = "everything-else";
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    /** Toggle key -> slot and the module:section keys it covers. */
    private record Toggle(String key, int slot, Material icon, String label, List<String> sections, String[] description) {
    }

    private static final List<Toggle> TOGGLES = List.of(
            new Toggle("inventory", 10, Material.CHEST, "Inventory", List.of("core:inventory", "staff:inv_pending"), new String[0]),
            new Toggle("enderchest", 11, Material.ENDER_CHEST, "Ender Chest", List.of("core:enderchest"), new String[0]),
            new Toggle("experience", 12, Material.EXPERIENCE_BOTTLE, "Experience", List.of("core:experience"), new String[0]),
            new Toggle("playervaults", 13, Material.SHULKER_BOX, "Player Vaults", List.of("core:playervaults"), new String[0]),
            new Toggle("balance", 14, Material.SUNFLOWER, "Balance (Money)", List.of("core:balance", "core:balance_pending"), new String[0]),
            new Toggle(EVERYTHING_ELSE, 16, Material.BUNDLE, "Everything Else", List.of(),
                    new String[]{"Homes, kits, cooldowns,", "settings, vault slots and", "every other tracked", "section not listed above."})
    );

    private final WipeService service;
    private final ChatPrompt prompt;

    WipeMenu(WipeService service, ChatPrompt prompt) {
        this.service = service;
        this.prompt = prompt;
    }

    /** Staff member's thread. */
    void open(Player staff, String targetName) {
        open(staff, targetName, new LinkedHashSet<>());
    }

    private void open(Player staff, String targetName, Set<String> wipeKeys) {
        Menu menu = new Menu(4, LEGACY.deserialize("&8Wipe " + targetName));
        for (Toggle toggle : TOGGLES) {
            boolean willWipe = wipeKeys.contains(toggle.key());
            Lore.Accent accent = willWipe ? Lore.Accent.RED : Lore.Accent.GREEN;
            Lore lore = Lore.builder(accent).subcategory("Wipe " + targetName).divider();
            if (toggle.description().length > 0) {
                lore.description(toggle.description()).divider();
            }
            lore.stat("Status", willWipe ? "Will Be Wiped" : "Kept").clickPrompt("Click to Toggle");
            ItemStack item = Lore.item(willWipe ? Material.RED_DYE : Material.LIME_DYE, accent.code() + "&l" + toggle.label(), lore.build());
            menu.set(toggle.slot(), item, click -> {
                Set<String> next = new LinkedHashSet<>(wipeKeys);
                if (willWipe) {
                    next.remove(toggle.key());
                } else {
                    next.add(toggle.key());
                }
                open(staff, targetName, next);
            });
        }
        menu.set(27, Lore.item(Material.TNT, Lore.Accent.RED.code() + "&lWipe Everything", Lore.builder(Lore.Accent.RED)
                        .subcategory("Wipe " + targetName).divider()
                        .description("&cSets every toggle above", "&cto Will Be Wiped.").divider()
                        .clickPrompt("Click to Select All").build()),
                click -> {
                    Set<String> everything = new LinkedHashSet<>();
                    TOGGLES.forEach(t -> everything.add(t.key()));
                    open(staff, targetName, everything);
                });
        menu.set(31, Lore.item(Material.REDSTONE_BLOCK, Lore.Accent.RED.code() + "&lConfirm Wipe", Lore.builder(Lore.Accent.RED)
                        .subcategory("Wipe " + targetName).divider()
                        .description("Kicks the player, wipes", "whatever is currently set", "to Will Be Wiped, then asks",
                                "for a staff-only reason", "in chat. A backup is", "written first.")
                        .clickPrompt("Click to Confirm").build()),
                click -> {
                    staff.closeInventory();
                    WipeKeepList keepList = keepList(wipeKeys);
                    staff.sendMessage(LEGACY.deserialize("&7Type the reason for this wipe in chat. (staff-only - the player will not see it; type 'cancel' to abort)"));
                    prompt.await(staff, reason -> {
                        String trimmed = reason.trim();
                        if (trimmed.isBlank() || trimmed.equalsIgnoreCase("cancel")) {
                            staff.sendMessage(LEGACY.deserialize("&7Wipe canceled."));
                            return;
                        }
                        service.confirmCustom(staff, targetName, keepList, trimmed);
                    });
                });
        menu.set(35, Lore.item(Material.BARRIER, Lore.Accent.RED.code() + "&lClose", Lore.builder(Lore.Accent.RED).clickPrompt("Click to Close").build()),
                click -> staff.closeInventory());
        menu.open(staff);
    }

    /** Everything not toggled to "Will Be Wiped" is kept. */
    private WipeKeepList keepList(Set<String> wipeKeys) {
        Set<String> named = new LinkedHashSet<>();
        Map<String, List<String>> kept = new LinkedHashMap<>();
        for (Toggle toggle : TOGGLES) {
            named.addAll(toggle.sections());
            if (!wipeKeys.contains(toggle.key())) {
                for (String section : toggle.sections()) {
                    String[] parts = section.split(":", 2);
                    kept.computeIfAbsent(parts[0], k -> new ArrayList<>()).add(parts[1]);
                }
            }
        }
        if (!wipeKeys.contains(EVERYTHING_ELSE)) {
            for (WipeSection section : service.registry().sections()) {
                if (!named.contains(section.key())) {
                    kept.computeIfAbsent(section.module(), k -> new ArrayList<>()).add(section.id());
                }
            }
        }
        return WipeKeepList.of(kept);
    }
}
