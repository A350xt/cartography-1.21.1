export interface CartographyManifest {
  tileSize: number;
  minZoom: number;
  maxZoom: number;
  pixelsPerBlockAtMaxZoom: number;
  dimensions: string[];
  defaultDimension: string;
  tilesetVersion: string;
  tileUrlTemplate: string;
  markerMode: string;
  pendingTileRetryMs: number;
}

export interface PlayerMarker {
  uuid: string;
  name: string;
  dimension: string;
  x: number;
  z: number;
  updatedAt: number;
}
