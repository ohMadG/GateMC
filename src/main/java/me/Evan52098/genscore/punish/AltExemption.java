package me.Evan52098.genscore.punish;

import java.util.UUID;

public record AltExemption(UUID targetUuid, String targetName, UUID addedByUuid, String addedByName, String reason, long createdAt) {
}
