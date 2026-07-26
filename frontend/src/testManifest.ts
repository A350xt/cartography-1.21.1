import type { CartographyManifest, TileGridManifest } from "./types";

/** Shared manifest fixture matching what the server actually serves. */
export const testTileGrid: TileGridManifest = {
  mode: "online-signed",
  tileSize: 256,
  minZoom: 0,
  maxZoom: 8,
  pixelsPerBlockAtMaxZoom: 2,
  blocksPerTileAtMaxZoom: 128,
  tileOriginX: 0,
  tileOriginZ: 0,
  minSignedTileX: -183,
  minSignedTileY: -96,
  maxSignedTileX: 220,
  maxSignedTileY: 144,
  normalizedOffsetX: 183,
  normalizedOffsetY: 96,
};

export const testManifest: CartographyManifest = {
  crs: "cartography:mc-crs",
  world: "survival",
  defaultDimension: "minecraft:overworld",
  dimensions: ["minecraft:overworld", "minecraft:the_nether"],
  dataCoordinate: "minecraft-xz",
  tileCoordinateMode: "online-signed",
  tileGrid: testTileGrid,
  profile: "fast",
  tilesetVersion: "tileset-v1",
  rendererVersion: "cartography-v2",
  materialTableHash: "vanilla-map-colors",
  resourcePackHash: "default-pack",
  format: "png",
  quality: 85,
  tileUrlTemplate: "/tiles/raster/{world}/{dimension}/{profile}/{tilesetVersion}/{z}/x{x}/y{y}.png",
  markerMode: "off",
  markerPollIntervalMs: 2000,
  pendingTileRetryMs: 1250,
};
