package me.Evan52098.genscore.plots;

/**
 * Told when a factory's blocks are in place and when they are about to go, so other modules (belt, gate) can start
 * and stop their own parts. Called from whichever thread finished the work: hop to the factory's region with
 * {@code Tasks.region} before touching the world.
 */
public interface PlotListener {

    /** The factory was built (or rebuilt by an admin) and is ready. */
    void ready(FactoryView factory);

    /** The factory is about to be cleared (owner left, or an admin rebuild). Stop everything on it. */
    void removing(FactoryView factory);
}
