package com.liedowncraft.cartography.snapshot;

/**
 * Visual classification of a sampled surface block (technical plan v2.0, section 5.3).
 *
 * <p>v1 lumped glass, ice, snow and water together as "transparent", which let the extractor fall
 * straight through a glass roof and render the floor below. Splitting the category by visual
 * semantics is what keeps glass buildings and greenhouses visible on the map.
 */
public enum SurfaceKind {
    /** Stone, planks, dirt, roofs, roads. Terminates the downward walk. */
    OPAQUE_SURFACE,

    /** Glass, stained glass, ice, glass panes. Renders as a surface, alpha-blended over what is below. */
    TRANSPARENT_STRUCTURE,

    /** Snow layers, carpet, moss, vines. Composited onto the base below rather than replacing it. */
    THIN_OVERLAY,

    /** Water and lava. Locates the fluid surface, then samples the floor beneath for depth shading. */
    FLUID_SURFACE,

    /** Leaves and plants. Treated as a surface but tinted by biome. */
    FOLIAGE_SURFACE,

    /** Torches, flowers, buttons, rail blocks. Pixel detail only; carries no clickable semantics. */
    DECORATION_VISUAL,

    /** Unknown or modded blocks that no rule matched. Falls back to the block's map color. */
    UNKNOWN_FALLBACK
}
