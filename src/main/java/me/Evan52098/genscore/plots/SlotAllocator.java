package me.Evan52098.genscore.plots;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Hands out and takes back plot slots. Thread-safe: joins, quits and clears happen on many threads at once.
 *
 * <p>A slot goes FREE -> USED (allocate) -> CLEARING (its blocks are being removed) -> FREE. A slot is never handed
 * out again until its clear has finished, so a new plot can never be built on top of an old one. A slot whose clear
 * failed stays CLEARING (quarantined) until the next start-up, which clears it again.
 */
final class SlotAllocator {

    enum State { FREE, USED, CLEARING }

    record Counts(int free, int used, int clearing) {
    }

    private final State[] states;
    private final UUID[] owners;

    SlotAllocator(int maxSlots) {
        states = new State[maxSlots];
        owners = new UUID[maxSlots];
        java.util.Arrays.fill(states, State.FREE);
    }

    int capacity() {
        return states.length;
    }

    /** The lowest free slot, now owned by {@code owner}, or -1 if every slot is taken. */
    synchronized int allocate(UUID owner) {
        for (int i = 0; i < states.length; i++) {
            if (states[i] == State.FREE) {
                states[i] = State.USED;
                owners[i] = owner;
                return i;
            }
        }
        return -1;
    }

    /** Takes back a slot that was allocated but never built on (nothing to clear). */
    synchronized void cancel(int slot, UUID owner) {
        if (inRange(slot) && states[slot] == State.USED && owner.equals(owners[slot])) {
            states[slot] = State.FREE;
            owners[slot] = null;
        }
    }

    /** The slot's blocks are about to be cleared. Also used at start-up for slots left over from a crash. */
    synchronized void markClearing(int slot) {
        if (inRange(slot)) {
            states[slot] = State.CLEARING;
        }
    }

    /** The slot's blocks are gone; it can be handed out again. */
    synchronized void markFree(int slot) {
        if (inRange(slot) && states[slot] == State.CLEARING) {
            states[slot] = State.FREE;
            owners[slot] = null;
        }
    }

    synchronized State state(int slot) {
        return inRange(slot) ? states[slot] : State.FREE;
    }

    synchronized @Nullable UUID owner(int slot) {
        return inRange(slot) ? owners[slot] : null;
    }

    synchronized Counts counts() {
        int free = 0;
        int used = 0;
        int clearing = 0;
        for (State s : states) {
            switch (s) {
                case FREE -> free++;
                case USED -> used++;
                case CLEARING -> clearing++;
            }
        }
        return new Counts(free, used, clearing);
    }

    /** How many slots are USED or CLEARING by any of these owners (for the stress test's orphan check). */
    synchronized int heldBy(java.util.Set<UUID> ownerSet) {
        int n = 0;
        for (int i = 0; i < states.length; i++) {
            if (states[i] != State.FREE && owners[i] != null && ownerSet.contains(owners[i])) {
                n++;
            }
        }
        return n;
    }

    private boolean inRange(int slot) {
        return slot >= 0 && slot < states.length;
    }
}
