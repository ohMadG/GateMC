package me.Evan52098.genscore.antihack;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Location;

import java.util.ArrayDeque;

/**
 * One player's anti-hack record (BlockvilleCore's PlayerTrack).
 *
 * <p>Folia: every field is read and written only on the player's own thread (their events, and their per-tick timer
 * on the entity scheduler, which follows them between regions). {@link #tick} is the count of ticks the player's
 * region has run since they joined; it replaces {@code Bukkit.getCurrentTick()}, which has no meaning on Folia.
 * The one exception, the reach check reading the target's position history, runs in a damage event, which Folia only
 * fires when attacker and target are owned by the same thread.
 */
final class PlayerTrack {
    static final int HISTORY = 12;

    int tick;
    int ignoreUntilTick;
    long bypassCheckedAt;
    boolean bypass;

    double airY = Double.NaN;
    int hover;
    int speedSamples;
    double speedDistance;
    int fastSurfaceUntilTick;
    Location lastSafe;
    Location speedStart;
    int lastSetbackTick = -1000;
    boolean settingBack;

    final double[] historyX = new double[HISTORY];
    final double[] historyY = new double[HISTORY];
    final double[] historyZ = new double[HISTORY];
    int historyCount;
    int historyHead;

    final ArrayDeque<Offense> offenses = new ArrayDeque<>();
    AntiHackLevel alertedLevel = AntiHackLevel.NONE;
    long lastAlertAt;

    ScheduledTask timer;

    record Offense(long at, AntiHackCheck check) {
    }

    void resetMovement() {
        this.airY = Double.NaN;
        this.hover = 0;
        this.speedSamples = 0;
        this.speedDistance = 0.0;
        this.speedStart = null;
    }

    void recordPosition(double x, double y, double z) {
        this.historyX[this.historyHead] = x;
        this.historyY[this.historyHead] = y;
        this.historyZ[this.historyHead] = z;
        this.historyHead = (this.historyHead + 1) % HISTORY;
        if (this.historyCount < HISTORY) {
            this.historyCount++;
        }
    }
}
