package com.idlesoap.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Structured snapshot created from the app's existing sectioned OCR text. */
public final class GameState {
    private static final Pattern HEADER = Pattern.compile("^\\[([A-Z ]+)\\]$");
    private static final Pattern AMOUNT = Pattern.compile("(?i)^\\s*[₹$]?\\s*(\\d+(?:[.,]\\d+)?)\\s*([KMB]?)\\s*$");
    private static final Pattern EARNINGS = Pattern.compile("(?i)^\\s*[₹$]?\\s*(\\d+(?:[.,]\\d+)?)\\s*([KMB]?)\\s*(?:/\\s*sec|per\\s*sec)\\s*$");
    private static final Pattern LEVEL = Pattern.compile("(?i)\\b(?:lvl|lvi|lvy|lv1|lul|lui|lu1|ly|level)\\s*(\\d{1,3})\\b");
    private static final Pattern PROGRESS = Pattern.compile("(?<!\\d)(\\d{1,4})\\s*/\\s*(\\d{1,4})(?!\\d)");
    private static final Pattern PERCENT = Pattern.compile("(?<!\\d)(\\d{1,3})\\s*%");

    /** Null numeric fields mean the value was not confidently extracted. */
    public final long parsedAtMillis;
    public final String rawOcrText;
    public final Long money;
    public final String moneyDisplay;
    public final Double earningsPerSecond;
    public final String earningsDisplay;
    public final String soapName;
    public final Integer soapNumber;
    public final Integer soapPercent;
    public final Integer progressLevel;
    public final Integer progressCurrent;
    public final Integer progressRequired;
    public final boolean levelUpReady;
    public final Upgrade cutUpgrade;
    public final Upgrade incomeUpgrade;
    public final Upgrade speedUpgrade;
    public final List<String> boostEntries;
    public final boolean autoClickerMentioned;
    public final boolean freeMarkerSeen;

    private GameState(String raw, Long money, String moneyText,
                      Double earnings, String earningsText, String soapName,
                      Integer soapNumber, Integer soapPercent, Integer level,
                      Integer progress, Integer progressMax, boolean levelUpReady,
                      Upgrade cut, Upgrade income, Upgrade speed,
                      List<String> boosts, boolean autoClicker, boolean freeMarker) {
        this.parsedAtMillis = System.currentTimeMillis();
        this.rawOcrText = raw;
        this.money = money;
        this.moneyDisplay = moneyText;
        this.earningsPerSecond = earnings;
        this.earningsDisplay = earningsText;
        this.soapName = soapName;
        this.soapNumber = soapNumber;
        this.soapPercent = soapPercent;
        this.progressLevel = level;
        this.progressCurrent = progress;
        this.progressRequired = progressMax;
        this.levelUpReady = levelUpReady;
        this.cutUpgrade = cut;
        this.incomeUpgrade = income;
        this.speedUpgrade = speed;
        this.boostEntries = Collections.unmodifiableList(new ArrayList<>(boosts));
        this.autoClickerMentioned = autoClicker;
        this.freeMarkerSeen = freeMarker;
    }

    public static GameState fromRawOcr(String rawText) {
        String raw = rawText == null ? "" : rawText;
        Map<String, String> s = splitSections(raw);
        String moneyText = firstLine(s.get("MONEY"));
        String earningsText = firstLine(s.get("EARNINGS"));
        String progressSection = s.get("PROGRESS LEVEL");
        Integer[] progress = parseProgress(progressSection);
        String boostsSection = s.get("BOOSTS");

        return new GameState(raw,
                parseAmount(moneyText), moneyText,
                parseEarnings(earningsText), earningsText,
                firstLine(s.get("SOAP NAME")),
                parseSoapNumber(s.get("SOAP STATUS")),
                parsePercent(s.get("SOAP PERCENT")),
                parseLevel(progressSection), progress[0], progress[1],
                containsLevelUp(s.get("LEVEL UP BUTTON")),
                parseUpgrade(s.get("CUT UPGRADE")),
                parseUpgrade(s.get("INCOME UPGRADE")),
                parseUpgrade(s.get("SPEED UPGRADE")),
                parseBoosts(boostsSection), containsAutoClicker(boostsSection),
                containsFree(boostsSection));
    }

    public boolean hasUsefulGameData() {
        return money != null || earningsPerSecond != null || soapNumber != null
                || soapName != null || progressLevel != null
                || progressCurrent != null || levelUpReady;
    }

    public boolean isProgressComplete() {
        return levelUpReady || (progressCurrent != null && progressRequired != null
                && progressRequired > 0 && progressCurrent >= progressRequired);
    }

    public boolean canAfford(Upgrade upgrade) {
        return money != null && upgrade != null && upgrade.cost != null
                && money.longValue() >= upgrade.cost.longValue();
    }

    public String getProgressDisplay() {
        if (isProgressComplete()) return "Ready to level up";
        if (progressCurrent == null || progressRequired == null) return "Unknown";
        return progressCurrent + "/" + progressRequired;
    }

    public static final class Upgrade {
        public final Integer level;
        public final Long cost;
        public final String costDisplay;

        private Upgrade(Integer level, Long cost, String display) {
            this.level = level;
            this.cost = cost;
            this.costDisplay = display;
        }

        public boolean isKnown() {
            return level != null || cost != null;
        }
    }

    private static Map<String, String> splitSections(String raw) {
        Map<String, StringBuilder> builders = new LinkedHashMap<>();
        String current = null;

        for (String line : raw.split("\\R")) {
            Matcher m = HEADER.matcher(line.trim());
            if (m.matches()) {
                current = m.group(1);
                builders.put(current, new StringBuilder());
            } else if (current != null) {
                builders.get(current).append(line).append('\n');
            }
        }

        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, StringBuilder> entry : builders.entrySet()) {
            result.put(entry.getKey(), entry.getValue().toString().trim());
        }
        return result;
    }

    private static String firstLine(String section) {
        if (section == null) return null;
        for (String line : section.split("\\R")) {
            String value = line.trim();
            String lower = value.toLowerCase(Locale.ROOT);
            if (!value.isEmpty() && !lower.equals("not detected")
                    && !lower.equals("unknown") && !lower.equals("recognition error")
                    && !lower.equals("no text detected yet.")) return value;
        }
        return null;
    }

    private static Long parseAmount(String text) {
        if (text == null) return null;
        Matcher m = AMOUNT.matcher(text);
        if (!m.matches()) return null;
        try {
            double value = Double.parseDouble(m.group(1).replace(',', '.'));
            String suffix = m.group(2).toUpperCase(Locale.ROOT);
            if ("K".equals(suffix)) value *= 1_000d;
            if ("M".equals(suffix)) value *= 1_000_000d;
            if ("B".equals(suffix)) value *= 1_000_000_000d;
            if (value > Long.MAX_VALUE) return null;
            return Math.round(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double parseEarnings(String text) {
        if (text == null) return null;
        String normalized = text.replace('|', '/').replace('\\', '/');
        Matcher m = EARNINGS.matcher(normalized);
        if (!m.matches()) return null;
        try {
            double value = Double.parseDouble(m.group(1).replace(',', '.'));
            String suffix = m.group(2).toUpperCase(Locale.ROOT);
            if ("K".equals(suffix)) value *= 1_000d;
            if ("M".equals(suffix)) value *= 1_000_000d;
            if ("B".equals(suffix)) value *= 1_000_000_000d;
            return value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer parseSoapNumber(String section) {
        if (section == null) return null;
        Matcher m = Pattern.compile("(?i)soap\\s*#?\\s*(\\d+)").matcher(section);
        return m.find() ? integerOrNull(m.group(1)) : null;
    }

    private static Integer parsePercent(String section) {
        if (section == null || section.trim().equalsIgnoreCase("Not detected")) return null;
        Matcher m = PERCENT.matcher(section);
        while (m.find()) {
            Integer value = integerOrNull(m.group(1));
            if (value != null && value <= 100) return value;
        }

        // The percent crop is dedicated to this value, so accept digits if '%' was missed.
        String digits = section.trim();
        if (digits.matches("\\d{1,3}")) {
            Integer value = integerOrNull(digits);
            if (value != null && value <= 100) return value;
        }
        return null;
    }

    private static Integer parseLevel(String section) {
        if (section == null) return null;
        Matcher m = LEVEL.matcher(section);
        return m.find() ? integerOrNull(m.group(1)) : null;
    }

    private static Integer[] parseProgress(String section) {
        Integer[] result = new Integer[] { null, null };
        if (section == null) return result;
        Matcher m = PROGRESS.matcher(section);
        if (m.find()) {
            Integer a = integerOrNull(m.group(1));
            Integer b = integerOrNull(m.group(2));
            if (a != null && b != null && b > 0 && a <= b) {
                result[0] = a;
                result[1] = b;
            }
        }
        return result;
    }

    private static boolean containsLevelUp(String section) {
        return section != null
                && Pattern.compile("(?i)\\blevel\\s*up\\b").matcher(section).find();
    }

    private static Upgrade parseUpgrade(String section) {
        if (section == null) return new Upgrade(null, null, null);
        Integer level = parseLevel(section);
        Long cost = null;
        String display = null;
        for (String line : section.split("\\R")) {
            Long candidate = parseAmount(line.trim());
            if (candidate != null) {
                cost = candidate;
                display = line.trim();
                break;
            }
        }
        return new Upgrade(level, cost, display);
    }

    private static List<String> parseBoosts(String section) {
        List<String> result = new ArrayList<>();
        if (section == null) return result;
        for (String line : section.split("\\R")) {
            String value = line.trim();
            String lower = value.toLowerCase(Locale.ROOT);
            if (!value.isEmpty() && !lower.equals("free") && !lower.equals("dfree")
                    && !lower.equals("not detected") && !lower.equals("recognition error")) {
                result.add(value);
            }
        }
        return result;
    }

    private static boolean containsAutoClicker(String section) {
        if (section == null) return false;
        String text = section.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        return text.contains("autoclick") || text.contains("autocllck")
                || text.contains("autoclcker");
    }

    private static boolean containsFree(String section) {
        return section != null
                && Pattern.compile("(?i)\\b(?:d?free)\\b").matcher(section).find();
    }

    private static Integer integerOrNull(String value) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
