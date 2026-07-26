import Feature from "ol/Feature";
import Map from "ol/Map";
import type ImageTile from "ol/ImageTile";
import View from "ol/View";
import Point from "ol/geom/Point";
import TileLayer from "ol/layer/Tile";
import VectorLayer from "ol/layer/Vector";
import Projection from "ol/proj/Projection";
import VectorSource from "ol/source/Vector";
import XYZ from "ol/source/XYZ";
import TileGrid from "ol/tilegrid/TileGrid";
import { Circle, Fill, Stroke, Style, Text } from "ol/style";

import { blockToPixel, pixelToBlock, resolutions, signedExtent, toSignedTileY } from "./crs";
import { loadManifest } from "./manifest";
import { createMarkerPoller } from "./markers";
import { buildTileUrl, loadTileIntoImage } from "./tileLoader";
import type { CartographyManifest, PlayerMarker } from "./types";

// Keyed by label so panning does not rebuild an identical Style for every marker on every poll.
// Note this is the global Map, not OpenLayers' Map imported above.
const markerStyleCache = new globalThis.Map<string, Style>();

function markerStyle(name: string): Style {
  let style = markerStyleCache.get(name);
  if (!style) {
    style = new Style({
      image: new Circle({
        radius: 6,
        fill: new Fill({ color: "#f3c969" }),
        stroke: new Stroke({ color: "#2f2418", width: 2 }),
      }),
      text: new Text({
        text: name,
        offsetY: -14,
        font: "12px system-ui, sans-serif",
        fill: new Fill({ color: "#f6f3ec" }),
        stroke: new Stroke({ color: "#1b1712", width: 3 }),
      }),
    });
    markerStyleCache.set(name, style);
  }
  return style;
}

export async function bootstrapApp(root: HTMLElement): Promise<void> {
  root.innerHTML = `
    <div class="shell">
      <header class="topbar">
        <div>
          <p class="eyebrow">Lie-DownCraft</p>
          <h1>Cartography</h1>
        </div>
        <div class="controls">
          <label>
            <span>Dimension</span>
            <select id="dimension-select"></select>
          </label>
        </div>
      </header>
      <section class="status-card">
        <p id="status-text">Loading manifest...</p>
        <p id="coordinate-readout" class="coordinates"></p>
      </section>
      <main class="map-frame">
        <div id="map"></div>
      </main>
    </div>
  `;

  const statusText = root.querySelector<HTMLElement>("#status-text");
  const coordinateReadout = root.querySelector<HTMLElement>("#coordinate-readout");
  const dimensionSelect = root.querySelector<HTMLSelectElement>("#dimension-select");
  const mapElement = root.querySelector<HTMLElement>("#map");

  if (!statusText || !coordinateReadout || !dimensionSelect || !mapElement) {
    throw new Error("Cartography app shell failed to initialize");
  }

  try {
    const manifest = await loadManifest();
    statusText.textContent = `Tileset ${manifest.tilesetVersion} ready`;
    initializeMap(manifest, dimensionSelect, mapElement, statusText, coordinateReadout);
  } catch (error) {
    const message = error instanceof Error ? error.message : "Unknown error";
    statusText.textContent = `Manifest load failed: ${message}`;
    statusText.dataset.state = "error";
  }
}

function initializeMap(
  manifest: CartographyManifest,
  dimensionSelect: HTMLSelectElement,
  mapElement: HTMLElement,
  statusText: HTMLElement,
  coordinateReadout: HTMLElement,
) {
  const grid = manifest.tileGrid;
  let currentDimension = manifest.defaultDimension;
  let markerPoller: ReturnType<typeof createMarkerPoller> | undefined;

  for (const dimension of manifest.dimensions) {
    const option = document.createElement("option");
    option.value = dimension;
    option.textContent = dimension;
    option.selected = dimension === manifest.defaultDimension;
    dimensionSelect.append(option);
  }

  // A custom projection in max-zoom pixel units. Minecraft coordinates are not geographic, so no
  // built-in projection applies; declaring our own is what keeps features aligned with the basemap.
  const extent = signedExtent(grid);
  const projection = new Projection({
    code: manifest.crs,
    units: "pixels",
    extent,
  });

  const tileGrid = new TileGrid({
    // Origin is the tile-grid origin in pixel space, which is where signed tile (0,0) begins.
    origin: [0, 0],
    resolutions: resolutions(grid),
    tileSize: grid.tileSize,
  });

  const rasterSource = new XYZ({
    projection,
    tileGrid,
    // Tiles are already versioned by URL, so the browser cache is safe and correct.
    cacheSize: 512,
    tileUrlFunction: (tileCoord) => {
      const [z, x, y] = tileCoord;
      return buildTileUrl(manifest, currentDimension, z, x, toSignedTileY(y));
    },
    tileLoadFunction: (tile, src) => {
      const image = (tile as ImageTile).getImage() as HTMLImageElement;
      void loadTileIntoImage(image, src, { manifest }).catch(() => {
        statusText.textContent = `Tile request failed for ${currentDimension}`;
      });
    },
  });

  const markerSource = new VectorSource();
  const markerLayer = new VectorLayer({
    source: markerSource,
    style: (feature) => markerStyle(String(feature.get("name") ?? "")),
    visible: manifest.markerMode !== "off",
  });

  const map = new Map({
    target: mapElement,
    layers: [new TileLayer({ source: rasterSource }), markerLayer],
    view: new View({
      projection,
      extent,
      center: blockToPixel(grid, grid.tileOriginX, grid.tileOriginZ),
      zoom: grid.maxZoom - grid.minZoom,
      resolutions: resolutions(grid),
    }),
  });

  // Report the cursor position in raw Minecraft coordinates, which is the only coordinate space a
  // player can act on.
  map.on("pointermove", (event) => {
    const [blockX, blockZ] = pixelToBlock(grid, event.coordinate[0], event.coordinate[1]);
    coordinateReadout.textContent = `X ${Math.floor(blockX)}  Z ${Math.floor(blockZ)}`;
  });

  const renderMarkers = (markers: PlayerMarker[]) => {
    markerSource.clear();
    for (const marker of markers) {
      markerSource.addFeature(
        new Feature({
          geometry: new Point(blockToPixel(grid, marker.x, marker.z)),
          name: marker.name,
        }),
      );
    }
  };

  const restartMarkerPolling = async () => {
    markerPoller?.stop();
    markerPoller = createMarkerPoller(manifest, currentDimension, renderMarkers);
    await markerPoller.start();
  };

  void restartMarkerPolling();

  dimensionSelect.addEventListener("change", () => {
    currentDimension = dimensionSelect.value;
    statusText.textContent = `Browsing ${currentDimension}`;
    rasterSource.refresh();
    void restartMarkerPolling();
  });

  statusText.textContent = `Browsing ${currentDimension}`;
}
