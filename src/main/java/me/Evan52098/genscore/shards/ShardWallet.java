package me.Evan52098.genscore.shards;

/**
 * One online player's shards. Thread-safe: changed on the player's thread, by other modules on any thread, and read by
 * the saver on a database thread. Whole shards only; per-minute pay keeps its fraction in {@code earnMilli}.
 */
public final class ShardWallet {

    private long shards;
    /** Thousandths of a shard earned from playtime but not yet a whole shard. */
    private long earnMilli;
    /** Playtime shards added since the last ledger row (one ledger row per save instead of one per minute). */
    private long unledgered;

    ShardWallet(long shards, long earnMilli) {
        this.shards = shards;
        this.earnMilli = earnMilli;
    }

    public synchronized long shards() {
        return shards;
    }

    synchronized long add(long amount) {
        shards = Math.max(0, Math.addExact(shards, amount));
        return shards;
    }

    /** Adds only if the result stays at or below {@code max} (checklist 8). */
    synchronized boolean tryAdd(long amount, long max) {
        if (amount < 0 || shards > max - amount) {
            return false;
        }
        shards += amount;
        return true;
    }

    /** Removes shards only if there are enough (checklist 8: the balance never goes below zero). */
    synchronized boolean tryWithdraw(long amount) {
        if (amount < 0 || shards < amount) {
            return false;
        }
        shards -= amount;
        return true;
    }

    synchronized long set(long amount) {
        long before = shards;
        shards = amount;
        return before;
    }

    /**
     * Adds a per-minute earning in thousandths, moves whole shards into the balance and returns how many whole shards
     * were added (0 most minutes at low rates).
     */
    synchronized long earn(long milli, long maxBalance) {
        earnMilli = Math.addExact(earnMilli, milli);
        long whole = earnMilli / 1000;
        if (whole <= 0) {
            return 0;
        }
        earnMilli -= whole * 1000;
        long room = Math.max(0, maxBalance - shards);
        long added = Math.min(whole, room);
        shards += added;
        unledgered += added;
        return added;
    }

    synchronized long takeUnledgered() {
        long u = unledgered;
        unledgered = 0;
        return u;
    }

    synchronized void returnUnledgered(long amount) {
        unledgered += amount;
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(shards, earnMilli);
    }

    record Snapshot(long shards, long earnMilli) {
    }
}
