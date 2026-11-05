
package com.idlesoap.ai;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Strategy engine focused on normal gameplay.
 * It measures and recommends, but does not send game actions.
 */
public final class GameStrategyEngine {

    private static final int MAX_SAMPLES_PER_UPGRADE = 5;

    private enum UpgradeType {
        CUT("Cut"),
        INCOME("Income"),
        SPEED("Speed");

        final String displayName;

        UpgradeType(String displayName) {
            this.displayName = displayName;
        }
    }

    private final Map<UpgradeType, List<Double>> earningsGainSamples =
            new EnumMap<>(UpgradeType.class);

    public static final class Report {

        public final String gameplayMeasurement;
        public final String upgradePriority;
        public final String rewardChoice;

        private Report(
                String gameplayMeasurement,
                String upgradePriority,
                String rewardChoice) {

            this.gameplayMeasurement = gameplayMeasurement;
            this.upgradePriority = upgradePriority;
            this.rewardChoice = rewardChoice;
        }

        public String toDisplayText() {

            StringBuilder out = new StringBuilder();

            out.append("STRATEGY ENGINE V1\n\n");

            out.append("GAMEPLAY PROGRESS MEASUREMENT\n")
                    .append(gameplayMeasurement)
                    .append("\n\n");

            out.append("UPGRADE PRIORITY\n")
                    .append(upgradePriority)
                    .append("\n\n");

            out.append("LEVEL-UP REWARD PLAN\n")
                    .append(rewardChoice)
                    .append("\n\n");

            out.append(
                    "MODE: OBSERVATION ONLY — no game actions are being sent.");

            return out.toString();
        }
    }

    public synchronized void reset() {
        earningsGainSamples.clear();
    }

    public synchronized Report evaluate(
            GameState current,
            GameState previous) {

        if (current == null) {

            return new Report(
                    "Waiting for a readable progress value.",
                    "Waiting for upgrade information.",
                    "Configured preference: first card = Merge. Reward screen not detected.");
        }

        collectUpgradeEvidence(current, previous);

        return new Report(
                measureProgress(current, previous),
                rankUpgrades(current),
                describeRewardPlan(current));
    }

    // ==================================
    // 1. GAMEPLAY PROGRESS MEASUREMENT
    // ==================================

    private String measureProgress(
            GameState current,
            GameState previous) {

        if (current.progressCurrent == null
                || current.progressRequired == null) {

            return "The progress meter is not readable yet.";
        }

        if (previous == null
                || previous.progressCurrent == null
                || previous.progressRequired == null) {

            return "Baseline captured at "
                    + current.getProgressDisplay()
                    + ". Waiting for the next readable snapshot.";
        }

        if ((current.progressLevel != null
                && previous.progressLevel != null
                && !current.progressLevel.equals(
                        previous.progressLevel))
                || !current.progressRequired.equals(
                        previous.progressRequired)) {

            return "The progress level or target changed. A new measurement baseline is needed.";
        }

        long elapsedMillis =
                current.parsedAtMillis
                        - previous.parsedAtMillis;

        int change =
                current.progressCurrent
                        - previous.progressCurrent;

        if (elapsedMillis <= 0L) {

            return "Progress changed by "
                    + signed(change)
                    + " units, but snapshot timing is unavailable.";
        }

        double seconds = elapsedMillis / 1000.0d;

        if (change > 0) {

            double rate = change / seconds;

            return "Progress advanced by "
                    + change
                    + " units in "
                    + formatOneDecimal(seconds)
                    + " sec. Rate: "
                    + formatTwoDecimals(rate)
                    + " progress units/sec. This is progress speed, not a verified tap count.";
        }

        if (change == 0) {

            return "No progress movement was visible over "
                    + formatOneDecimal(seconds)
                    + " sec. More observations are needed.";
        }

        return "Progress moved backward by "
                + Math.abs(change)
                + " units. This may be a level reset or new stage.";
    }

    // ==================================
    // 2. UPGRADE PERFORMANCE MEASUREMENT
    // ==================================

    private void collectUpgradeEvidence(
            GameState current,
            GameState previous) {

        if (previous == null
                || previous.earningsPerSecond == null
                || current.earningsPerSecond == null) {

            return;
        }

        List<UpgradeType> changed = new ArrayList<>();

        for (UpgradeType type : UpgradeType.values()) {

            Integer before = upgradeLevel(previous, type);
            Integer after = upgradeLevel(current, type);

            if (before != null && after != null
                    && after > before) {

                changed.add(type);
            }
        }

        // Ignore the sample if more than one upgrade changed.
        if (changed.size() != 1) {
            return;
        }

        UpgradeType changedType = changed.get(0);

        int levelsAdded =
                upgradeLevel(current, changedType)
                        - upgradeLevel(previous, changedType);

        double gain =
                current.earningsPerSecond
                        - previous.earningsPerSecond;

        double tolerance = Math.max(
                0.05d,
                Math.abs(previous.earningsPerSecond) * 0.001d);

        if (gain <= tolerance || levelsAdded <= 0) {
            return;
        }

        double gainPerLevel = gain / levelsAdded;

        List<Double> samples =
                earningsGainSamples.get(changedType);

        if (samples == null) {

            samples = new ArrayList<>();
            earningsGainSamples.put(changedType, samples);
        }

        samples.add(gainPerLevel);

        while (samples.size() > MAX_SAMPLES_PER_UPGRADE) {
            samples.remove(0);
        }
    }

    // ==================================
    // 3. UPGRADE PRIORITIZATION
    // ==================================

    private String rankUpgrades(GameState current) {

        UpgradeType bestType = null;
        GameState.Upgrade bestUpgrade = null;

        double bestPaybackSeconds = Double.MAX_VALUE;
        double bestAverageGain = 0.0d;
        int bestSamples = 0;

        StringBuilder evidence = new StringBuilder();

        for (UpgradeType type : UpgradeType.values()) {

            GameState.Upgrade upgrade =
                    upgrade(current, type);

            List<Double> samples =
                    earningsGainSamples.get(type);

            double average = average(samples);

            evidence.append(type.displayName)
                    .append(": ");

            if (samples == null || samples.isEmpty()) {

                evidence.append("no isolated earnings sample");

            } else {

                evidence.append("measured average +")
                        .append(formatTwoDecimals(average))
                        .append("/sec per level from ")
                        .append(samples.size())
                        .append(" sample(s)");
            }

            if (upgrade != null
                    && upgrade.cost != null
                    && upgrade.cost > 0L
                    && average > 0.0d) {

                double payback =
                        upgrade.cost.doubleValue() / average;

                if (payback < bestPaybackSeconds) {

                    bestPaybackSeconds = payback;
                    bestType = type;
                    bestUpgrade = upgrade;
                    bestAverageGain = average;
                    bestSamples = samples.size();
                }
            }

            evidence.append("\n");
        }

        if (bestType == null) {

            return "No upgrade can be ranked reliably yet. The AI needs to observe one upgrade category increase on its own and compare earnings before and after it. Multiple simultaneous upgrades are ignored.\n"
                    + evidence.toString().trim();
        }

        StringBuilder result = new StringBuilder();

        result.append("Best measured income return: ")
                .append(bestType.displayName)
                .append(". Estimated payback: ")
                .append(formatOneDecimal(bestPaybackSeconds))
                .append(" sec, based on +")
                .append(formatTwoDecimals(bestAverageGain))
                .append(" earnings/sec per level from ")
                .append(bestSamples)
                .append(" sample(s).\n");

        if (current.money != null
                && current.money >= bestUpgrade.cost) {

            result.append(
                    "Current money is enough for its displayed next cost.");

        } else if (current.money != null) {

            result.append("Next cost: ")
                    .append(bestUpgrade.cost)
                    .append("; current money: ")
                    .append(current.money)
                    .append(". Save before buying.");

        } else {

            result.append(
                    "Affordability is unknown because money was not read.");
        }

        result.append(
                " The estimate assumes the next level gives a similar gain; it is not guaranteed.");

        return result.toString();
    }

    // ==================================
    // 4. MERGE REWARD PREFERENCE
    // ==================================

    private String describeRewardPlan(GameState current) {

        String preference =
                "Configured choice: first/leftmost reward card = Merge.";

        if (current.isProgressComplete()) {

            return "Level Up is ready. "
                    + preference
                    + " The current OCR regions do not read the reward cards, so the card still needs visual recognition.";
        }

        String raw = current.rawOcrText == null
                ? ""
                : current.rawOcrText.toLowerCase(Locale.ROOT);

        if (raw.contains("merge")
                && raw.contains("take")
                && raw.contains("level up")) {

            return "Reward-related text appears in OCR. "
                    + preference
                    + " Card position and the Take control still need visual confirmation.";
        }

        return preference
                + " Waiting for Level Up. Reward-card detection is not covered yet.";
    }

    // ==================================
    // HELPERS
    // ==================================

    private static Integer upgradeLevel(
            GameState state,
            UpgradeType type) {

        GameState.Upgrade value = upgrade(state, type);

        return value == null ? null : value.level;
    }

    private static GameState.Upgrade upgrade(
            GameState state,
            UpgradeType type) {

        switch (type) {
            case CUT:
                return state.cutUpgrade;

            case INCOME:
                return state.incomeUpgrade;

            case SPEED:
                return state.speedUpgrade;

            default:
                return null;
        }
    }

    private static double average(List<Double> values) {

        if (values == null || values.isEmpty()) {
            return 0.0d;
        }

        double total = 0.0d;

        for (Double value : values) {
            total += value;
        }

        return total / values.size();
    }

    private static String signed(int value) {
        return value > 0 ? "+" + value : String.valueOf(value);
    }

    private static String formatOneDecimal(double value) {
        return String.format(Locale.US, "%.1f", value);
    }

    private static String formatTwoDecimals(double value) {
        return String.format(Locale.US, "%.2f", value);
    }
}
