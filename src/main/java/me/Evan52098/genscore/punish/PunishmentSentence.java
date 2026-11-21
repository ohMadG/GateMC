package me.Evan52098.genscore.punish;

public record PunishmentSentence(Category category, int severity, boolean ban, long durationMillis, int pastOffenses) {

    public static final long PERMANENT = -1L;

    public boolean isPermanent() {
        return durationMillis == PERMANENT;
    }
}
