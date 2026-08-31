package dev.modmind.omnitools;

/** The only state used to render and interact with one achievement card. */
public enum AchievementDisplayState {
    LOCKED,
    IN_PROGRESS,
    CLAIMABLE,
    CLAIMED;

    public static AchievementDisplayState resolve(boolean locked, boolean completed, boolean claimed) {
        if (claimed) {
            return CLAIMED;
        }
        if (locked) {
            return LOCKED;
        }
        return completed ? CLAIMABLE : IN_PROGRESS;
    }

    public boolean isCompleted() {
        return this == CLAIMABLE || this == CLAIMED;
    }

    public boolean isClaimable() {
        return this == CLAIMABLE;
    }
}
