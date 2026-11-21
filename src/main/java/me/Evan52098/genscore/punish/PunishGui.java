package me.Evan52098.genscore.punish;

import me.Evan52098.genscore.menu.MenuStyle;
import me.Evan52098.genscore.menu.Lore;
import me.Evan52098.genscore.menu.Lore.Accent;
import me.Evan52098.genscore.menu.Menu;
import me.Evan52098.genscore.scheduler.Tasks;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PunishGui {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("MM/dd/yyyy").withZone(ZoneOffset.UTC);
    private static final Pattern SHADOW_MUTE_DURATION_PATTERN = Pattern.compile("(\\d{1,5})\\s*(h|d|w|mo)");

    private record CategoryButton(Category category, int slot, Material material, String label, List<String> lore, String permission) {
    }

    private static final String PERM_CATEGORY_CHAT = "genscore.punish.category.chat";
    private static final String PERM_CATEGORY_SEVERE_CONDUCT = "genscore.punish.category.severeconduct";
    private static final String PERM_CATEGORY_GAMEPLAY_CHEATING = "genscore.punish.category.gameplaycheating";
    private static final String PERM_CATEGORY_ACCOUNT_SECURITY = "genscore.punish.category.accountsecurity";

    private static final List<CategoryButton> CATEGORY_BUTTONS = List.of(
            new CategoryButton(Category.CHAT, 9, Material.WRITABLE_BOOK, "Chat Offenses",
                    List.of("Spam, advertising, disrespect,", "toxicity, impersonation, etc."), PERM_CATEGORY_CHAT),
            new CategoryButton(Category.SEVERE_CONDUCT, 11, Material.DIAMOND_HELMET, "Severe Chat & Conduct Offenses",
                    List.of("Harassment, discrimination, slurs,", "sexual harassment, threats, etc."), PERM_CATEGORY_SEVERE_CONDUCT),
            new CategoryButton(Category.GAMEPLAY_CHEATING, 13, Material.IRON_SWORD, "Gameplay & Cheating Offenses",
                    List.of("Hacking, griefing, scamming,", "advertising, bug abuse, etc."), PERM_CATEGORY_GAMEPLAY_CHEATING),
            new CategoryButton(Category.ACCOUNT_SECURITY, 15, Material.NAME_TAG, "Account, Identity & Security Offenses",
                    List.of("Exploits, real-world trading,", "impersonation, evasion, etc."), PERM_CATEGORY_ACCOUNT_SECURITY));

    private static String categoryPermission(Category category) {
        for (CategoryButton cat : CATEGORY_BUTTONS) {
            if (cat.category() == category) {
                return cat.permission();
            }
        }
        return PunishCommand.PERMISSION_GUI;
    }

    private static final int HISTORY_BUTTON_SLOT = 8;
    private static final int NOTES_BUTTON_SLOT = 17;
    private static final int KICK_SLOT = 26;
    private static final int ALTS_BUTTON_SLOT = 35;
    private static final int ADMIN_ACTIONS_SLOT = 44;
    private static final int CLOSE_SLOT = 45;
    private static final int HISTORY_PAGE_SIZE = 45;
    private static final int[] CATEGORY_OFFENSE_SLOTS = {
            10, 12, 14, 16,
            19, 21, 23, 25,
            28, 30, 32, 34,
            37, 39, 41, 43
    };
    private static final int CATEGORY_PAGE_SIZE = CATEGORY_OFFENSE_SLOTS.length;
    private static final int PREVIOUS_PAGE_SLOT = 45;
    private static final int NEXT_PAGE_SLOT = 53;

    private final Warden warden;
    private final ChatInput chatInput;
    private final String adminPermission;
    private final String revokePermission;
    private final NoteService noteService;

    PunishGui(Warden warden, ChatInput chatInput, String adminPermission, String revokePermission, NoteService noteService) {
        this.warden = warden;
        this.chatInput = chatInput;
        this.adminPermission = adminPermission;
        this.revokePermission = revokePermission;
        this.noteService = noteService;
    }

    void open(Player staff, UUID targetUuid, String targetName, String reason) {
        boolean admin = staff.hasPermission(adminPermission);
        CompletableFuture<Warden.ActiveStatus> statusFuture = warden.activeStatus(targetUuid);
        List<CompletableFuture<Integer>> countFutures = new ArrayList<>();
        for (CategoryButton cat : CATEGORY_BUTTONS) {
            countFutures.add(warden.punishments().countCategoryOffenses(targetUuid, cat.category()));
        }
        CompletableFuture.allOf(countFutures.toArray(new CompletableFuture[0])).thenCompose(ignored -> statusFuture)
                .thenAccept(status -> {
                    List<Integer> counts = countFutures.stream().map(CompletableFuture::join).toList();
                    Tasks.onEntity(staff, () -> render(staff, targetUuid, targetName, reason, status, admin, counts));
                });
    }

    private static final int[] CATEGORY_SLOTS = {19, 21, 23, 25};

    private void render(Player staff, UUID targetUuid, String targetName, String reason, Warden.ActiveStatus status, boolean admin, List<Integer> categoryCounts) {
        Menu menu = new Menu(6, MenuStyle.title("&8Punish " + targetName));

        menu.set(4, targetHead(targetUuid, targetName, reason, status), click -> {
            staff.closeInventory();
            promptCustomReason(staff, targetUuid, targetName, reason);
        });

        menu.set(HISTORY_BUTTON_SLOT, Lore.item(Material.BLACK_DYE, "&bPlayer History", Lore.builder(Accent.CYAN)
                .description("Click to view " + targetName + "'s", "full punishment history.")
                .clickPrompt("Click to Open")
                .build()), click -> {
            staff.closeInventory();
            openHistoryFilter(staff, targetUuid, targetName, reason);
        });

        for (int i = 0; i < CATEGORY_BUTTONS.size(); i++) {
            CategoryButton cat = CATEGORY_BUTTONS.get(i);
            if (!staff.hasPermission(cat.permission())) {
                menu.set(CATEGORY_SLOTS[i], noPermissionItem(cat.material(), cat.label()));
                continue;
            }
            List<String> lore = Lore.builder(Accent.CYAN)
                    .description(cat.lore().toArray(new String[0]))
                    .divider()
                    .stat("Current Offense", "#" + (categoryCounts.get(i) + 1))
                    .clickPrompt("Click to Open")
                    .build();
            menu.set(CATEGORY_SLOTS[i], Lore.item(cat.material(), "&b" + cat.label(), lore), click -> {
                staff.closeInventory();
                openCategory(staff, cat.category(), targetUuid, targetName, reason, 0);
            });
        }

        menu.set(NOTES_BUTTON_SLOT, Lore.item(Material.WRITTEN_BOOK, "&bStaff Notes", Lore.builder(Accent.CYAN)
                .description("Click to view/add notes", "about " + targetName + ".")
                .clickPrompt("Click to Open")
                .build()), click -> {
            staff.closeInventory();
            openNotes(staff, targetUuid, targetName, reason, 0);
        });

        if (staff.hasPermission(PunishCommand.PERMISSION_ACTION_KICK)) {
            menu.set(KICK_SLOT, Lore.item(Material.WOODEN_SWORD, "&cKick", Lore.builder(Accent.RED)
                    .description("Removes the player from the", "server immediately - does not", "prevent them rejoining.")
                    .divider()
                    .description("Player must be online.")
                    .clickPrompt("Click to Kick")
                    .build()), click -> {
                staff.closeInventory();
                kickClick(staff, targetUuid, targetName, reason);
            });
        } else {
            menu.set(KICK_SLOT, noPermissionItem(Material.WOODEN_SWORD, "Kick"));
        }

        menu.set(ALTS_BUTTON_SLOT, Lore.item(Material.ENDER_EYE, "&bKnown Alts", Lore.builder(Accent.CYAN)
                .description("Click to view accounts that", "have logged in from the same", "IP as " + targetName + ".")
                .clickPrompt("Click to Open")
                .build()), click -> {
            staff.closeInventory();
            openAlts(staff, targetUuid, targetName, reason);
        });

        if (admin) {
            menu.set(ADMIN_ACTIONS_SLOT, Lore.item(Material.COMMAND_BLOCK, "&c&lAdmin Actions", Lore.builder(Accent.RED)
                    .description("IP Ban, Shadow Mute and Blacklist.")
                    .clickPrompt("Click to Open")
                    .build()), click -> {
                staff.closeInventory();
                openAdminActions(staff, targetUuid, targetName, reason);
            });
        } else {
            menu.set(ADMIN_ACTIONS_SLOT, noPermissionItem(Material.COMMAND_BLOCK, "Admin Actions"));
        }

        menu.set(CLOSE_SLOT, closeButton(), click -> staff.closeInventory());

        menu.open(staff);
    }

    private void openCategory(Player staff, Category category, UUID targetUuid, String targetName, String reason, int page) {
        if (!staff.hasPermission(categoryPermission(category))) {
            staff.sendMessage(legacy("&cYou don't have permission to view that category."));
            return;
        }
        List<Offense> offenses = new ArrayList<>(OffenseCatalog.inCategory(category));
        offenses.sort(java.util.Comparator.comparingInt(PunishGui::colorRank));
        List<CompletableFuture<PunishmentSentence>> sentenceFutures = new ArrayList<>();
        for (Offense offense : offenses) {
            sentenceFutures.add(warden.sentenceFor(offense, targetUuid));
        }
        CompletableFuture.allOf(sentenceFutures.toArray(new CompletableFuture[0])).thenAccept(ignored -> {
            List<PunishmentSentence> sentences = sentenceFutures.stream().map(CompletableFuture::join).toList();
            Tasks.onEntity(staff, () -> buildCategoryGui(staff, category, targetUuid, targetName, reason, offenses, sentences, page));
        });
    }

    private void buildCategoryGui(Player staff, Category category, UUID targetUuid, String targetName, String reason,
                                   List<Offense> offenses, List<PunishmentSentence> sentences, int page) {
        PageSlice<Offense> slice = paginate(offenses, page, CATEGORY_PAGE_SIZE);
        Menu menu = new Menu(6, MenuStyle.title(pagedTitle("&8" + categoryLabel(category), slice)));

        int base = slice.page() * CATEGORY_PAGE_SIZE;
        for (int i = 0; i < slice.items().size(); i++) {
            Offense offense = slice.items().get(i);
            PunishmentSentence sentence = sentences.get(base + i);
            Accent accent = accentForOffense(offense);
            Lore builder = Lore.builder(accent)
                    .stat("Offense", ordinalOffense(sentence.pastOffenses() + 1))
                    .stat(typeLabel(sentence) + " Duration", formatDuration(sentence))
                    .divider()
                    .description(offense.description().toArray(new String[0]));
            if (!offense.notes().isEmpty()) {
                builder.divider().description(offense.notes().toArray(new String[0]));
            }
            List<String> lore = builder.clickPrompt("Click to Apply").build();
            if (offense.unappealable()) {
                lore = new ArrayList<>(lore);
                lore.add(lore.size() - 2, "");
                lore.add(lore.size() - 2, "&c&lUnappealable");
            }
            menu.set(CATEGORY_OFFENSE_SLOTS[i], Lore.item(offenseIcon(offense), accent.code() + offense.name(), lore), click -> {
                staff.closeInventory();
                offenseClick(staff, offense, targetUuid, targetName, reason);
            });
        }

        addPageButtons(menu, staff, slice, p -> openCategory(staff, category, targetUuid, targetName, reason, p));
        menu.set(49, backButton(), click -> {
            staff.closeInventory();
            open(staff, targetUuid, targetName, reason);
        });

        menu.open(staff);
    }

    private static String categoryLabel(Category category) {
        for (CategoryButton cat : CATEGORY_BUTTONS) {
            if (cat.category() == category) {
                return cat.label();
            }
        }
        return category.name();
    }

    private static Material offenseIcon(Offense offense) {
        if (offense.unappealable()) {
            return Material.BLACK_DYE;
        }
        boolean anyBan = offense.ladder().stream().anyMatch(Offense.Rung::ban);
        boolean anyMute = offense.ladder().stream().anyMatch(r -> !r.ban());
        if (anyBan && anyMute) {
            return Material.ORANGE_DYE;
        }
        return anyBan ? Material.RED_DYE : Material.LIME_DYE;
    }

    private static int colorRank(Offense offense) {
        if (offense.unappealable()) {
            return 3;
        }
        boolean anyBan = offense.ladder().stream().anyMatch(Offense.Rung::ban);
        boolean anyMute = offense.ladder().stream().anyMatch(r -> !r.ban());
        if (anyBan && anyMute) {
            return 1;
        }
        return anyBan ? 2 : 0;
    }

    private static Accent accentForOffense(Offense offense) {
        if (offense.unappealable()) {
            return Accent.WHITE;
        }
        boolean anyBan = offense.ladder().stream().anyMatch(Offense.Rung::ban);
        boolean anyMute = offense.ladder().stream().anyMatch(r -> !r.ban());
        if (anyBan && anyMute) {
            return Accent.WHITE;
        }
        return anyBan ? Accent.RED : Accent.GREEN;
    }

    private void offenseClick(Player staff, Offense offense, UUID targetUuid, String targetName, String reason) {
        String finalReason = reason == null || reason.isBlank() || reason.equals("No reason given") ? offense.name() : reason;
        warden.sentenceFor(offense, targetUuid)
                .thenCompose(sentence -> warden.apply(sentence, targetUuid, targetName, staff, finalReason))
                .thenAccept(result -> Tasks.onEntity(staff, () -> report(staff, targetName, result)));
    }

    private void kickClick(Player staff, UUID targetUuid, String targetName, String reason) {
        Player online = Bukkit.getPlayer(targetUuid);
        if (online == null) {
            staff.sendMessage(legacy("&f" + targetName + "&c is not online - there's nothing to kick them from."));
            return;
        }
        warden.applyKick(targetUuid, targetName, staff, reason).thenAccept(result -> Tasks.onEntity(staff, () -> report(staff, targetName, result)));
    }

    private void openAdminActions(Player staff, UUID targetUuid, String targetName, String reason) {
        Menu menu = new Menu(4, MenuStyle.title("&8Admin Actions: " + targetName));

        menu.set(10, Lore.item(Material.EMERALD_BLOCK, "&cPermanent Mute", Lore.builder(Accent.RED)
                .stat("Mute Duration", "Permanent")
                .divider()
                .description("Joking About Suicide is an instant", "permanent, no ladder.")
                .divider()
                .description("Use a detailed reason.")
                .clickPrompt("Click to Apply")
                .build()), click -> {
            staff.closeInventory();
            permanentMuteClick(staff, targetUuid, targetName, reason);
        });

        menu.set(12, Lore.item(Material.REDSTONE_BLOCK, "&cPermanent Ban", Lore.builder(Accent.RED)
                .stat("Ban Duration", "Permanent")
                .divider()
                .description("Use a detailed reason.")
                .clickPrompt("Click to Apply")
                .build()), click -> {
            staff.closeInventory();
            permanentBanClick(staff, targetUuid, targetName, reason);
        });

        menu.set(14, Lore.item(Material.TNT, "&cIP Ban", Lore.builder(Accent.RED)
                .stat("Ban Duration", "Permanent")
                .divider()
                .description("Blocks the player's IP address", "outright - any account connecting", "from it is denied.")
                .divider()
                .description("Uses their live connection if", "online, otherwise their most", "recent known IP.")
                .divider()
                .description("Use a detailed reason.")
                .clickPrompt("Click to Apply")
                .build()), click -> {
            staff.closeInventory();
            ipBanClick(staff, targetUuid, targetName, reason);
        });

        menu.set(16, Lore.item(Material.PHANTOM_MEMBRANE, "&cShadow Mute", Lore.builder(Accent.RED)
                .description("The player is never told they", "were muted - their messages just", "stop reaching anyone else.")
                .divider()
                .description("Use a detailed reason.")
                .clickPrompt("Click to Choose a Duration")
                .build()), click -> {
            staff.closeInventory();
            shadowMuteClick(staff, targetUuid, targetName, reason);
        });

        if (staff.hasPermission(PunishCommand.PERMISSION_ACTION_BLACKLIST)) {
            menu.set(22, Lore.item(Material.NETHERITE_BLOCK, "&c&lBlacklist", Lore.builder(Accent.RED)
                    .description("IP-bans this player, marked", "unappealable. Think very", "carefully before using this.")
                    .divider()
                    .description("Use for:", "Nazi Symbols / Hate Speech,", "Threats to Personal Safety.")
                    .divider()
                    .description("Use a detailed reason.")
                    .clickPrompt("Click to Apply")
                    .build()), click -> {
                staff.closeInventory();
                blacklistClick(staff, targetUuid, targetName, reason);
            });
        }

        menu.set(31, backButton(), click -> {
            staff.closeInventory();
            open(staff, targetUuid, targetName, reason);
        });

        menu.open(staff);
    }

    private void permanentMuteClick(Player staff, UUID targetUuid, String targetName, String reason) {
        warden.permanentSentence(Category.CHAT)
                .thenCompose(sentence -> warden.apply(sentence, targetUuid, targetName, staff, reason))
                .thenAccept(result -> Tasks.onEntity(staff, () -> report(staff, targetName, result)));
    }

    private void permanentBanClick(Player staff, UUID targetUuid, String targetName, String reason) {
        warden.permanentSentence(Category.OTHER)
                .thenCompose(sentence -> warden.apply(sentence, targetUuid, targetName, staff, reason))
                .thenAccept(result -> Tasks.onEntity(staff, () -> report(staff, targetName, result)));
    }

    private void ipBanClick(Player staff, UUID targetUuid, String targetName, String reason) {
        Player online = Bukkit.getPlayer(targetUuid);
        if (online != null && online.getAddress() != null) {
            applyIpBan(staff, targetUuid, targetName, reason, online.getAddress().getAddress().getHostAddress());
            return;
        }
        warden.punishments().mostRecentIp(targetUuid).thenAccept(ip -> Tasks.onEntity(staff, () -> {
            if (ip == null) {
                staff.sendMessage(legacy("&f" + targetName + "&c has no known IP on record to ban - they need to have logged in at least once."));
                return;
            }
            applyIpBan(staff, targetUuid, targetName, reason, ip);
        }));
    }

    private void applyIpBan(Player staff, UUID targetUuid, String targetName, String reason, String ip) {
        warden.applyIpBan(targetUuid, targetName, staff, reason, ip).thenAccept(result -> Tasks.onEntity(staff, () -> report(staff, targetName, result)));
    }

    private void blacklistClick(Player staff, UUID targetUuid, String targetName, String reason) {
        if (!staff.hasPermission(PunishCommand.PERMISSION_ACTION_BLACKLIST)) {
            staff.sendMessage(legacy("&cYou don't have permission to blacklist players."));
            return;
        }
        promptChat(staff, "&cWrite the reason for blacklisting " + targetName + " - this permanently bans their account and their IP. (type 'cancel' to abort)",
                "&7Blacklist cancelled.", () -> {}, input -> resolveBlacklistIp(staff, targetUuid, targetName, "[BLACKLIST - UNAPPEALABLE] " + input));
    }

    private void resolveBlacklistIp(Player staff, UUID targetUuid, String targetName, String taggedReason) {
        Player online = Bukkit.getPlayer(targetUuid);
        if (online != null && online.getAddress() != null) {
            applyBlacklist(staff, targetUuid, targetName, taggedReason, online.getAddress().getAddress().getHostAddress());
            return;
        }
        warden.punishments().mostRecentIp(targetUuid).thenAccept(ip -> Tasks.onEntity(staff, () -> applyBlacklist(staff, targetUuid, targetName, taggedReason, ip)));
    }

    private void applyBlacklist(Player staff, UUID targetUuid, String targetName, String taggedReason, String ip) {
        warden.permanentSentence(Category.OTHER)
                .thenCompose(sentence -> warden.apply(sentence, targetUuid, targetName, staff, taggedReason))
                .thenAccept(banResult -> {
                    if (ip == null) {
                        Tasks.onEntity(staff, () -> {
                            report(staff, targetName, banResult);
                            staff.sendMessage(legacy("&c" + targetName + " has no known IP on record - only their account was banned."));
                        });
                        return;
                    }
                    warden.applyIpBan(targetUuid, targetName, staff, taggedReason, ip).thenAccept(ipResult -> Tasks.onEntity(staff, () -> {
                        report(staff, targetName, banResult);
                        report(staff, targetName, ipResult);
                    }));
                });
    }

    private void shadowMuteClick(Player staff, UUID targetUuid, String targetName, String reason) {
        promptChat(staff, "&7How long should the shadow mute last? (e.g. 1h, 1d, 1w, 1mo - type &f0&7 to make it permanent, or 'cancel' to abort)",
                "&7Cancelled.", () -> {}, input -> handleShadowMuteDuration(staff, targetUuid, targetName, reason, input));
    }

    private void handleShadowMuteDuration(Player staff, UUID targetUuid, String targetName, String reason, String input) {
        if (input.equals("0")) {
            warden.applyShadowMute(targetUuid, targetName, staff, reason, null).thenAccept(result -> Tasks.onEntity(staff, () -> report(staff, targetName, result)));
            return;
        }
        Long durationMs = parseShadowMuteDuration(input);
        if (durationMs == null) {
            staff.sendMessage(legacy("&cCouldn't read that duration - use a number plus h/d/w/mo, like 1h or 3d, or 0 for permanent."));
            shadowMuteClick(staff, targetUuid, targetName, reason);
            return;
        }
        warden.applyShadowMute(targetUuid, targetName, staff, reason, durationMs).thenAccept(result -> Tasks.onEntity(staff, () -> report(staff, targetName, result)));
    }

    private static Long parseShadowMuteDuration(String input) {
        Matcher matcher = SHADOW_MUTE_DURATION_PATTERN.matcher(input.toLowerCase(Locale.ROOT));
        long totalMs = 0L;
        boolean matchedAny = false;
        while (matcher.find()) {
            matchedAny = true;
            long value = Long.parseLong(matcher.group(1));
            totalMs += switch (matcher.group(2)) {
                case "h" -> value * 3_600_000L;
                case "d" -> value * 86_400_000L;
                case "w" -> value * 604_800_000L;
                case "mo" -> value * 2_592_000_000L;
                default -> 0L;
            };
        }
        return matchedAny && totalMs > 0L ? totalMs : null;
    }

    private void promptCustomReason(Player staff, UUID targetUuid, String targetName, String reason) {
        promptChat(staff, "&7Please write the punishment reason. (type 'cancel' to abort)", "&7Cancelled.",
                () -> open(staff, targetUuid, targetName, reason),
                input -> open(staff, targetUuid, targetName, input));
    }

    private void openHistoryFilter(Player staff, UUID targetUuid, String targetName, String reason) {
        Menu menu = new Menu(3, MenuStyle.title("&8" + targetName + "'s History"));
        menu.set(11, Lore.item(Material.LIME_DYE, "&aActive Punishments", Lore.builder(Accent.GREEN)
                .description("Punishments currently in effect.")
                .clickPrompt("Click to View")
                .build()), click -> {
            staff.closeInventory();
            openHistory(staff, targetUuid, targetName, reason, 0, "ACTIVE");
        });
        menu.set(13, Lore.item(Material.GRAY_DYE, "&fExpired Punishments", Lore.builder(Accent.WHITE)
                .description("Punishments that ran their", "course naturally.")
                .clickPrompt("Click to View")
                .build()), click -> {
            staff.closeInventory();
            openHistory(staff, targetUuid, targetName, reason, 0, "EXPIRED");
        });
        menu.set(15, Lore.item(Material.RED_DYE, "&cRevoked Punishments", Lore.builder(Accent.RED)
                .description("Punishments lifted early by staff.")
                .clickPrompt("Click to View")
                .build()), click -> {
            staff.closeInventory();
            openHistory(staff, targetUuid, targetName, reason, 0, "REVOKED");
        });
        menu.set(22, backButton(), click -> {
            staff.closeInventory();
            open(staff, targetUuid, targetName, reason);
        });
        menu.open(staff);
    }

    private static String statusOf(PunishmentData record) {
        if (record.isInstantType()) {
            return "EXPIRED";
        }
        if (!record.active()) {
            return "REVOKED";
        }
        if (record.expiresAt() != null && record.expiresAt() <= System.currentTimeMillis()) {
            return "EXPIRED";
        }
        return "ACTIVE";
    }

    private static Material statusIcon(String status) {
        return switch (status) {
            case "ACTIVE" -> Material.LIME_DYE;
            case "REVOKED" -> Material.RED_DYE;
            default -> Material.GRAY_DYE;
        };
    }

    private static Accent statusAccent(String status) {
        return switch (status) {
            case "ACTIVE" -> Accent.GREEN;
            case "REVOKED" -> Accent.RED;
            default -> Accent.WHITE;
        };
    }

    private static boolean isRevocable(PunishmentData record) {
        return !record.isInstantType() && record.isCurrentlyInEffect();
    }

    private String revokePermissionFor(PunishmentData record) {
        boolean isBan = PunishmentService.TYPE_BAN.equals(record.type()) || PunishmentService.TYPE_IPBAN.equals(record.type());
        return isBan ? PunishCommand.PERMISSION_ACTION_UNBAN : revokePermission;
    }

    private void openHistory(Player staff, UUID targetUuid, String targetName, String reason, int page, String statusFilter) {
        warden.punishments().history(targetUuid, 500).thenAccept(history -> {
            List<PunishmentData> filtered = new ArrayList<>();
            for (PunishmentData record : history) {
                if (statusOf(record).equals(statusFilter)) {
                    filtered.add(record);
                }
            }
            Tasks.onEntity(staff, () -> buildHistoryGui(staff, targetUuid, targetName, reason, filtered, page, statusFilter));
        });
    }

    private void buildHistoryGui(Player staff, UUID targetUuid, String targetName, String reason, List<PunishmentData> history, int page, String statusFilter) {
        PageSlice<PunishmentData> slice = paginate(history, page);
        Menu menu = new Menu(6, MenuStyle.title(pagedTitle("&8" + targetName + "'s History", slice)));

        for (int i = 0; i < slice.items().size(); i++) {
            PunishmentData record = slice.items().get(i);
            String status = statusOf(record);
            Accent accent = statusAccent(status);
            Lore builder = Lore.builder(accent)
                    .stat("ID", record.displayId())
                    .stat("Status", status)
                    .divider()
                    .stat("Reason", record.reason())
                    .stat("Staff", record.staffName())
                    .stat("Date", DATE_FORMAT.format(Instant.ofEpochMilli(record.createdAt())));
            boolean revocable = isRevocable(record) && staff.hasPermission(revokePermissionFor(record));
            if (revocable) {
                builder.clickPrompt("Click to Revoke");
            }
            ItemStack item = Lore.item(statusIcon(status), record.type() + " &8- " + (record.category() == null || record.category().isBlank() ? "OTHER" : record.category())
                    + " &7(" + record.displayId() + ")", builder.build());
            if (revocable) {
                menu.set(i, item, click -> {
                    staff.closeInventory();
                    revokeFromHistory(staff, record, targetUuid, targetName, reason, slice.page(), statusFilter);
                });
            } else {
                menu.set(i, item);
            }
        }

        addPageButtons(menu, staff, slice, p -> buildHistoryGui(staff, targetUuid, targetName, reason, history, p, statusFilter));
        menu.set(49, backButton(), click -> {
            staff.closeInventory();
            openHistoryFilter(staff, targetUuid, targetName, reason);
        });

        menu.open(staff);
    }

    private void revokeFromHistory(Player staff, PunishmentData record, UUID targetUuid, String targetName, String reason, int page, String statusFilter) {
        if (!staff.hasPermission(revokePermissionFor(record))) {
            staff.sendMessage(legacy("&cYou don't have permission to revoke punishments."));
            return;
        }
        Runnable reopen = () -> openHistory(staff, targetUuid, targetName, reason, page, statusFilter);
        promptChat(staff, "&7Please write the reason you're revoking this punishment. (type 'cancel' to abort)", "&7Revoke cancelled.", reopen,
                input -> warden.revoke(record.id(), staff.getName(), input).thenAccept(ok -> Tasks.onEntity(staff, () -> {
                    staff.sendMessage(ok ? legacy("&aRevoked punishment &f" + record.displayId() + "&a.") : legacy("&cThat punishment was already inactive."));
                    reopen.run();
                })));
    }

    private void openAlts(Player staff, UUID targetUuid, String targetName, String reason) {
        warden.punishments().findAltAccounts(targetUuid).thenAccept(alts -> Tasks.onEntity(staff, () -> {
            Menu menu = new Menu(6, MenuStyle.title("&8" + targetName + "'s Known Alts"));
            if (alts.isEmpty()) {
                menu.set(22, Lore.item(Material.BARRIER, "&cNo known alts", Lore.builder(Accent.RED)
                        .description("No other accounts have logged in", "from the same IP as " + targetName + ".")
                        .build()));
            } else {
                int slot = 0;
                for (UUID alt : alts) {
                    if (slot >= 45) {
                        break;
                    }
                    String altName = Bukkit.getOfflinePlayer(alt).getName();
                    menu.set(slot, altHead(alt, altName == null ? alt.toString() : altName));
                    slot++;
                }
            }
            menu.set(49, backButton(), click -> {
                staff.closeInventory();
                open(staff, targetUuid, targetName, reason);
            });
            menu.open(staff);
        }));
    }

    private static ItemStack altHead(UUID uuid, String name) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        meta.displayName(legacy("&b" + name).decoration(TextDecoration.ITALIC, false));
        meta.setOwningPlayer(Bukkit.getOfflinePlayer(uuid));
        item.setItemMeta(meta);
        return item;
    }

    private void openNotes(Player staff, UUID targetUuid, String targetName, String reason, int page) {
        noteService.history(targetUuid).thenAccept(notes -> Tasks.onEntity(staff, () -> buildNotesGui(staff, targetUuid, targetName, reason, notes, page)));
    }

    private void buildNotesGui(Player staff, UUID targetUuid, String targetName, String reason, List<PlayerNote> notes, int page) {
        PageSlice<PlayerNote> slice = paginate(notes, page);
        Menu menu = new Menu(6, MenuStyle.title(pagedTitle("&8" + targetName + "'s Notes", slice)));

        if (slice.items().isEmpty()) {
            menu.set(22, Lore.item(Material.WRITTEN_BOOK, "&fNo notes yet", Lore.builder(Accent.WHITE)
                    .description("Click 'Add Note' below to leave", "context for the next staff member.")
                    .build()));
        }
        for (int i = 0; i < slice.items().size(); i++) {
            PlayerNote note = slice.items().get(i);
            List<String> lore = new ArrayList<>();
            lore.add("&f" + note.note());
            lore.addAll(Lore.builder(Accent.WHITE)
                    .divider()
                    .stat("By", note.staffName())
                    .stat("Date", DATE_FORMAT.format(Instant.ofEpochMilli(note.createdAt())))
                    .build());
            menu.set(i, Lore.item(Material.WRITTEN_BOOK, "&fNote", lore));
        }

        addPageButtons(menu, staff, slice, p -> buildNotesGui(staff, targetUuid, targetName, reason, notes, p));
        menu.set(48, Lore.item(Material.WRITABLE_BOOK, "&aAdd Note", Lore.builder(Accent.GREEN)
                .description("Click to leave a note about " + targetName + ".")
                .clickPrompt("Click to Add")
                .build()), click -> {
            staff.closeInventory();
            promptAddNote(staff, targetUuid, targetName, reason);
        });
        menu.set(49, backButton(), click -> {
            staff.closeInventory();
            open(staff, targetUuid, targetName, reason);
        });

        menu.open(staff);
    }

    private void promptAddNote(Player staff, UUID targetUuid, String targetName, String reason) {
        Runnable reopen = () -> openNotes(staff, targetUuid, targetName, reason, 0);
        promptChat(staff, "&7Please write the note. (type 'cancel' to abort)", "&7Cancelled.", reopen,
                input -> noteService.create(targetUuid, targetName, staff.getUniqueId(), staff.getName(), input).thenAccept(ignored -> Tasks.onEntity(staff, () -> {
                    staff.sendMessage(legacy("&aNote added."));
                    reopen.run();
                })));
    }

    static boolean isRecentWarnConflict(Warden.ApplyResult result) {
        return result.isConflict() && PunishmentService.TYPE_WARN.equals(result.conflict().type());
    }

    private void report(Player staff, String targetName, Warden.ApplyResult result) {
        report((org.bukkit.command.CommandSender) staff, targetName, result);
    }

    static void report(org.bukkit.command.CommandSender staff, String targetName, Warden.ApplyResult result) {
        if (result.lockedOut()) {
            staff.sendMessage(legacy("&cYou are locked out of issuing punishments. An admin must run /punish clearlockout to restore access."));
            return;
        }
        if (result.isProtected()) {
            staff.sendMessage(legacy("&c" + result.protectedMessage()));
            return;
        }
        if (result.isConflict()) {
            var conflict = result.conflict();
            if (PunishmentService.TYPE_WARN.equals(conflict.type())) {
                staff.sendMessage(legacy("&f" + targetName + "&c was already warned recently - reason: &f" + conflict.reason()
                        + "&c by &f" + conflict.staffName() + "&c (" + conflict.displayId() + "). &cPunish them instead of warning again."));
                return;
            }
            staff.sendMessage(legacy("&f" + targetName + "&c already has an active &f" + conflict.type().toLowerCase()
                    + "&c. Reason: &f" + conflict.reason() + "&c by &f" + conflict.staffName() + "&c (" + conflict.displayId() + ")"));
            return;
        }
        var created = result.created();
        if (created == null) {
            staff.sendMessage(legacy("&cSomething went wrong recording that punishment. Check the console."));
            return;
        }
        staff.sendMessage(legacy("&f" + created.type() + "&a recorded for &f" + targetName + "&a. (&f" + created.displayId() + "&a)"));
    }

    private static String ordinalOffense(int offenseNumber) {
        String suffix;
        int lastTwoDigits = offenseNumber % 100;
        if (lastTwoDigits >= 11 && lastTwoDigits <= 13) {
            suffix = "th";
        } else {
            suffix = switch (offenseNumber % 10) {
                case 1 -> "st";
                case 2 -> "nd";
                case 3 -> "rd";
                default -> "th";
            };
        }
        return offenseNumber + suffix + " Offense";
    }

    private static String typeLabel(PunishmentSentence sentence) {
        return sentence.ban() ? "Ban" : "Mute";
    }

    private static String formatDuration(PunishmentSentence sentence) {
        if (sentence.category() == Category.WARNING) {
            return "-";
        }
        if (sentence.isPermanent()) {
            return "Permanent";
        }
        return formatHoursOrDays(sentence.durationMillis());
    }

    private static String formatHoursOrDays(long millis) {
        long hours = millis / 3_600_000L;
        if (hours >= 730) {
            return String.format(Locale.ROOT, "%.1f months", hours / 730.0);
        }
        if (hours >= 24) {
            return String.format(Locale.ROOT, "%.1f days", hours / 24.0);
        }
        if (millis >= 3_600_000L) {
            return String.format(Locale.ROOT, "%.1f hours", millis / 3_600_000.0);
        }
        long minutes = Math.max(1, (millis + 59_999) / 60_000);
        return minutes + (minutes == 1 ? " minute" : " minutes");
    }

    private static String expiryLabel(PunishmentData record) {
        if (record.expiresAt() == null) {
            return "Permanent";
        }
        long remainingMs = record.expiresAt() - System.currentTimeMillis();
        String remaining = remainingMs > 0 ? formatHoursOrDays(remainingMs) + " left" : "expired";
        return "expires " + DATE_FORMAT.format(Instant.ofEpochMilli(record.expiresAt())) + " &7(&f" + remaining + "&7)";
    }

    private static List<String> activeStatusLore(Warden.ActiveStatus status) {
        if (!status.hasAny()) {
            return List.of("&aNo active punishments.");
        }
        List<String> lines = new ArrayList<>();
        if (status.ban() != null) {
            lines.add("&c&lBANNED &7- " + expiryLabel(status.ban()) + " &b(" + status.ban().displayId() + ")");
        }
        if (status.mute() != null) {
            lines.add("&6&lMUTED &7- " + expiryLabel(status.mute()) + " &b(" + status.mute().displayId() + ")");
        }
        if (status.shadowMute() != null) {
            lines.add("&5&lSHADOW MUTED &b(" + status.shadowMute().displayId() + ")");
        }
        return lines;
    }

    private static ItemStack targetHead(UUID targetUuid, String targetName, String reason, Warden.ActiveStatus status) {
        List<String> lore = new ArrayList<>(Lore.builder(Accent.CYAN).stat("Reason", reason).build());
        lore.add("");
        lore.addAll(activeStatusLore(status));
        lore.addAll(Lore.builder(Accent.CYAN).clickPrompt("Click to write a different reason").build());

        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        meta.displayName(legacy("&b" + targetName).decoration(TextDecoration.ITALIC, false));
        meta.setOwningPlayer(Bukkit.getOfflinePlayer(targetUuid));
        meta.lore(lore.stream().map(PunishGui::legacy).map(c -> c.decoration(TextDecoration.ITALIC, false)).toList());
        item.setItemMeta(meta);
        return item;
    }

    private static Component legacy(String s) {
        return LEGACY.deserialize(s);
    }

    private static ItemStack noPermissionItem(Material material, String label) {
        ItemStack item = Lore.item(material, "&7" + label, Lore.builder(Accent.RED)
                .description("You don't have permission", "to use this.")
                .build());
        return MenuStyle.clean(item, true);
    }

    private static ItemStack backButton() {
        return Lore.item(Material.ARROW, "&c&l← Back", Lore.builder(Accent.RED).clickPrompt("Click to Go Back").build());
    }

    private static ItemStack closeButton() {
        return Lore.item(Material.BARRIER, "&cClose", Lore.builder(Accent.RED).clickPrompt("Click to Close").build());
    }

    private record PageSlice<T>(List<T> items, int page, int totalPages) {
    }

    private static <T> PageSlice<T> paginate(List<T> all, int page) {
        return paginate(all, page, HISTORY_PAGE_SIZE);
    }

    private static <T> PageSlice<T> paginate(List<T> all, int page, int pageSize) {
        int totalPages = Math.max(1, (int) Math.ceil(all.size() / (double) pageSize));
        int clampedPage = Math.max(0, Math.min(page, totalPages - 1));
        int start = clampedPage * pageSize;
        int end = Math.min(start + pageSize, all.size());
        return new PageSlice<>(all.subList(start, end), clampedPage, totalPages);
    }

    private static String pagedTitle(String base, PageSlice<?> slice) {
        return slice.totalPages() > 1 ? base + " &7(&c" + (slice.page() + 1) + "&7/&c" + slice.totalPages() + "&7)" : base;
    }

    private void addPageButtons(Menu menu, Player staff, PageSlice<?> slice, IntConsumer onPage) {
        if (slice.page() > 0) {
            menu.set(PREVIOUS_PAGE_SLOT, Lore.item(Material.ARROW, "&ePrevious page", List.of()), click -> {
                staff.closeInventory();
                onPage.accept(slice.page() - 1);
            });
        }
        if (slice.page() < slice.totalPages() - 1) {
            menu.set(NEXT_PAGE_SLOT, Lore.item(Material.ARROW, "&eNext page", List.of()), click -> {
                staff.closeInventory();
                onPage.accept(slice.page() + 1);
            });
        }
    }

    private void promptChat(Player staff, String prompt, String cancelMessage, Runnable onCancel, Consumer<String> onInput) {
        staff.sendMessage(legacy(prompt));
        chatInput.expect(staff, 60_000, input -> {
            String trimmed = input.trim();
            if (trimmed.isBlank() || trimmed.equalsIgnoreCase("cancel")) {
                staff.sendMessage(legacy(cancelMessage));
                onCancel.run();
                return;
            }
            onInput.accept(trimmed);
        });
    }
}
