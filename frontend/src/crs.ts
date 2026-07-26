import type { CartographyManifest, TileGridManifest } from "./types";

/**
 * Minecraft CRS view transform (technical plan v2.0, section 4.2).
 *
 * Data coordinates stay raw Minecraft blocks everywhere. The tile origin and pixel scale live only
 * here and in the tile grid, so changing spawn or republishing the map cannot shift existing data.
 *
 * The map is drawn in max-zoom pixel space. Minecraft Z increases southward while OpenLayers Y
 * increases northward, so the Y axis is negated on the way in and back out again.
 */

/** Minecraft block coordinate to max-zoom map pixel. */
export function blockToPixel(grid: TileGridManifest, blockX: number, blockZ: number): [number, number] {
  return [
    (blockX - grid.tileOriginX) * grid.pixelsPerBlockAtMaxZoom,
    -((blockZ - grid.tileOriginZ) * grid.pixelsPerBlockAtMaxZoom),
  ];
}

/** Max-zoom map pixel back to Minecraft block coordinate. */
export function pixelToBlock(grid: TileGridManifest, pixelX: number, pixelY: number): [number, number] {
  return [
    pixelX / grid.pixelsPerBlockAtMaxZoom + grid.tileOriginX,
    -pixelY / grid.pixelsPerBlockAtMaxZoom + grid.tileOriginZ,
  ];
}

/** Blocks covered by one tile edge at a zoom level. Each step out doubles the footprint. */
export function blocksPerTile(grid: TileGridManifest, zoom: number): number {
  return grid.blocksPerTileAtMaxZoom * 2 ** Math.max(grid.maxZoom - zoom, 0);
}

/** Map units per pixel at a zoom level, for an OpenLayers resolution array. */
export function resolutions(grid: TileGridManifest): number[] {
  const levels: number[] = [];
  for (let zoom = grid.minZoom; zoom <= grid.maxZoom; zoom++) {
    levels.push(2 ** (grid.maxZoom - zoom));
  }
  return levels;
}

/**
 * Signed tile containing a block coordinate.
 *
 * Uses floor rather than truncation so block -1 lands in tile -1 rather than tile 0. Getting this
 * wrong is what makes negative-coordinate regions render into the wrong tile.
 */
export function blockToSignedTile(
  grid: TileGridManifest,
  zoom: number,
  blockX: number,
  blockZ: number,
): [number, number] {
  const span = blocksPerTile(grid, zoom);
  return [
    Math.floor((blockX - grid.tileOriginX) / span),
    Math.floor((blockZ - grid.tileOriginZ) / span),
  ];
}

/** Extent of the rendered area in map pixels, for the OpenLayers view. */
export function signedExtent(grid: TileGridManifest): [number, number, number, number] {
  const span = grid.blocksPerTileAtMaxZoom * grid.pixelsPerBlockAtMaxZoom;
  const minX = grid.minSignedTileX * span;
  const maxX = (grid.maxSignedTileX + 1) * span;
  // Y is negated, so the tile-space minimum becomes the pixel-space maximum.
  const minY = -(grid.maxSignedTileY + 1) * span;
  const maxY = -grid.minSignedTileY * span;
  return [minX, minY, maxX, maxY];
}

/**
 * Converts an OpenLayers tile coordinate into the signed tile the server addresses.
 *
 * OpenLayers counts Y downward from the top of its extent; the server uses signed coordinates keyed
 * to the tile origin. This is the seam where an off-by-one silently shifts the whole map.
 */
export function toSignedTileY(openLayersY: number): number {
  return -openLayersY - 1;
}

export function tileGridOf(manifest: CartographyManifest): TileGridManifest {
  return manifest.tileGrid;
}
