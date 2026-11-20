package me.Evan52098.genscore.crates;

import java.util.UUID;

/**
 * One player's lockbox game, as stored in {@code pvp_lockbox_games}. Immutable; changes make a new copy.
 * Times are epoch millis. The reward is in cents and was decided once, when the game finished.
 */
public record LockboxGame(String gameKey, UUID player, String dailyPeriod, String secret, int rulesVersion,
                          String guessHistory, int attemptsUsed, String status, String rewardStatus, long rewardCents,
                          long createdAt, long updatedAt) {

    public static final String ACTIVE = "ACTIVE";
    public static final String SOLVED = "SOLVED";
    public static final String FAILED = "FAILED";
    public static final String REWARD_NOT_EARNED = "NOT_EARNED";
    public static final String REWARD_PENDING = "PENDING";
    public static final String REWARD_GRANTED = "GRANTED";

    public boolean active() {
        return ACTIVE.equals(status);
    }

    LockboxGame withRewardStatus(String newStatus) {
        return new LockboxGame(gameKey, player, dailyPeriod, secret, rulesVersion, guessHistory, attemptsUsed, status,
                newStatus, rewardCents, createdAt, updatedAt);
    }

    LockboxReward reward() {
        return new LockboxReward(rewardCents);
    }
}
