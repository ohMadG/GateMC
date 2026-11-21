package me.Evan52098.genscore.punish;

import java.util.UUID;

public record PlayerNote(long id, UUID targetUuid, String targetName, String note, UUID staffUuid, String staffName, long createdAt) {
    public String displayId() {
        return "#" + id;
    }
}
