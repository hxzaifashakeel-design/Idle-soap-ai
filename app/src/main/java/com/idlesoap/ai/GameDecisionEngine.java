
package com.idlesoap.ai;

/** Read-only game decision engine. */
public final class GameDecisionEngine {

    public enum Action {
        WAIT_FOR_VALID_SCREEN,
        PRIORITIZE_LEVEL_UP,
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

        private Decision(
                Action action,
                Priority priority,
                String title,
                String explanation,
                String nextRequirement,
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

    public static Decision evaluate(
            GameState current,
            GameState previous) {

        EarningsTrend trend =
                compareEarnings(current, previous);

        if (current == null || !current.hasUsefulGameData()) {

            return new Decision(
                    Action.WAIT_FOR_VALID_SCREEN,
                    Priority.LOW,
                    "Waiting for a readable game screen",
                    "The latest OCR snapshot does not contain enough usable game information.",
                    "Wait for a fresh frame with readable money, level, or progress.",
                    trend);
        }

        if (current.isProgressComplete()) {

            return new Decision(
                    Action.PRIORITIZE_LEVEL_UP,
                    Priority.CRITICAL,
                    "Level Up available",
                    "The Level Up button is visible or the progress counter is complete. This takes priority over normal gameplay.",
                    "Next: recognize the reward screen. Your preferred reward is the first card: Merge.",
                    trend);
        }

        if (current.cutUpgrade.isKnown()
                || current.incomeUpgrade.isKnown()
                || current.speedUpgrade.isKnown()) {

            return new Decision(
                    Action.TAP_GAMEPLAY,
                    Priority.NORMAL,
                    "Continue normal gameplay",
                    "No Level Up is available. Normal soap tapping is the current gameplay priority. This is a recommendation; no tap is sent yet.",
                    "Measure progress and earnings, then compare upgrade performance.",
                    trend);
        }

        return new Decision(
                Action.COLLECT_MORE_UPGRADE_DATA,
                Priority.LOW,
                "Collect more game information",
                "The screen is partly readable, but upgrade information is incomplete.",
                "Wait for another clean OCR snapshot before choosing a strategy.",
                trend);
    }

    private static EarningsTrend compareEarnings(
            GameState current,
            GameState previous) {

        if (current == null || previous == null
                || current.earningsPerSecond == null
                || previous.earningsPerSecond == null) {

            return EarningsTrend.UNKNOWN;
        }

        double now = current.earningsPerSecond;
        double before = previous.earningsPerSecond;

        double tolerance =
                Math.max(1.0d, Math.abs(before) * 0.001d);

        if (now > before + tolerance) {
            return EarningsTrend.INCREASED;
        }

        if (now < before - tolerance) {
            return EarningsTrend.DECREASED;
        }

        return EarningsTrend.UNCHANGED;
    }
}
