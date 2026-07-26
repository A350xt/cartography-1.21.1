package com.liedowncraft.cartography.web;

import java.util.List;

/**
 * Minimal JSON emitter.
 *
 * <p>The mod ships no JSON dependency, and the payloads here are small and fully controlled. What
 * matters is that strings are escaped properly: dimension ids, world names and player names all
 * reach these endpoints from user-controlled data.
 */
final class Json {
    private final StringBuilder builder = new StringBuilder();
    private boolean needsComma;

    Json object() {
        builder.append('{');
        needsComma = false;
        return this;
    }

    Json endObject() {
        builder.append('}');
        needsComma = true;
        return this;
    }

    Json field(String name, String value) {
        return rawField(name, value == null ? "null" : quote(value));
    }

    Json field(String name, long value) {
        return rawField(name, Long.toString(value));
    }

    Json field(String name, int value) {
        return rawField(name, Integer.toString(value));
    }

    Json field(String name, double value) {
        // NaN and infinities are not valid JSON; emit 0 rather than corrupt the document.
        return rawField(name, Double.isFinite(value) ? trimDouble(value) : "0");
    }

    Json field(String name, boolean value) {
        return rawField(name, Boolean.toString(value));
    }

    Json stringArray(String name, List<String> values) {
        StringBuilder array = new StringBuilder("[");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                array.append(',');
            }
            array.append(quote(values.get(index)));
        }
        array.append(']');
        return rawField(name, array.toString());
    }

    /** Opens a nested object under {@code name}; caller must close it with {@link #endObject()}. */
    Json objectField(String name) {
        separate();
        builder.append(quote(name)).append(':').append('{');
        needsComma = false;
        return this;
    }

    Json rawField(String name, String rawValue) {
        separate();
        builder.append(quote(name)).append(':').append(rawValue);
        needsComma = true;
        return this;
    }

    private void separate() {
        if (needsComma) {
            builder.append(',');
        }
    }

    private static String trimDouble(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return Long.toString((long) value);
        }
        return String.format(java.util.Locale.ROOT, "%.4f", value);
    }

    static String quote(String value) {
        StringBuilder quoted = new StringBuilder(value.length() + 2);
        quoted.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> quoted.append("\\\"");
                case '\\' -> quoted.append("\\\\");
                case '\n' -> quoted.append("\\n");
                case '\r' -> quoted.append("\\r");
                case '\t' -> quoted.append("\\t");
                case '\b' -> quoted.append("\\b");
                case '\f' -> quoted.append("\\f");
                default -> {
                    if (character < 0x20) {
                        quoted.append(String.format("\\u%04x", (int) character));
                    } else {
                        quoted.append(character);
                    }
                }
            }
        }
        quoted.append('"');
        return quoted.toString();
    }

    @Override
    public String toString() {
        return builder.toString();
    }
}
