package dev.modmind.omnitools.reward;

/** Summary of one attempt to process a configured reward event. */
public record RewardGrantResult(Status status, int granted, int alreadyGranted, String reason) {
    public enum Status {
        SUCCESS,
        PENDING,
        BLOCKED,
        FAILED
    }

    public boolean complete() {
        return status == Status.SUCCESS;
    }

    public static RewardGrantResult success(int granted, int alreadyGranted) {
        return new RewardGrantResult(Status.SUCCESS, granted, alreadyGranted, "");
    }

    public static RewardGrantResult blocked(int granted, int alreadyGranted, String reason) {
        return new RewardGrantResult(Status.BLOCKED, granted, alreadyGranted, reason == null ? "" : reason);
    }

    public static RewardGrantResult pending(int granted, int alreadyGranted, String reason) {
        return new RewardGrantResult(Status.PENDING, granted, alreadyGranted, reason == null ? "" : reason);
    }

    public static RewardGrantResult failed(int granted, int alreadyGranted, String reason) {
        return new RewardGrantResult(Status.FAILED, granted, alreadyGranted, reason == null ? "" : reason);
    }
}
