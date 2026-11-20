package me.Evan52098.genscore.trade;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import me.Evan52098.genscore.storage.Database;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * One trade between two players. The two traders are usually on two different threads (Folia), so nothing here is an
 * inventory both of them edit: each side's offer is an escrow list held here (and in {@code pvp_trade_escrow}), and
 * each player sees their own view rendered from a {@link #snapshot()}.
 *
 * <p>Every field is guarded by {@code this}. Database work for this trade runs in order through {@link #queue}, so an
 * escrow insert can never land after the completion or cancel that should have included it.
 */
final class TradeSession {

    enum State { OPEN, COMPLETING, DONE, CANCELLED }

    /** One stack in escrow. {@code id} is the escrow row's key. The item is a private copy no player can touch. */
    record Offer(String id, ItemStack item) {
    }

    /** What a view needs to draw, copied under the lock. */
    record Snapshot(String nameA, String nameB, List<ItemStack> offerA, List<ItemStack> offerB, boolean readyA, boolean readyB,
                    int countdown, State state) {
    }

    static final int MAX_OFFERS = 12;

    final String id;
    final UUID a;
    final String nameA;
    final UUID b;
    final String nameB;

    private final List<Offer> offerA = new ArrayList<>();
    private final List<Offer> offerB = new ArrayList<>();
    private State state = State.OPEN;
    private boolean readyA;
    private boolean readyB;
    private int version;
    private int countdown = -1;
    private @Nullable ScheduledTask countdownTask;
    /** Set when a cancel was asked for while completion was in flight; acted on if completion fails. */
    private @Nullable String cancelAfter;
    private CompletableFuture<?> chain = CompletableFuture.completedFuture(null);
    /** Escrow ids whose items were dropped at their owner's death (never returned). */
    private final java.util.Set<String> dropped = new java.util.HashSet<>();

    TradeSession(String id, UUID a, String nameA, UUID b, String nameB) {
        this.id = id;
        this.a = a;
        this.nameA = nameA;
        this.b = b;
        this.nameB = nameB;
    }

    boolean isA(UUID player) {
        return a.equals(player);
    }

    UUID other(UUID player) {
        return isA(player) ? b : a;
    }

    String nameOf(UUID player) {
        return isA(player) ? nameA : nameB;
    }

    synchronized State state() {
        return state;
    }

    synchronized void state(State s) {
        state = s;
    }

    synchronized List<Offer> offers(UUID owner) {
        return List.copyOf(isA(owner) ? offerA : offerB);
    }

    /** Adds to the owner's escrow if the trade is open and has room. Any change un-readies both sides. */
    synchronized boolean addOffer(UUID owner, Offer offer) {
        List<Offer> list = isA(owner) ? offerA : offerB;
        if (state != State.OPEN || list.size() >= MAX_OFFERS) {
            return false;
        }
        list.add(offer);
        changed();
        return true;
    }

    /** Removes an offer by index if the trade is open; returns it, or null. */
    synchronized @Nullable Offer removeOffer(UUID owner, int index) {
        List<Offer> list = isA(owner) ? offerA : offerB;
        if (state != State.OPEN || index < 0 || index >= list.size()) {
            return null;
        }
        Offer removed = list.remove(index);
        changed();
        return removed;
    }

    /** Removes an offer by id (used when its escrow insert failed); true if it was still there. */
    synchronized boolean dropOffer(UUID owner, String offerId) {
        List<Offer> list = isA(owner) ? offerA : offerB;
        boolean removed = list.removeIf(o -> o.id().equals(offerId));
        if (removed) {
            changed();
        }
        return removed;
    }

    synchronized boolean hasRoom(UUID owner) {
        return state == State.OPEN && (isA(owner) ? offerA : offerB).size() < MAX_OFFERS;
    }

    /** Toggles the player's ready flag. Returns true when both are now ready. */
    synchronized boolean toggleReady(UUID player) {
        if (state != State.OPEN) {
            return false;
        }
        if (isA(player)) {
            readyA = !readyA;
        } else {
            readyB = !readyB;
        }
        version++;
        return readyA && readyB;
    }

    /** Checklist 3/4: any change by either side resets both accept states. */
    private void changed() {
        version++;
        readyA = false;
        readyB = false;
    }

    synchronized boolean bothReady() {
        return state == State.OPEN && readyA && readyB;
    }

    synchronized int version() {
        return version;
    }

    synchronized boolean unready() {
        boolean was = readyA || readyB;
        readyA = false;
        readyB = false;
        version++;
        return was;
    }

    synchronized void countdownTask(@Nullable ScheduledTask task, int seconds) {
        countdownTask = task;
        countdown = seconds;
    }

    synchronized boolean countingDown() {
        return countdownTask != null;
    }

    synchronized int countdown() {
        return countdown;
    }

    synchronized void countdown(int value) {
        countdown = value;
    }

    synchronized void stopCountdown() {
        if (countdownTask != null) {
            countdownTask.cancel();
            countdownTask = null;
        }
        countdown = -1;
    }

    /**
     * Moves OPEN to COMPLETING if both are still ready at the given version. Returns the escrow ids per owner to check
     * against the database, or null if the trade changed.
     */
    synchronized @Nullable Map<String, UUID> beginCompletion(int expectedVersion) {
        if (state != State.OPEN || !readyA || !readyB || version != expectedVersion) {
            return null;
        }
        state = State.COMPLETING;
        java.util.HashMap<String, UUID> ids = new java.util.HashMap<>();
        for (Offer o : offerA) {
            ids.put(o.id(), a);
        }
        for (Offer o : offerB) {
            ids.put(o.id(), b);
        }
        return ids;
    }

    /** Completion failed (database changed under us): back to OPEN, nobody ready. */
    synchronized @Nullable String revertCompletion() {
        if (state == State.COMPLETING) {
            state = State.OPEN;
        }
        readyA = false;
        readyB = false;
        version++;
        String pending = cancelAfter;
        cancelAfter = null;
        return pending;
    }

    /**
     * Ends the trade if it is OPEN (returns true). If completion is in flight, remembers the cancel for later and
     * returns false; if it already ended, returns false.
     */
    synchronized boolean beginCancel(String reason) {
        if (state == State.OPEN) {
            state = State.CANCELLED;
            if (countdownTask != null) {
                countdownTask.cancel();
                countdownTask = null;
            }
            countdown = -1;
            return true;
        }
        if (state == State.COMPLETING && cancelAfter == null) {
            cancelAfter = reason;
        }
        return false;
    }

    /** Takes the owner's escrow out of the session (death drops). Call only after {@link #beginCancel} returned true. */
    synchronized List<Offer> takeAll(UUID owner) {
        List<Offer> list = isA(owner) ? offerA : offerB;
        List<Offer> copy = List.copyOf(list);
        list.clear();
        return copy;
    }

    synchronized void markDropped(String offerId) {
        dropped.add(offerId);
    }

    synchronized boolean wasDropped(String offerId) {
        return dropped.contains(offerId);
    }

    synchronized Snapshot snapshot() {
        List<ItemStack> itemsA = new ArrayList<>(offerA.size());
        for (Offer o : offerA) {
            itemsA.add(o.item().clone());
        }
        List<ItemStack> itemsB = new ArrayList<>(offerB.size());
        for (Offer o : offerB) {
            itemsB.add(o.item().clone());
        }
        return new Snapshot(nameA, nameB, itemsA, itemsB, readyA, readyB, countdownTask != null ? countdown : -1, state);
    }

    /** Runs database work for this trade after every earlier piece of work for it, whether that succeeded or not. */
    synchronized <T> CompletableFuture<T> queue(Database db, Database.SqlFunction<T> work) {
        CompletableFuture<T> next = chain.handle((v, e) -> null).thenCompose(v -> db.async(work));
        chain = next;
        return next;
    }

    synchronized CompletableFuture<?> tail() {
        return chain;
    }
}
