package com.idlesoap.ai;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GameStateParser {

    private static final Pattern MONEY =
            Pattern.compile(
                    "(?i)^\\s*[₹$]?\\s*\\d+(?:[.,]\\d+)?\\s*[KMB]?\\s*$");

    // Includes common OCR mistakes such as Lul instead of Lvl.
    private static final Pattern LEVEL =
            Pattern.compile(
                    "(?i)\\b(?:lvl|lvi|lvy|lv1|lul|lui|lu1|ly|level)\\s*(\\d{1,3})\\b");

    private static final Pattern PERCENT =
            Pattern.compile("(\\d{1,3})\\s*%");

    private static final Pattern PROGRESS =
            Pattern.compile("(\\d{1,4})\\s*/\\s*(\\d{1,4})");

    private GameStateParser() {
    }

    public static String createSummary(String rawText) {

        if (rawText == null || rawText.trim().isEmpty()) {
            return "Waiting for game information...";
        }

        Map<String, String> sections = splitSections(rawText);

        String money = findMoney(sections.get("MONEY"));
        String earnings = findEarnings(sections.get("EARNINGS"));

        String soapNumber =
                findSoapNumber(sections.get("SOAP STATUS"));

        String soapPercentSection = sections.get("SOAP PERCENT");

        String soapPercent = soapPercentSection != null
                ? findSoapPercent(soapPercentSection)
                : findPercent(sections.get("SOAP STATUS"));

        String soapName =
                firstUsefulLine(sections.get("SOAP NAME"));

        String progressLevelSection =
                sections.get("PROGRESS LEVEL");

        String progressLevel = findLevel(progressLevelSection);

        boolean levelUpReady =
                isLevelUpReady(sections.get("LEVEL UP BUTTON"));

        String levelUpProgress = levelUpReady
                ? "Ready to level up"
                : findProgress(progressLevelSection);

        String cut = findUpgrade(sections.get("CUT UPGRADE"));
        String income = findUpgrade(sections.get("INCOME UPGRADE"));
        String speed = findUpgrade(sections.get("SPEED UPGRADE"));

        

        StringBuilder result = new StringBuilder();

        result.append("CURRENT GAME STATE\n\n");

        result.append("Money: ")
                .append(money).append("\n");

        result.append("Earnings: ")
                .append(earnings).append("\n");

        result.append("Current soap: ")
                .append(soapName)
                .append(" (")
                .append(soapNumber)
                .append(")\n");

        result.append("Soap completion: ")
                .append(soapPercent).append("\n");

        result.append("Progress level: ")
                .append(progressLevel).append("\n");

        result.append("Level-up progress: ")
                .append(levelUpProgress).append("\n\n");

        result.append("UPGRADES\n\n");

        result.append("Cut: ")
                .append(cut).append("\n");

        result.append("Income: ")
                .append(income).append("\n");

        result.append("Speed: ")
                .append(speed).append("\n\n");

        

        result.append(
                "Each value is read from its own screen region. ")
                .append("Missing or unclear readings remain unconfirmed.");

        return result.toString();
    }

    private static Map<String, String> splitSections(
            String rawText) {

        Map<String, StringBuilder> builders =
                new LinkedHashMap<>();

        String current = null;

        for (String line : rawText.split("\\R")) {

            String trimmed = line.trim();

            if (trimmed.matches("^\\[[A-Z ]+\\]$")) {

                current = trimmed.substring(
                        1, trimmed.length() - 1);

                builders.put(current, new StringBuilder());

            } else if (current != null) {

                builders.get(current)
                        .append(line)
                        .append('\n');
            }
        }

        Map<String, String> sections =
                new LinkedHashMap<>();

        for (Map.Entry<String, StringBuilder> entry
                : builders.entrySet()) {

            sections.put(
                    entry.getKey(),
                    entry.getValue().toString().trim());
        }

        return sections;
    }

    private static String findMoney(String section) {

        if (section == null) {
            return "Not detected";
        }

        for (String line : section.split("\\R")) {

            String value = line.trim();

            if (MONEY.matcher(value).matches()) {
                return value;
            }
        }

        return "Not detected";
    }

    private static String findEarnings(String section) {

        if (section == null) {
            return "Not detected";
        }

        for (String line : section.split("\\R")) {

            String compact = line.toUpperCase()
                    .replaceAll("\\s+", "");

            compact = compact.replaceAll(
                    "(?<=[KMB])[JI|](?=SEC)", "/");

            Matcher matcher = Pattern.compile(
                    "^(\\d+(?:[.,]\\d+)?[KMB]?)/SEC$")
                    .matcher(compact);

            if (matcher.matches()) {

                return matcher.group(1)
                        .replace(',', '.') + "/sec";
            }

            return "Uncertain: " + line.trim();
        }

        return "Not detected";
    }

    private static String findSoapNumber(String section) {

        if (section == null) {
            return "Unknown";
        }

        Matcher matcher = Pattern.compile(
                "(?i)soap\\s*#?\\s*(\\d+)")
                .matcher(section);

        return matcher.find()
                ? "#" + matcher.group(1)
                : "Unknown";
    }

    private static String findPercent(String section) {

        if (section == null) {
            return "Not detected";
        }

        Matcher matcher = PERCENT.matcher(section);

        while (matcher.find()) {

            int value =
                    Integer.parseInt(matcher.group(1));

            if (value <= 100) {
                return value + "%";
            }
        }

        return "Not detected";
    }

    private static String findSoapPercent(String section) {

        if (section == null) {
            return "Not detected";
        }

        // Prefer a complete reading such as 55%.
        Matcher percentMatcher = PERCENT.matcher(section);

        while (percentMatcher.find()) {

            int value =
                    Integer.parseInt(percentMatcher.group(1));

            if (value <= 100) {
                return value + "%";
            }
        }

        // The dedicated crop isolates the percentage display.
        // Accept two or three digits if OCR misses the % symbol.
        Matcher digitsMatcher = Pattern.compile(
                "(?<!\\d)(\\d{2,3})(?!\\d)")
                .matcher(section);

        while (digitsMatcher.find()) {

            int value =
                    Integer.parseInt(digitsMatcher.group(1));

            if (value <= 100) {
                return value + "%";
            }
        }

        return "Not detected";
    }

    private static boolean isLevelUpReady(String section) {

        if (section == null) {
            return false;
        }

        return Pattern.compile("(?i)level\\s*up")
                .matcher(section)
                .find();
    }

    private static String findProgress(String section) {

        if (section == null) {
            return "Not detected";
        }

        Matcher matcher = PROGRESS.matcher(section);

        if (matcher.find()) {

            int completed =
                    Integer.parseInt(matcher.group(1));

            int total =
                    Integer.parseInt(matcher.group(2));

            if (total > 0 && completed <= total) {
                return completed + "/" + total;
            }
        }

        return "Not detected";
    }

    private static String findLevel(String section) {

        if (section == null) {
            return "Not detected";
        }

        Matcher matcher = LEVEL.matcher(section);

        return matcher.find()
                ? matcher.group(1)
                : "Not detected";
    }

    private static String findUpgrade(String section) {

        if (section == null) {
            return "Not detected";
        }

        String level = findLevel(section);
        String cost = findMoney(section);

        return "Level " + level + " | Cost " + cost;
    }

    private static String firstUsefulLine(String section) {

        if (section == null) {
            return "Unknown";
        }

        for (String line : section.split("\\R")) {

            String value = line.trim();

            if (!value.isEmpty()
                    && !value.equalsIgnoreCase("No text detected yet.")
                    && !value.toLowerCase().contains("recognition error")) {

                return value;
            }
        }

        return "Unknown";
    }

    

        StringBuilder result = new StringBuilder();

        for (String line : section.split("\\R")) {

            String value = line.trim();

            if (value.isEmpty()
                    || value.equalsIgnoreCase("Free")
                    || value.equalsIgnoreCase("No text detected yet.")
                    || value.equalsIgnoreCase("Recognition error")) {

                continue;
            }

            if (result.length() > 0) {
                result.append(", ");
            }

            result.append(value);
        }

        return result.length() == 0
                ? "None detected"
                : result.toString();
    }
}
