package dev.modmind.omnitools.entitlement;

import java.util.Locale;

/**
 * A durable, tick-based entitlement. It has no title-specific behavior, so future modules can
 * reuse the same grant and renewal semantics for time-limited permissions or products.
 */
public record TimedEntitlement(Mode mode, long remainingActiveTicks, long totalGrantedTicks,
                               long grantedAt, RenewalPolicy renewalPolicy) {
    public static final long TICKS_PER_ACTIVE_DAY = 1_728_000L;

    public TimedEntitlement {
        mode = mode == null ? Mode.PERMANENT : mode;
        renewalPolicy = renewalPolicy == null ? RenewalPolicy.EXTEND : renewalPolicy;
        grantedAt = Math.max(0L, grantedAt);
        if (mode == Mode.PERMANENT) {
            remainingActiveTicks = 0L;
            totalGrantedTicks = 0L;
        } else {
            if (remainingActiveTicks < 0L || totalGrantedTicks < 1L) {
                throw new IllegalArgumentException("active entitlement ticks must not be negative");
            }
        }
    }

    public static TimedEntitlement permanent(long grantedAt) {
        return new TimedEntitlement(Mode.PERMANENT, 0L, 0L, grantedAt, RenewalPolicy.EXTEND);
    }

    public boolean isPermanent() {
        return mode == Mode.PERMANENT;
    }

    public boolean isActive() {
        return mode == Mode.ACTIVE_DAYS && remainingActiveTicks > 0L;
    }

    public TimedEntitlement consumeActiveTick() {
        if (!isActive()) {
            return this;
        }
        long remaining = remainingActiveTicks - 1L;
        return new TimedEntitlement(Mode.ACTIVE_DAYS, remaining, totalGrantedTicks, grantedAt, renewalPolicy);
    }

    public static Grant permanentGrant() {
        return new Grant(Mode.PERMANENT, 0L, RenewalPolicy.EXTEND);
    }

    public enum Mode {
        PERMANENT("permanent"),
        ACTIVE_DAYS("active_days");

        private final String serializedName;

        Mode(String serializedName) {
            this.serializedName = serializedName;
        }

        public String serializedName() {
            return serializedName;
        }

        public static Mode parse(String value) {
            if (value == null) {
                throw new IllegalArgumentException("duration.mode is required");
            }
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "permanent" -> PERMANENT;
                case "active_days" -> ACTIVE_DAYS;
                default -> throw new IllegalArgumentException("Unknown duration.mode: " + value);
            };
        }
    }

    public enum RenewalPolicy {
        EXTEND("extend"),
        REPLACE("replace"),
        MAX("max");

        private final String serializedName;

        RenewalPolicy(String serializedName) {
            this.serializedName = serializedName;
        }

        public String serializedName() {
            return serializedName;
        }

        public static RenewalPolicy parse(String value) {
            if (value == null || value.isBlank()) {
                return EXTEND;
            }
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "extend" -> EXTEND;
                case "replace" -> REPLACE;
                case "max" -> MAX;
                default -> throw new IllegalArgumentException("Unknown title renewal policy: " + value);
            };
        }
    }

    /** Definition-time grant request; a concrete entitlement is created only when it is awarded. */
    public record Grant(Mode mode, long activeTicks, RenewalPolicy renewalPolicy) {
        public Grant {
            mode = mode == null ? Mode.PERMANENT : mode;
            renewalPolicy = renewalPolicy == null ? RenewalPolicy.EXTEND : renewalPolicy;
            if (mode == Mode.PERMANENT) {
                if (activeTicks != 0L) {
                    throw new IllegalArgumentException("permanent entitlement cannot include active ticks");
                }
            } else if (activeTicks < 1L) {
                throw new IllegalArgumentException("active entitlement ticks must be positive");
            }
        }

        public static Grant activeDays(long days, RenewalPolicy renewalPolicy) {
            if (days < 1L) {
                throw new IllegalArgumentException("duration.days must be positive");
            }
            try {
                return new Grant(Mode.ACTIVE_DAYS, Math.multiplyExact(days, TICKS_PER_ACTIVE_DAY), renewalPolicy);
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException("duration.days overflows active tick storage", exception);
            }
        }

        public TimedEntitlement applyTo(TimedEntitlement existing, long grantedAt) {
            if (existing != null && existing.isPermanent()) {
                return existing;
            }
            if (mode == Mode.PERMANENT) {
                return permanent(grantedAt);
            }
            long previous = existing == null || !existing.isActive() ? 0L : existing.remainingActiveTicks();
            long next = switch (renewalPolicy) {
                case EXTEND -> safeAdd(previous, activeTicks);
                case REPLACE -> activeTicks;
                case MAX -> Math.max(previous, activeTicks);
            };
            long total = existing == null || existing.isPermanent() ? activeTicks
                    : safeAdd(existing.totalGrantedTicks(), activeTicks);
            return new TimedEntitlement(Mode.ACTIVE_DAYS, next, total, grantedAt, renewalPolicy);
        }

        private static long safeAdd(long first, long second) {
            try {
                return Math.addExact(first, second);
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException("title entitlement tick count overflows long", exception);
            }
        }
    }
}
