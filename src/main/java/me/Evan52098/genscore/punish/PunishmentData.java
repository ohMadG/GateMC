package me.Evan52098.genscore.punish;

import java.util.UUID;

public final class PunishmentData {
    private final long id;
    private final String shortId;
    private final UUID targetUuid;
    private final String targetName;
    private final String type;
    private final String reason;
    private final long createdAt;
    private final Long expiresAt;
    private final UUID staffUuid;
    private final String staffName;
    private final boolean active;
    private final String ipAddress;
    private final String category;
    private final int severity;
    private final String removeAdmin;
    private final String removeReason;
    private final Long removedAt;

    public PunishmentData(long id, String shortId, UUID targetUuid, String targetName, String type, String reason, long createdAt, Long expiresAt,
                           UUID staffUuid, String staffName, boolean active, String ipAddress, String category, int severity,
                           String removeAdmin, String removeReason, Long removedAt) {
        this.id = id;
        this.shortId = shortId;
        this.targetUuid = targetUuid;
        this.targetName = targetName;
        this.type = type;
        this.reason = reason;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.staffUuid = staffUuid;
        this.staffName = staffName;
        this.active = active;
        this.ipAddress = ipAddress;
        this.category = category;
        this.severity = severity;
        this.removeAdmin = removeAdmin;
        this.removeReason = removeReason;
        this.removedAt = removedAt;
    }

    public long id() {
        return id;
    }

    public String shortId() {
        return shortId;
    }

    public UUID targetUuid() {
        return targetUuid;
    }

    public String targetName() {
        return targetName;
    }

    public String type() {
        return type;
    }

    public String reason() {
        return reason;
    }

    public long createdAt() {
        return createdAt;
    }

    public Long expiresAt() {
        return expiresAt;
    }

    public UUID staffUuid() {
        return staffUuid;
    }

    public String staffName() {
        return staffName;
    }

    public boolean active() {
        return active;
    }

    public String ipAddress() {
        return ipAddress;
    }

    public String category() {
        return category;
    }

    public int severity() {
        return severity;
    }

    public String removeAdmin() {
        return removeAdmin;
    }

    public String removeReason() {
        return removeReason;
    }

    public Long removedAt() {
        return removedAt;
    }

    public boolean isCurrentlyInEffect() {
        return active && (expiresAt == null || expiresAt > System.currentTimeMillis());
    }

    public boolean isInstantType() {
        return PunishmentService.TYPE_KICK.equals(type) || PunishmentService.TYPE_WARN.equals(type);
    }

    public String displayId() {
        return "#" + shortId;
    }
}
