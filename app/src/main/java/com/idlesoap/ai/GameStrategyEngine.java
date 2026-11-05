package com.idlesoap.ai;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Observation-only strategy engine.
 * Measures OCR changes and makes recommendations.
 * It never sends taps or activates boosts.
 */
public final class GameStrategyEngine {

    private static final int MAX_SAMPLES_PER_UPGRADE = 5;

    private enum UpgradeType {
        CUT("Cut"), INCOME("Income"), SPEED("Speed");

        final String displayName;

        UpgradeType(String displayName) {
            this.displayName = displayName;
        }
    }

    private final Map<UpgradeType, List<Double>> earningsGainSamples =
            new EnumMap<>(UpgradeType.class);

    public static final class Report {
        public final String repeatedGameplay;
        public final String autoClicker;
        public final String upgradePriority;
        public final String rewardChoice;

        private Report(String repeatedGameplay, String autoClicker,
                       String upgradePriority, String rewardChoice) {
            this.repeatedGameplay = repeatedGameplay;
            this.autoClicker = autoClicker;
            this.upgradePriority = upgradePriority;
            this.rewardChoice = rewardChoice;
        }

        public String toDisplayText() {
            StringBuilder out = new StringBuilder();

            out.append("STRATEGY ENGINE V1\n\n");

            out.append("REPEATED GAMEPLAY MEASUREMENT\n")
                    .append(repeatedGameplay).append("\n\n");

            out.append("AUTO-CLICKER EVALUATION\n")
                    .append(autoClicker).append("\n\n");

            out.append("UPGRADE PRIORITY\n")
                    .append(upgradePriority).append("\n\n");

            out.append("LEVEL-UP REWARD PLAN\n")
                    .append(rewardChoice).append("\n\n");

            out.append("MODE: OBSERVATION ONLY — no taps or boost activations are sent.");

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
                    "Waiting for a readable Boosts section.",
                    "Waiting for upgrade information.",
                    "Configured preference: first card = Merge. Reward screen not detected.");
        }

        collectUpgradeEvidence(current, previous);

        return new Report(
                measureProgress(current, previous),
                evaluateAutoClicker(current, previous),
                rankUpgrades(current),
                describeRewardPlan(current));
    }

    // ==================================
    // 1. REPEATED GAMEPLAY MEASUREMENT
    // ==================================

    private String measureProgress(
            GameState current,
            GameState previous) {

        if (current.progressCurrent == null
                || current.progressRequired == null) {

            return "The progress meter is not readable yet, so the game-progress rate cannot be measured.";
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
                && !current.progressLevel.equals(previous.progressLevel))
                || !current.progressRequired.equals(
                        previous.progressRequired)) {

            return "The progress level or target changed. The previous measurement window is closed; a new baseline is needed.";
        }

        long elapsedMillis =
                current.parsedAtMillis - previous.parsedAtMillis;

        int change =
                current.progressCurrent - previous.progressCurrent;

        if (elapsedMillis <= 0L) {
            return "Progress changed by " + signed(change)
                    + " units, but the snapshot timing is unavailable.";
        }

        double seconds = elapsedMillis / 1000.0d;

        if (change > 0) {
            double rate = change / seconds;

            return "Progress advanced by " + change
                    + " units in " + formatOneDecimal(seconds)
                    + " sec (about " + formatTwoDecimals(rate)
                    + " progress units/sec). This is a progress rate, not a verified tap count.";
        }

        if (change == 0) {
            return "No progress movement was visible over "
                    + formatOneDecimal(seconds)
                    + " sec. A one-second OCR snapshot cannot prove that tapping has no effect.";
        }

        return "Progress moved backward by "
                + Math.abs(change)
                + " units. This may be a reset or a new stage, so no tapping rate is calculated.";
    }

    // ==================================
    // 2. AUTO-CLICKER EVALUATION
    // ==================================

    private String evaluateAutoClicker(
            GameState current,
            GameState previous) {

        if (!current.autoClickerMentioned) {
            return "Auto-clicker text was not detected in the current Boosts crop.";
        }

        StringBuilder out = new StringBuilder();

        out.append("Auto-clicker label detected. ");

        if (current.freeMarkerSeen) {
            out.append("A Free marker also appears in the Boosts crop, but OCR cannot confirm that it belongs to Auto-clicker. ");
        } else {
            out.append("No associated Free marker was confirmed. ");
        }

        out.append("Active/inactive status is not readable, so the boost is not treated as activated.");

        if (previous != null
                && previous.earningsPerSecond != null
                && current.earningsPerSecond != null) {

            double delta =
                    current.earningsPerSecond
                            - previous.earningsPerSecond;

            out.append(" Earnings changed by ")
                    .append(formatSignedTwoDecimals(delta))
                    .append(" per second during this interval; OCR cannot attribute that change to Auto-clicker.");
        }

        return out.toString();
    }

    // ==================================
    // 3. UPGRADE PERFORMANCE MEASUREMENT
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

            if (before != null
                    && after != null
                    && after.intValue() > before.intValue()) {

                changed.add(type);
            }
        }

        // Multiple changed categories make attribution uncertain.
        if (changed.size() != 1) {
            return;
        }

        UpgradeType changedType = changed.get(0);

        Integer beforeLevel = upgradeLevel(previous, changedType);
        Integer afterLevel = upgradeLevel(current, changedType);

        int levelsAdded =
                afterLevel.intValue() - beforeLevel.intValue();

        double gain =
                current.earningsPerSecond
                        - previous.earningsPerSecond;

        double tolerance = Math.max(
                0.05d,
                Math.abs(previous.earningsPerSecond) * 0.001d);

        // Keep only positive, isolated earnings gains.
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
    // 4. UPGRADE PRIORITIZATION
    // ==================================

    private String rankUpgrades(GameState current) {

        UpgradeType bestType = null;
        GameState.Upgrade bestUpgrade = null;

        double bestPaybackSeconds = Double.MAX_VALUE;
        double bestAverageGain = 0.0d;
        int bestSamples = 0;

        StringBuilder evidence = new StringBuilder();

        for (UpgradeType type : UpgradeType.values()) {

            GameState.Upgrade upgrade = upgrade(current, type);

            List<Double> samples =
                    earningsGainSamples.get(type);

            double average = average(samples);

            evidence.append(type.displayName).append(": ");

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

            return "No upgrade can be ranked reliably yet. The engine needs to observe one upgrade category increase on its own and compare earnings before/after. If multiple categories change together, that sample is ignored.\n"
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
                && current.money.longValue()
                >= bestUpgrade.cost.longValue()) {

            result.append("Current money is enough for its displayed next cost.");

        } else if (current.money != null) {

            result.append("Next cost: ")
                    .append(bestUpgrade.cost)
                    .append("; current money: ")
                    .append(current.money)
                    .append(". Save before buying.");

        } else {

            result.append("Affordability is unknown because money was not read.");
        }

        result.append(" This estimate assumes the next level gives a similar gain; it is not guaranteed.");

        return result.toString();
    }

    // ==================================
    // 5. MERGE REWARD PREFERENCE
    // ==================================

    private String describeRewardPlan(GameState current) {

        String preference =
                "Configured choice: first/leftmost reward card = Merge.";

        if (current.isProgressComplete()) {

            return "Level Up is ready. " + preference
                    + " The current OCR regions do not read reward-card text, so the Merge card cannot yet be visually verified or selected.";
        }

        String raw = current.rawOcrText == null
                ? ""
                : current.rawOcrText.toLowerCase(Locale.ROOT);

        if (raw.contains("merge")
                && raw.contains("take")
                && raw.contains("level up")) {

            return "Reward-related text appears in OCR. "
                    + preference
                    + " Card position and the Take control still need visual confirmation before any input can be considered.";
        }

        return preference
                + " Waiting for Level Up. Reward-card detection is not covered by the current OCR regions.";
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
            total += value.doubleValue();
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

    private static String formatSignedTwoDecimals(double value) {
        return (value > 0.0d ? "+" : "")
                + formatTwoDecimals(value);
    }
    }
