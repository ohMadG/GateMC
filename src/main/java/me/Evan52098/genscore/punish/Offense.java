package me.Evan52098.genscore.punish;

import java.util.List;

record Offense(Category category, int code, String name, List<String> description, List<String> notes, List<Rung> ladder, boolean unappealable) {

    record Rung(long hours, boolean ban) {
        boolean isPermanent() {
            return hours <= 0;
        }
    }

    static Rung mute(long hours) {
        return new Rung(hours, false);
    }

    static Rung ban(long hours) {
        return new Rung(hours, true);
    }

    Rung rungFor(int pastOffenses) {
        int index = Math.min(Math.max(pastOffenses, 0), ladder.size() - 1);
        return ladder.get(index);
    }
}
