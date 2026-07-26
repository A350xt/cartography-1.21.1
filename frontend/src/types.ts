/** Tile grid contract from the server (technical plan v2.0, sections 4.4 and A.2). */
export interface TileGridManifest {
  mode: string;
  tileSize: number;
  minZoom: number;
  maxZoom: number;
  pixelsPerBlockAtMaxZoom: number;
  blocksPerTileAtMaxZoom: number;
  /** Display origin. Data coordinates are never stored relative to it. */
  tileOriginX: number;
  tileOriginZ: number;
  minSignedTileX: number;
  minSignedTileY: number;
  maxSignedTileX: number;
  maxSignedTileY: number;
  normalizedOffsetX: number;
  normalizedOffsetY: number;
}

export interface CartographyManifest {
  crs: string;
  world: string;
  defaultDimension: string;
  dimensions: string[];
  dataCoordinate: string;
  tileCoordinateMode: string;
  tileGrid: TileGridManifest;
  profile: string;
  tilesetVersion: string;
  rendererVersion: string;
  materialTableHash: string;
  resourcePackHash: string;
  format: string;
  quality: number;
  tileUrlTemplate: string;
  markerMode: string;
  markerPollIntervalMs: number;
  pendingTileRetryMs: number;
}

export interface PlayerMarker {
  uuid: string;
  name: string;
  dimension: string;
  /** Minecraft block X. Never offset by the display origin. */
  x: number;
  /** Minecraft block Z. */
  z: number;
  updatedAt: number;
}
