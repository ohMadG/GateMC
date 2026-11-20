package me.Evan52098.genscore.plots;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * One loaded plot: an owner on a slot. Built from the template on join and removed after the owner leaves.
 * Its lasting data (belt length, lock level, ...) is the owner's {@link PlotData}, not this object.
 *
 * <p>Thread-safe: state changes come from the owner's thread (join, quit), region threads (build finished) and
 * async timers (delayed removal).
 *
 * <p>Removal uses a token: every removal request takes the current token, and a rejoin ({@link #keep()}) moves the
 * token on. A removal whose token is stale is ignored, so a quit timer that fires just as the owner rejoins can never
 * remove the plot from under them.
 */
final class Plot {

    enum State {
        /** The template is being placed. */
        BUILDING,
        /** Built and in use. */
        READY,
        /** An admin reset is clearing and rebuilding it. */
        REBUILDING,
        /** Being cleared; the slot is freed when that finishes. */
        REMOVING
    }

    /** Returned instead of a token when there is nothing to remove. */
    static final long NO_REMOVAL = -1;

    private final UUID owner;
    private final int slot;
    private final long createdAt = System.currentTimeMillis();
    private final CompletableFuture<Plot> built = new CompletableFuture<>();
    private State state = State.BUILDING;
    private long token;
    private boolean removeWhenIdle;
    private @Nullable ScheduledTask pendingRemoval;

    Plot(UUID owner, int slot) {
        this.owner = owner;
        this.slot = slot;
    }

    UUID owner() {
        return owner;
    }

    int slot() {
        return slot;
    }

    long createdAt() {
        return createdAt;
    }

    /** Completes once the first build is done (or fails with the build error). */
    CompletableFuture<Plot> built() {
        return built;
    }

    synchronized State state() {
        return state;
    }

    synchronized boolean isReady() {
        return state == State.READY;
    }

    /** The token a removal request must carry. */
    synchronized long removalToken() {
        return token;
    }

    /**
     * Starts removal if the token is still current. While the plot is building or rebuilding the request is kept and
     * returned by {@link #finishBuild()} / {@link #finishRebuild()} instead. Returns true if the caller must clear it now.
     */
    synchronized boolean beginRemoval(long requestToken) {
        if (requestToken != token || state == State.REMOVING) {
            return false;
        }
        pendingRemoval = null;
        if (state == State.BUILDING || state == State.REBUILDING) {
            removeWhenIdle = true;
            return false;
        }
        state = State.REMOVING;
        return true;
    }

    /** First build finished. Returns a removal token if the owner left meanwhile, else {@link #NO_REMOVAL}. */
    synchronized long finishBuild() {
        if (state == State.BUILDING) {
            state = State.READY;
        }
        built.complete(this);
        return removeWhenIdle ? token : NO_REMOVAL;
    }

    void failBuild(Throwable error) {
        built.completeExceptionally(error);
    }

    /** Starts an admin rebuild. Only a ready plot can be rebuilt. */
    synchronized boolean beginRebuild() {
        if (state != State.READY) {
            return false;
        }
        state = State.REBUILDING;
        return true;
    }

    /** Rebuild finished. Returns a removal token if the owner left meanwhile, else {@link #NO_REMOVAL}. */
    synchronized long finishRebuild() {
        if (state == State.REBUILDING) {
            state = State.READY;
        }
        return removeWhenIdle ? token : NO_REMOVAL;
    }

    /** Remembers a delayed removal so a rejoin can cancel it. */
    synchronized void setPendingRemoval(@Nullable ScheduledTask task) {
        if (pendingRemoval != null) {
            pendingRemoval.cancel();
        }
        pendingRemoval = task;
    }

    /**
     * The owner is back: cancel any delayed or requested removal and invalidate every token handed out so far.
     * Returns false if removal already started (the caller then builds a new plot).
     */
    synchronized boolean keep() {
        token++;
        removeWhenIdle = false;
        if (pendingRemoval != null) {
            pendingRemoval.cancel();
            pendingRemoval = null;
        }
        return state != State.REMOVING;
    }
}
