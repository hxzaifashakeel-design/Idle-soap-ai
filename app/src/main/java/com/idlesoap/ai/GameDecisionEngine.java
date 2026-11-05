package com.idlesoap.ai;

/** Read-only strategy layer. It evaluates the game state but does not tap. */
public final class GameDecisionEngine {

    public enum Action {
        WAIT_FOR_VALID_SCREEN,
        PRIORITIZE_LEVEL_UP,
        REVIEW_AUTO_CLICKER,
        TAP_GAMEPLAY,
        COLLECT_MORE_UPGRADE_DATA
    }

    public enum Priority {
        CRITICAL, HIGH, NORMAL, LOW
    }

    public enum EarningsTrend {
        INCREASED, DECREASED, UNCHANGED, UNKNOWN
    }

    public static final class Decision {
        public final Action action;
        public final Priority priority;
        public final String title;
        public final String explanation;
        public final String nextRequirement;
        public final EarningsTrend earningsTrend;

        private Decision(Action action, Priority priority, String title,
                         String explanation, String nextRequirement,
                         EarningsTrend earningsTrend) {
            this.action = action;
            this.priority = priority;
            this.title = title;
            this.explanation = explanation;
            this.nextRequirement = nextRequirement;
            this.earningsTrend = earningsTrend;
        }
    }

    private GameDecisionEngine() {
    }

    public static Decision evaluate(GameState current) {
        return evaluate(current, null);
    }

    /** Compare two snapshots to see whether displayed earnings changed. */
    public static Decision evaluate(GameState current, GameState previous) {
        EarningsTrend trend = compareEarnings(current, previous);

        if (current == null || !current.hasUsefulGameData()) {
            return new Decision(Action.WAIT_FOR_VALID_SCREEN, Priority.LOW,
                    "Waiting for a readable game screen",
                    "The latest OCR snapshot does not contain enough usable game information.",
                    "Wait for a fresh frame with readable money, level, or progress.",
                    trend);
        }

        if (current.isProgressComplete()) {
            return new Decision(Action.PRIORITIZE_LEVEL_UP, Priority.CRITICAL,
                    "Level Up available",
                    "The Level Up button is visible or the progress counter is complete. It takes priority over normal play.",
                    "Next: recognize the reward screen, identify the leftmost Merge card, and verify its Take control before connecting any input method.",
                    trend);
        }

        if (current.autoClickerMentioned) {
            return new Decision(Action.REVIEW_AUTO_CLICKER, Priority.HIGH,
                    "Auto-clicker detected in Boosts",
                    "OCR sees an Auto-clicker label, but does not prove whether it is active, free, timed, or ready.",
                    "Next: identify its actual status and measure earnings before and after activation.",
                    trend);
        }

        if (current.cutUpgrade.isKnown() || current.incomeUpgrade.isKnown()
                || current.speedUpgrade.isKnown()) {
            return new Decision(Action.TAP_GAMEPLAY, Priority.NORMAL,
                    "Continue normal gameplay",
                    "No Level Up is available. Repeated tapping is the current gameplay action to evaluate while tracking money and earnings. No tap is sent by this decision engine.",
                    "Collect before/after measurements so the AI can quantify the effect of tapping and upgrades before ranking purchases.",
                    trend);
        }

        return new Decision(Action.COLLECT_MORE_UPGRADE_DATA, Priority.LOW,
                "Collect more game information",
                "The screen is partly readable, but upgrade information is incomplete.",
                "Wait for another clean OCR snapshot before choosing a strategy.",
                trend);
    }

    private static EarningsTrend compareEarnings(GameState current, GameState previous) {
        if (current == null || previous == null
                || current.earningsPerSecond == null
                || previous.earningsPerSecond == null) {
            return EarningsTrend.UNKNOWN;
        }

        double now = current.earningsPerSecond.doubleValue();
        double before = previous.earningsPerSecond.doubleValue();
        double tolerance = Math.max(1.0d, Math.abs(before) * 0.001d);

        if (now > before + tolerance) return EarningsTrend.INCREASED;
        if (now < before - tolerance) return EarningsTrend.DECREASED;
        return EarningsTrend.UNCHANGED;
    }
}
