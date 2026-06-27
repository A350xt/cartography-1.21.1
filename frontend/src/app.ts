import Feature from "ol/Feature";
import Map from "ol/Map";
import View from "ol/View";
import Point from "ol/geom/Point";
import TileLayer from "ol/layer/Tile";
import VectorLayer from "ol/layer/Vector";
import Projection from "ol/proj/Projection";
import VectorSource from "ol/source/Vector";
import XYZ from "ol/source/XYZ";
import { Circle, Fill, Stroke, Style } from "ol/style";

import { loadManifest } from "./manifest";
import { createMarkerPoller } from "./markers";
import { buildTileUrl, loadTileIntoImage } from "./tileLoader";
import type { CartographyManifest, PlayerMarker } from "./types";

const markerStyle = new Style({
  image: new Circle({
    radius: 6,
    fill: new Fill({ color: "#f3c969" }),
    stroke: new Stroke({ color: "#2f2418", width: 2 }),
  }),
});

const projection = new Projection({
  code: "CARTOGRAPHY",
  units: "pixels",
  extent: [-1000000, -1000000, 1000000, 1000000],
});

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
      </section>
      <main class="map-frame">
        <div id="map"></div>
      </main>
    </div>
  `;

  const statusText = root.querySelector<HTMLElement>("#status-text");
  const dimensionSelect = root.querySelector<HTMLSelectElement>("#dimension-select");
  const mapElement = root.querySelector<HTMLElement>("#map");

  if (!statusText || !dimensionSelect || !mapElement) {
    throw new Error("Cartography app shell failed to initialize");
  }

  try {
    const manifest = await loadManifest();
    statusText.textContent = `Tileset ${manifest.tilesetVersion} ready`;
    initializeMap(manifest, dimensionSelect, mapElement, statusText);
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
) {
  let currentDimension = manifest.defaultDimension;
  let markerPoller: ReturnType<typeof createMarkerPoller> | undefined;

  for (const dimension of manifest.dimensions) {
    const option = document.createElement("option");
    option.value = dimension;
    option.textContent = dimension;
    option.selected = dimension === manifest.defaultDimension;
    dimensionSelect.append(option);
  }

  const rasterSource = new XYZ({
    minZoom: manifest.minZoom,
    maxZoom: manifest.maxZoom,
    projection,
    tileUrlFunction: (tileCoord) => {
      const [z, x, invertedY] = tileCoord;
      const y = -invertedY - 1;
      return buildTileUrl(manifest, currentDimension, z, x, y);
    },
    tileLoadFunction: (tile, src) => {
      const image = tile.getImage() as HTMLImageElement;
      statusText.textContent = `Loading ${currentDimension} tiles...`;
      void loadTileIntoImage(image, src, { manifest }).then(() => {
        statusText.textContent = `Browsing ${currentDimension}`;
      });
    },
  });

  const markerSource = new VectorSource();
  const markerLayer = new VectorLayer({
    source: markerSource,
    style: markerStyle,
    visible: manifest.markerMode !== "off",
  });

  const map = new Map({
    target: mapElement,
    layers: [
      new TileLayer({
        source: rasterSource,
      }),
      markerLayer,
    ],
    view: new View({
      projection,
      center: [0, 0],
      zoom: manifest.maxZoom,
      minZoom: manifest.minZoom,
      maxZoom: manifest.maxZoom,
      multiWorld: true,
    }),
  });

  const renderMarkers = (markers: PlayerMarker[]) => {
    markerSource.clear();
    for (const marker of markers) {
      markerSource.addFeature(
        new Feature({
          geometry: new Point([marker.x, marker.z]),
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

  map.renderSync();
}
