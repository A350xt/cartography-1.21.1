package com.liedowncraft.cartography.render;

public final class VanillaMapPalette {
    private static final int[] BASE_COLORS = new int[]{
            0,
            8368696,
            16247203,
            13092807,
            16711680,
            10526975,
            10987431,
            31744,
            16777215,
            10791096,
            9923917,
            7368816,
            4210943,
            9402184,
            16776437,
            14188339,
            11685080,
            6724056,
            15066419,
            8375321,
            15892389,
            5000268,
            10066329,
            5013401,
            8339378,
            3361970,
            6704179,
            6717235,
            10040115,
            1644825,
            16445005,
            6085589,
            4882687,
            55610,
            8476209,
            7340544,
            13742497,
            10441252,
            9787244,
            7367818,
            12223780,
            6780213,
            10505550,
            3746083,
            8874850,
            5725276,
            8014168,
            4996700,
            4993571,
            5001770,
            9321518,
            2430480,
            12398641,
            9715553,
            6035741,
            1474182,
            3837580,
            5647422,
            1356933,
            6579300,
            14200723,
            8365974
    };

    public static final byte NONE = 0;
    public static final byte GRASS = 1;
    public static final byte DIRT = 10;
    public static final byte STONE = 11;
    public static final byte WATER = 12;

    private VanillaMapPalette() {
    }

    /**
     * Packs a map colour and brightness into true ARGB for {@link java.awt.image.BufferedImage}.
     *
     * <p>Deliberately NOT a copy of vanilla's {@code MapColor#calculateRGBColor}: despite its name
     * that method emits ABGR, because it feeds {@code NativeImage.setPixelRGBA}, which is
     * little-endian. Mirroring it here would swap red and blue in every written tile.
     */
    public static int argb(int mapColorId, Brightness brightness) {
        if (mapColorId <= 0 || mapColorId >= BASE_COLORS.length) {
            return 0;
        }

        int baseColor = BASE_COLORS[mapColorId];
        int modifier = brightness.modifier();
        int red = (baseColor >> 16 & 0xFF) * modifier / 255;
        int green = (baseColor >> 8 & 0xFF) * modifier / 255;
        int blue = (baseColor & 0xFF) * modifier / 255;
        return 0xFF000000 | red << 16 | green << 8 | blue;
    }

    public static byte packedId(int mapColorId, Brightness brightness) {
        return (byte)(mapColorId << 2 | brightness.id() & 3);
    }

    public static int argbFromPackedId(byte packedId) {
        int packed = Byte.toUnsignedInt(packedId);
        return argb(packed >> 2, Brightness.byId(packed & 3));
    }

    public enum Brightness {
        LOW(0, 180),
        NORMAL(1, 220),
        HIGH(2, 255),
        LOWEST(3, 135);

        private final int id;
        private final int modifier;

        Brightness(int id, int modifier) {
            this.id = id;
            this.modifier = modifier;
        }

        public int id() {
            return id;
        }

        public int modifier() {
            return modifier;
        }

        public static Brightness byId(int id) {
            return switch (id) {
                case 0 -> LOW;
                case 1 -> NORMAL;
                case 2 -> HIGH;
                case 3 -> LOWEST;
                default -> NORMAL;
            };
        }
    }
}
