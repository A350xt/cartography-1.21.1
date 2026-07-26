package com.liedowncraft.cartography.web;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import com.liedowncraft.cartography.core.TileCoordinate;

/**
 * Raster tile URL parsing and building (technical plan v2.0, section 7.1).
 *
 * <p>Format: {@code /tiles/raster/{world}/{dimension}/{profile}/{tilesetVersion}/{z}/x{x}/y{y}.{ext}}
 *
 * <p>Tile coordinates carry an explicit {@code x}/{@code y} prefix so a negative value cannot be
 * mistaken for a path separator or traversal segment. The tileset version sits in the path rather
 * than a query string so a CDN can treat the whole URL as immutable.
 */
public record TilePath(
        String world,
        String dimension,
        String profile,
        String tilesetVersion,
        TileCoordinate tile,
        String extension) {

    private static final String PREFIX = "/tiles/raster/";

    public static String template() {
        return PREFIX + "{world}/{dimension}/{profile}/{tilesetVersion}/{z}/x{x}/y{y}";
    }

    public static String build(
            String world,
            String dimension,
            String profile,
            String tilesetVersion,
            TileCoordinate tile,
            String extension) {
        return PREFIX
                + encode(world) + "/"
                + encode(dimension) + "/"
                + encode(profile) + "/"
                + encode(tilesetVersion) + "/"
                + tile.zoom() + "/"
                + "x" + tile.x() + "/"
                + "y" + tile.y() + "." + extension;
    }

    /** @return empty when the path does not match the tile URL shape. */
    public static Optional<TilePath> parse(String path) {
        if (!path.startsWith(PREFIX)) {
            return Optional.empty();
        }

        String[] segments = path.substring(PREFIX.length()).split("/");
        if (segments.length != 7) {
            return Optional.empty();
        }

        try {
            String world = decode(segments[0]);
            String dimension = decode(segments[1]);
            String profile = decode(segments[2]);
            String tilesetVersion = decode(segments[3]);
            int zoom = Integer.parseInt(segments[4]);

            String xSegment = segments[5];
            String ySegment = segments[6];
            if (!xSegment.startsWith("x") || !ySegment.startsWith("y")) {
                return Optional.empty();
            }

            int dotIndex = ySegment.lastIndexOf('.');
            if (dotIndex < 0) {
                return Optional.empty();
            }

            int x = Integer.parseInt(xSegment.substring(1));
            int y = Integer.parseInt(ySegment.substring(1, dotIndex));

            // Decode before validating. A percent-encoded traversal such as "%2e%2e%2f" passes any
            // check performed on the raw text and only becomes "../" afterwards.
            String extension = decode(ySegment.substring(dotIndex + 1));
            if (!isSafeExtension(extension)) {
                return Optional.empty();
            }

            // Allowlist rather than blocklist: these segments become filesystem path components, and
            // enumerating safe characters is far more reliable than enumerating dangerous ones.
            if (!isSafeSegment(world) || !isSafeSegment(profile) || !isSafeSegment(tilesetVersion)
                    || !isSafeDimension(dimension)) {
                return Optional.empty();
            }

            return Optional.of(new TilePath(
                    world,
                    dimension,
                    profile,
                    tilesetVersion,
                    new TileCoordinate(dimension, zoom, x, y),
                    extension));
        } catch (IllegalArgumentException malformed) {
            // Covers NumberFormatException from the coordinate parses and bad percent-escapes.
            return Optional.empty();
        }
    }

    /** Letters, digits, dot, underscore and dash, with no leading dot so "." and ".." cannot appear. */
    private static boolean isSafeSegment(String segment) {
        if (segment.isEmpty() || segment.length() > 128 || segment.charAt(0) == '.') {
            return false;
        }

        for (int index = 0; index < segment.length(); index++) {
            char character = segment.charAt(index);
            boolean allowed = (character >= 'a' && character <= 'z')
                    || (character >= 'A' && character <= 'Z')
                    || (character >= '0' && character <= '9')
                    || character == '_'
                    || character == '-'
                    || character == '.';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    /** A dimension id such as {@code minecraft:overworld}: one colon, safe segments either side. */
    private static boolean isSafeDimension(String dimension) {
        int colonIndex = dimension.indexOf(':');
        if (colonIndex < 0) {
            return isSafeSegment(dimension);
        }
        if (dimension.indexOf(':', colonIndex + 1) >= 0) {
            return false;
        }
        return isSafeSegment(dimension.substring(0, colonIndex))
                && isSafeSegment(dimension.substring(colonIndex + 1));
    }

    /** Lowercase letters and digits only, so an extension can never carry path syntax. */
    private static boolean isSafeExtension(String extension) {
        if (extension.isEmpty() || extension.length() > 8) {
            return false;
        }

        for (int index = 0; index < extension.length(); index++) {
            char character = extension.charAt(index);
            if (!((character >= 'a' && character <= 'z') || (character >= '0' && character <= '9'))) {
                return false;
            }
        }
        return true;
    }

    private static String encode(String value) {
        // Dimension ids contain ':', which is legal in a path segment and stays readable unencoded.
        return value.replace("/", "%2F").replace("\\", "%5C");
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
