package me.Evan52098.genscore.powers;

import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class PowerData {

    private final Set<String> owned = new LinkedHashSet<>();
    private @Nullable String active;
    private boolean freeUsed;
    private String seenUpdate;

    PowerData(Iterable<String> owned, @Nullable String active, boolean freeUsed, String seenUpdate) {
        for (String id : owned) {
            if (id != null && !id.isBlank()) {
                this.owned.add(id);
            }
        }
        this.active = active != null && this.owned.contains(active) ? active : null;
        this.freeUsed = freeUsed;
        this.seenUpdate = seenUpdate == null ? "" : seenUpdate;
    }

    synchronized boolean owns(String id) {
        return owned.contains(id);
    }

    synchronized boolean addOwned(String id) {
        return owned.add(id);
    }

    synchronized boolean removeOwned(String id) {
        if (id.equals(active)) {
            active = null;
        }
        return owned.remove(id);
    }

    synchronized List<String> owned() {
        return List.copyOf(owned);
    }

    synchronized @Nullable String active() {
        return active;
    }

    synchronized boolean setActive(@Nullable String id) {
        if (id != null && !owned.contains(id)) {
            return false;
        }
        active = id;
        return true;
    }

    synchronized boolean freeUsed() {
        return freeUsed;
    }

    synchronized void markFreeUsed() {
        freeUsed = true;
    }

    synchronized String seenUpdate() {
        return seenUpdate;
    }

    synchronized void seenUpdate(String id) {
        seenUpdate = id == null ? "" : id;
    }

    record Snapshot(String owned, @Nullable String active, boolean freeUsed, String seenUpdate) {
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(String.join(",", owned), active, freeUsed, seenUpdate);
    }
}
