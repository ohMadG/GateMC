package me.Evan52098.genscore.punish;

import me.Evan52098.genscore.scheduler.Tasks;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class Warden {
    public static final UUID SYSTEM_STAFF_UUID = new UUID(0L, 0L);
    public static final String STAFF_NAME = "Server";

    private static final Set<String> MUTE_TYPES = Set.of(PunishmentService.TYPE_MUTE, PunishmentService.TYPE_SHADOWMUTE);
    private static final String STAFF_ALERT_PERMISSION = "genscore.punish.gui";

    private final PunishmentService punishmentService;
    private final StaffLockoutService lockoutService;
    private final PunishWebhook webhook;
    private final String appealUrl;
    private final boolean ipTrackingEnabled;
    private final boolean altBanEvasionEnabled;
    private final long warnCooldownMillis;

    public Warden(PunishmentService punishmentService, StaffLockoutService lockoutService, PunishWebhook webhook,
                  String appealUrl,
                  boolean ipTrackingEnabled, boolean altBanEvasionEnabled, long warnCooldownMillis) {
        this.punishmentService = punishmentService;
        this.lockoutService = lockoutService;
        this.webhook = webhook;
        this.appealUrl = appealUrl == null ? "" : appealUrl;
        this.ipTrackingEnabled = ipTrackingEnabled;
        this.altBanEvasionEnabled = altBanEvasionEnabled;
        this.warnCooldownMillis = warnCooldownMillis;
    }

    public PunishmentService punishments() {
        return punishmentService;
    }

    public StaffLockoutService lockouts() {
        return lockoutService;
    }

    public record ActiveStatus(@Nullable PunishmentData ban, @Nullable PunishmentData mute, @Nullable PunishmentData shadowMute) {
        public boolean hasAny() {
            return ban != null || mute != null || shadowMute != null;
        }
    }

    public CompletableFuture<ActiveStatus> activeStatus(UUID targetUuid) {
        return punishmentService.getActive(targetUuid, PunishmentService.TYPE_BAN)
                .thenCombine(punishmentService.getActiveMuteState(targetUuid),
                        (ban, muteState) -> new ActiveStatus(ban, muteState.mute(), muteState.shadowMute()));
    }

    public CompletableFuture<PunishmentSentence> permanentSentence(Category category) {
        boolean ban = category != Category.CHAT;
        return CompletableFuture.completedFuture(new PunishmentSentence(category, 0, ban, PunishmentSentence.PERMANENT, 0));
    }

    public CompletableFuture<ApplyResult> applyFilterStrike(UUID targetUuid, String targetName, @Nullable UUID staffUuid, String staffName, String reason) {
        Offense offense = OffenseCatalog.ALL.stream()
                .filter(o -> o.category() == Category.CHAT && o.code() == 6)
                .findFirst()
                .orElseThrow();
        UUID by = staffUuid == null ? SYSTEM_STAFF_UUID : staffUuid;
        String refusal = refuseTarget(targetUuid, by, targetName);
        if (refusal != null) {
            return refused(refusal);
        }
        return sentenceFor(offense, targetUuid)
                .thenCompose(sentence -> applyAfterLockoutCheck(sentence, targetUuid, targetName, by, staffName, reason));
    }

    public CompletableFuture<PunishmentSentence> sentenceFor(Offense offense, UUID targetUuid) {
        return punishmentService.countOffenses(targetUuid, offense.category(), offense.code()).thenApply(pastOffenses -> {
            Offense.Rung rung = offense.rungFor(pastOffenses);
            long durationMillis = rung.isPermanent() ? PunishmentSentence.PERMANENT : rung.hours() * 3_600_000L;
            return new PunishmentSentence(offense.category(), offense.code(), rung.ban(), durationMillis, pastOffenses);
        });
    }

    public String typeFor(PunishmentSentence sentence) {
        return sentence.category() == Category.WARNING
                ? PunishmentService.TYPE_WARN
                : sentence.ban() ? PunishmentService.TYPE_BAN : PunishmentService.TYPE_MUTE;
    }

    public record ApplyResult(@Nullable PunishmentData created, @Nullable PunishmentData conflict, boolean lockedOut,
                               @Nullable String protectedMessage) {
        public ApplyResult(@Nullable PunishmentData created, @Nullable PunishmentData conflict) {
            this(created, conflict, false, null);
        }

        public ApplyResult(@Nullable PunishmentData created, @Nullable PunishmentData conflict, boolean lockedOut) {
            this(created, conflict, lockedOut, null);
        }

        public boolean isConflict() {
            return conflict != null;
        }

        public boolean isProtected() {
            return protectedMessage != null;
        }
    }

    public CompletableFuture<ApplyResult> apply(PunishmentSentence sentence, UUID targetUuid, String targetName, Player staff, String reason) {
        String permission = actionPermissionFor(sentence);
        if (!staff.hasPermission(permission)) {
            return refused("You don't have permission to issue that type of punishment.");
        }
        return apply(sentence, targetUuid, targetName, staff.getUniqueId(), staff.getName(), reason);
    }

    private static String actionPermissionFor(PunishmentSentence sentence) {
        if (sentence.category() == Category.WARNING) {
            return PunishCommand.PERMISSION_ACTION_WARN;
        }
        return sentence.ban() ? PunishCommand.PERMISSION_ACTION_BAN : PunishCommand.PERMISSION_ACTION_MUTE;
    }

    public CompletableFuture<ApplyResult> applyWarn(UUID targetUuid, String targetName, Player staff, String reason) {
        String refusal = refuseTarget(targetUuid, staff.getUniqueId(), targetName);
        if (refusal != null) {
            return refused(refusal);
        }
        return punishmentService.findMostRecentWarn(targetUuid).thenCompose(recent -> {
            if (recent != null && warnCooldownMillis > 0 && System.currentTimeMillis() - recent.createdAt() < warnCooldownMillis) {
                return CompletableFuture.completedFuture(new ApplyResult(null, recent));
            }
            PunishmentSentence sentence = new PunishmentSentence(Category.WARNING, 1, false, 0, 0);
            return apply(sentence, targetUuid, targetName, staff, reason);
        });
    }

    public CompletableFuture<ApplyResult> apply(PunishmentSentence sentence, UUID targetUuid, String targetName, UUID staffUuid, String staffName, String reason) {
        String refusal = refuseTarget(targetUuid, staffUuid, targetName);
        if (refusal != null) {
            return refused(refusal);
        }

        if (!staffUuid.equals(SYSTEM_STAFF_UUID)) {
            return lockoutService.checkAndRecord(staffName, staffUuid).thenCompose(allowed -> {
                if (!allowed) {
                    return CompletableFuture.completedFuture(new ApplyResult(null, null, true));
                }
                return applyAfterLockoutCheck(sentence, targetUuid, targetName, staffUuid, staffName, reason);
            });
        }
        return applyAfterLockoutCheck(sentence, targetUuid, targetName, staffUuid, staffName, reason);
    }

    private CompletableFuture<ApplyResult> applyAfterLockoutCheck(PunishmentSentence sentence, UUID targetUuid, String targetName, UUID staffUuid, String staffName, String reason) {
        String type = typeFor(sentence);
        if (type.equals(PunishmentService.TYPE_BAN)) {
            return punishmentService.getActive(targetUuid, type).thenCompose(existing -> existing != null
                    ? CompletableFuture.completedFuture(new ApplyResult(null, existing))
                    : createAndApply(type, sentence, targetUuid, targetName, staffUuid, staffName, reason).thenApply(created -> new ApplyResult(created, null)));
        }
        if (MUTE_TYPES.contains(type)) {
            return checkExistingMute(targetUuid).thenCompose(existing -> existing != null
                    ? CompletableFuture.completedFuture(new ApplyResult(null, existing))
                    : createAndApply(type, sentence, targetUuid, targetName, staffUuid, staffName, reason).thenApply(created -> new ApplyResult(created, null)));
        }
        return createAndApply(type, sentence, targetUuid, targetName, staffUuid, staffName, reason).thenApply(created -> new ApplyResult(created, null));
    }

    private CompletableFuture<PunishmentData> checkExistingMute(UUID targetUuid) {
        return punishmentService.getActiveMuteState(targetUuid).thenApply(state -> state.mute() != null ? state.mute() : state.shadowMute());
    }

    public CompletableFuture<ApplyResult> applyShadowMute(UUID targetUuid, String targetName, Player staff, String reason, @Nullable Long durationMs) {
        if (!staff.hasPermission(PunishCommand.PERMISSION_ACTION_MUTE)) {
            return refused("You don't have permission to issue that type of punishment.");
        }
        String refusal = refuseTarget(targetUuid, staff.getUniqueId(), targetName);
        if (refusal != null) {
            return refused(refusal);
        }
        return lockoutService.checkAndRecord(staff.getName(), staff.getUniqueId()).thenCompose(allowed -> {
            if (!allowed) {
                return CompletableFuture.completedFuture(new ApplyResult(null, null, true));
            }
            return checkExistingMute(targetUuid).thenCompose(existing -> {
                if (existing != null) {
                    return CompletableFuture.completedFuture(new ApplyResult(null, existing));
                }
                PunishmentSentence sentence = durationMs == null
                        ? new PunishmentSentence(Category.CHAT, 0, false, PunishmentSentence.PERMANENT, 0)
                        : new PunishmentSentence(Category.CHAT, 0, false, durationMs, 0);
                return createAndApply(PunishmentService.TYPE_SHADOWMUTE, sentence, targetUuid, targetName, staff.getUniqueId(), staff.getName(), reason)
                        .thenApply(created -> new ApplyResult(created, null));
            });
        });
    }

    private CompletableFuture<PunishmentData> createAndApply(String type, PunishmentSentence sentence, UUID targetUuid, String targetName, UUID staffUuid, String staffName, String reason) {
        Long durationMs = sentence.isPermanent() ? null : sentence.durationMillis();
        return punishmentService.create(type, targetUuid, targetName, staffUuid, staffName, reason, durationMs, null, sentence.category().name(), sentence.severity())
                .thenApply(created -> {
                    if (created != null) {
                        if (webhook != null) {
                            webhook.issued(created);
                        }
                        Tasks.global.run(() -> {
                            broadcastToStaff(created);
                            applyImmediateEffect(created);
                        });
                    }
                    return created;
                });
    }

    public CompletableFuture<ApplyResult> applyKick(UUID targetUuid, String targetName, Player staff, String reason) {
        if (!staff.hasPermission(PunishCommand.PERMISSION_ACTION_KICK)) {
            return refused("You don't have permission to issue that type of punishment.");
        }
        return applyKickAs(targetUuid, targetName, staff.getUniqueId(), staff.getName(), reason);
    }

    public CompletableFuture<ApplyResult> applyKickAs(UUID targetUuid, String targetName, UUID staffUuid, String staffName, String reason) {
        String refusal = refuseTarget(targetUuid, staffUuid, targetName);
        if (refusal != null) {
            return refused(refusal);
        }
        PunishmentSentence sentence = new PunishmentSentence(Category.OTHER, 0, false, 0, 0);
        return createAndApply(PunishmentService.TYPE_KICK, sentence, targetUuid, targetName, staffUuid, staffName, reason)
                .thenApply(created -> new ApplyResult(created, null));
    }

    public CompletableFuture<ApplyResult> applyIpBan(UUID targetUuid, String targetName, Player staff, String reason, String ip) {
        if (!staff.hasPermission(PunishCommand.PERMISSION_ACTION_BAN)) {
            return refused("You don't have permission to issue that type of punishment.");
        }
        String refusal = refuseTarget(targetUuid, staff.getUniqueId(), targetName);
        if (refusal != null) {
            return refused(refusal);
        }
        return lockoutService.checkAndRecord(staff.getName(), staff.getUniqueId()).thenCompose(allowed -> {
            if (!allowed) {
                return CompletableFuture.completedFuture(new ApplyResult(null, null, true));
            }
            return punishmentService.findActiveIpBan(ip).thenCompose(existing -> {
                if (existing != null) {
                    return CompletableFuture.completedFuture(new ApplyResult(null, existing));
                }
                return punishmentService.create(PunishmentService.TYPE_IPBAN, targetUuid, targetName, staff.getUniqueId(), staff.getName(), reason, null, ip, Category.OTHER.name(), 0)
                        .thenApply(created -> {
                            if (created != null) {
                                if (webhook != null) {
                                    webhook.issued(created);
                                }
                                Tasks.global.run(() -> {
                                    broadcastToStaff(created);
                                    applyIpBanImmediateEffect(created);
                                });
                            }
                            return new ApplyResult(created, null);
                        });
            });
        });
    }

    public CompletableFuture<Boolean> revoke(long punishmentId, String staffName, String reason) {
        return punishmentService.revoke(punishmentId, staffName, reason).thenCompose(ok -> {
            if (!ok) {
                return CompletableFuture.completedFuture(false);
            }
            return punishmentService.findById(punishmentId).thenApply(updated -> {
                if (updated != null && webhook != null) {
                    webhook.revoked(updated, staffName);
                }
                return true;
            });
        });
    }

    public static @Nullable String refuseTarget(UUID targetUuid, @Nullable UUID staffUuid, String targetName) {
        if (staffUuid != null && !SYSTEM_STAFF_UUID.equals(staffUuid) && staffUuid.equals(targetUuid)) {
            return "You can't punish yourself.";
        }
        try {
            if (Bukkit.getOfflinePlayer(targetUuid).isOp()) {
                return targetName + " is an operator and cannot be punished.";
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    private static CompletableFuture<ApplyResult> refused(String message) {
        return CompletableFuture.completedFuture(new ApplyResult(null, null, false, message));
    }

    private void broadcastToStaff(PunishmentData punishment) {
        String verb = switch (punishment.type()) {
            case PunishmentService.TYPE_BAN -> "banned";
            case PunishmentService.TYPE_IPBAN -> "IP banned";
            case PunishmentService.TYPE_MUTE -> "muted";
            case PunishmentService.TYPE_SHADOWMUTE -> "shadow muted";
            case PunishmentService.TYPE_WARN -> "warned";
            case PunishmentService.TYPE_KICK -> "kicked";
            default -> punishment.type().toLowerCase();
        };
        String reason = punishment.reason() == null || punishment.reason().isBlank() ? "No reason given" : punishment.reason();
        boolean instant = punishment.isInstantType();
        String durationLabel = instant ? "" : punishment.expiresAt() == null ? " (permanent)" : "";
        Component message = MiniMessage.miniMessage().deserialize(
                "<red>● <yellow>" + escape(punishment.staffName()) + "</yellow> <gray>" + verb + " <yellow>" + escape(punishment.targetName()) + "</yellow>" + durationLabel
                        + ". Reason: <yellow>" + escape(reason) + "</yellow> <gray>(" + punishment.displayId() + ")</gray>");
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission(STAFF_ALERT_PERMISSION)) {
                Tasks.onEntity(staff, () -> staff.sendMessage(message));
            }
        }
    }

    private static String escape(String text) {
        return text == null ? "" : MiniMessage.miniMessage().escapeTags(text);
    }

    private void applyImmediateEffect(PunishmentData punishment) {
        if (PunishmentService.TYPE_SHADOWMUTE.equals(punishment.type())) {
            return;
        }
        if (PunishmentService.TYPE_BAN.equals(punishment.type())) {
            Player online = Bukkit.getPlayer(punishment.targetUuid());
            String ip = online == null || online.getAddress() == null ? null : online.getAddress().getAddress().getHostAddress();
            if (online != null) {
                Tasks.onEntity(online, () -> online.kick(PunishMessages.banMessage(punishment, appealUrl)));
            }
            if (ip != null && ipTrackingEnabled && altBanEvasionEnabled) {
                banConnectedAlts(punishment, ip).thenAccept(this::kickNewlyBannedAlts);
            }
            return;
        }
        Player online = Bukkit.getPlayer(punishment.targetUuid());
        if (online == null) {
            return;
        }
        if (PunishmentService.TYPE_MUTE.equals(punishment.type())) {
            Tasks.onEntity(online, () -> online.sendMessage(PunishMessages.muteMessage(punishment, appealUrl)));
        } else if (PunishmentService.TYPE_WARN.equals(punishment.type())) {
            Tasks.onEntity(online, () -> online.sendMessage(PunishMessages.warnMessage(punishment)));
        } else if (PunishmentService.TYPE_KICK.equals(punishment.type())) {
            Tasks.onEntity(online, () -> online.kick(PunishMessages.kickMessage(punishment)));
        }
    }

    private void applyIpBanImmediateEffect(PunishmentData punishment) {
        String ip = punishment.ipAddress();
        if (ip == null || ip.isBlank()) {
            return;
        }
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getAddress() == null || !ip.equals(online.getAddress().getAddress().getHostAddress())) {
                continue;
            }
            Tasks.onEntity(online, () -> online.kick(PunishMessages.banMessage(punishment, appealUrl)));
        }
    }

    private CompletableFuture<List<PunishmentData>> banConnectedAlts(PunishmentData bannedPunishment, String ip) {
        Long durationMs = bannedPunishment.expiresAt() == null ? null : Math.max(1000L, bannedPunishment.expiresAt() - bannedPunishment.createdAt());
        return punishmentService.findAccountsByIp(ip, bannedPunishment.targetUuid()).thenCompose(altUuids -> {
            List<CompletableFuture<PunishmentData>> tasks = new ArrayList<>();
            for (UUID altUuid : altUuids) {
                Player altOnline = Bukkit.getPlayer(altUuid);
                if (altOnline == null) {
                    continue;
                }
                tasks.add(punishmentService.isAltBanExempt(altUuid).thenCompose(exempt -> {
                    if (exempt) {
                        return CompletableFuture.completedFuture(null);
                    }
                    return punishmentService.getActive(altUuid, PunishmentService.TYPE_BAN).thenCompose(existingBan -> {
                        if (existingBan != null) {
                            return CompletableFuture.completedFuture(null);
                        }
                        return punishmentService.create(PunishmentService.TYPE_BAN, altUuid, altOnline.getName(), SYSTEM_STAFF_UUID, STAFF_NAME,
                                "Ban evasion: alt of banned account " + bannedPunishment.targetName(), durationMs, ip, Category.OTHER.name(), 0);
                    });
                }));
            }
            if (tasks.isEmpty()) {
                return CompletableFuture.completedFuture(List.<PunishmentData>of());
            }
            return CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).thenApply(ignored -> {
                List<PunishmentData> created = new ArrayList<>();
                for (CompletableFuture<PunishmentData> task : tasks) {
                    PunishmentData result = task.join();
                    if (result != null) {
                        created.add(result);
                    }
                }
                return created;
            });
        });
    }

    private void kickNewlyBannedAlts(List<PunishmentData> altBans) {
        if (altBans.isEmpty()) {
            return;
        }
        for (PunishmentData altBan : altBans) {
            if (webhook != null) {
                webhook.issued(altBan);
            }
        }
        Tasks.global.run(() -> {
            for (PunishmentData altBan : altBans) {
                Player altOnline = Bukkit.getPlayer(altBan.targetUuid());
                if (altOnline != null) {
                    Tasks.onEntity(altOnline, () -> altOnline.kick(PunishMessages.banMessage(altBan, appealUrl)));
                }
            }
        });
    }
}
