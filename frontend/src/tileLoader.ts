import type { CartographyManifest } from "./types";

interface LoadTileOptions {
  fetchImpl?: (input: string, init?: RequestInit) => Promise<Response>;
  manifest: CartographyManifest;
  scheduleRetry?: (callback: () => void, delayMs: number) => void;
  createObjectUrl?: (blob: Blob) => string;
}

export function buildTileUrl(
  manifest: CartographyManifest,
  dimension: string,
  z: number,
  x: number,
  y: number,
): string {
  return manifest.tileUrlTemplate
    .replace("{tilesetVersion}", manifest.tilesetVersion)
    .replace("{dimension}", dimension)
    .replace("{z}", String(z))
    .replace("{x}", String(x))
    .replace("{y}", String(y));
}

export async function loadTileIntoImage(
  image: HTMLImageElement,
  url: string,
  options: LoadTileOptions,
): Promise<void> {
  const fetchImpl = options.fetchImpl ?? fetch;
  const scheduleRetry = options.scheduleRetry ?? ((callback, delayMs) => window.setTimeout(callback, delayMs));
  const createObjectUrl = options.createObjectUrl ?? URL.createObjectURL.bind(URL);

  const response = await fetchImpl(url);
  if (!response.ok) {
    throw new Error(`Tile request failed with ${response.status}`);
  }

  const blob = await response.blob();
  image.src = createObjectUrl(blob);

  if (response.headers.get("X-Cartography-Tile-State") === "pending") {
    const retryAfterSeconds = Number.parseInt(response.headers.get("Retry-After") ?? "", 10);
    const retryDelayMs = Number.isFinite(retryAfterSeconds)
      ? retryAfterSeconds * 1000
      : options.manifest.pendingTileRetryMs;
    scheduleRetry(() => {
      void loadTileIntoImage(image, url, options);
    }, retryDelayMs);
  }
}
