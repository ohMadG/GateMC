package me.Evan52098.genscore.economy;

/** One online player's money. Thread-safe: changed on many threads, read by the saver. Amounts are cents. */
public final class Wallet {

    private long cents;
    private boolean payEnabled;
    private boolean payConfirm;

    Wallet(long cents, boolean payEnabled, boolean payConfirm) {
        this.cents = cents;
        this.payEnabled = payEnabled;
        this.payConfirm = payConfirm;
    }

    public synchronized long cents() {
        return cents;
    }

    public synchronized boolean has(long amount) {
        return cents >= amount;
    }

    /** Adds (or with a negative amount removes) money unconditionally. Returns the new balance. */
    public synchronized long add(long amount) {
        cents = Math.addExact(cents, amount);
        return cents;
    }

    /** Removes money only if there is enough. */
    public synchronized boolean tryWithdraw(long amount) {
        if (amount < 0 || cents < amount) {
            return false;
        }
        cents -= amount;
        return true;
    }

    public synchronized void set(long amount) {
        cents = amount;
    }

    public synchronized boolean payEnabled() {
        return payEnabled;
    }

    public synchronized void payEnabled(boolean enabled) {
        payEnabled = enabled;
    }

    public synchronized boolean payConfirm() {
        return payConfirm;
    }

    public synchronized void payConfirm(boolean enabled) {
        payConfirm = enabled;
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(cents, payEnabled, payConfirm);
    }

    record Snapshot(long cents, boolean payEnabled, boolean payConfirm) {
    }
}
