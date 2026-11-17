package com.jifercraft.bingo.updater;

import java.util.Arrays;
import java.util.Objects;

/**
 * Basic semantic version (major.minor.patch) with optional pre-release tag.
 * Strips leading 'v', project prefixes, and ignores build metadata (+build).
 */
public final class Version implements Comparable<Version> {

    private final int[] numbers;
    private final String preRelease;
    private final String original;

    public Version(int[] numbers, String preRelease, String original) {
        this.numbers = numbers;
        this.preRelease = preRelease;
        this.original = original;
    }

    public static Version parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new Version(new int[]{0}, "", "0");
        }
        String original = raw.trim();
        String clean = original;

        // Skip non-digit prefixes such as "v", "V", or "BingoRush-"
        int firstDigit = -1;
        for (int i = 0; i < clean.length(); i++) {
            if (Character.isDigit(clean.charAt(i))) {
                firstDigit = i;
                break;
            }
        }
        if (firstDigit != -1) {
            clean = clean.substring(firstDigit);
        }

        int plus = clean.indexOf('+');
        if (plus != -1) {
            clean = clean.substring(0, plus);
        }

        String pre = "";
        int dash = clean.indexOf('-');
        if (dash != -1) {
            pre = clean.substring(dash + 1);
            clean = clean.substring(0, dash);
        }

        String[] tokens = clean.split("\\.");
        int[] numbers = new int[tokens.length];
        for (int i = 0; i < tokens.length; i++) {
            try {
                numbers[i] = Integer.parseInt(tokens[i].replaceAll("\\D+", ""));
            } catch (NumberFormatException e) {
                numbers[i] = 0;
            }
        }
        return new Version(numbers, pre, original);
    }

    public boolean isNewerThan(Version other) {
        return compareTo(other) > 0;
    }

    /** Returns normalized version without leading 'v' or project prefix. */
    public String clean() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < numbers.length; i++) {
            if (i > 0) sb.append('.');
            sb.append(numbers[i]);
        }
        if (!preRelease.isEmpty()) {
            sb.append('-').append(preRelease);
        }
        return sb.toString();
    }

    @Override
    public int compareTo(Version other) {
        int max = Math.max(numbers.length, other.numbers.length);
        for (int i = 0; i < max; i++) {
            int a = i < numbers.length ? numbers[i] : 0;
            int b = i < other.numbers.length ? other.numbers[i] : 0;
            if (a != b) {
                return Integer.compare(a, b);
            }
        }
        if (preRelease.isEmpty() && !other.preRelease.isEmpty()) {
            return 1;
        }
        if (!preRelease.isEmpty() && other.preRelease.isEmpty()) {
            return -1;
        }
        return preRelease.compareToIgnoreCase(other.preRelease);
    }

    @Override
    public String toString() {
        return original;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Version other)) return false;
        return compareTo(other) == 0;
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(numbers);
        result = 31 * result + Objects.hashCode(preRelease);
        return result;
    }
}
