import type { CartographyManifest } from "./types";

interface LoadTileOptions {
  fetchImpl?: (input: string, init?: RequestInit) => Promise<Response>;
  manifest: CartographyManifest;
  scheduleRetry?: (callback: () => void, delayMs: number) => void;
  createObjectUrl?: (blob: Blob) => string;
  revokeObjectUrl?: (url: string) => void;
}

/**
 * Builds a versioned tile URL (technical plan v2.0, section 7.1).
 *
 * The tileset version is part of the path, so a tile fetched under an old renderer or resource pack
 * can never be served from cache under the new one.
 */
export function buildTileUrl(
  manifest: CartographyManifest,
  dimension: string,
  z: number,
  x: number,
  y: number,
): string {
  return manifest.tileUrlTemplate
    .replace("{world}", encodeURIComponent(manifest.world))
    .replace("{tilesetVersion}", manifest.tilesetVersion)
    .replace("{profile}", manifest.profile)
    .replace("{dimension}", dimension)
    .replace("{z}", String(z))
    .replace("{x}", String(x))
    .replace("{y}", String(y));
}

/**
 * Loads a tile, retrying while the server reports it as still rendering.
 *
 * A miss returns a placeholder with 200 and a pending header rather than a 404, so the grid keeps a
 * valid image and simply refreshes once the render lands.
 */
export async function loadTileIntoImage(
  image: HTMLImageElement,
  url: string,
  options: LoadTileOptions,
): Promise<void> {
  const fetchImpl = options.fetchImpl ?? fetch;
  const scheduleRetry =
    options.scheduleRetry ?? ((callback, delayMs) => window.setTimeout(callback, delayMs));

  const response = await fetchImpl(url);
  if (!response.ok) {
    throw new Error(`Tile request failed with ${response.status}`);
  }

  // Resolved lazily rather than at the top: not every environment exposes the URL helpers, and a
  // failed request must surface its own error rather than one from setting up an unused default.
  const createObjectUrl = options.createObjectUrl ?? ((blob: Blob) => URL.createObjectURL(blob));
  const revokeObjectUrl = options.revokeObjectUrl ?? ((objectUrl: string) => URL.revokeObjectURL(objectUrl));

  const blob = await response.blob();
  const previousSrc = image.src;
  image.src = createObjectUrl(blob);

  // Object URLs pin their blob in memory until revoked. A pending tile can be replaced many times
  // while it renders, so releasing the old one keeps a long panning session from leaking.
  if (previousSrc.startsWith("blob:")) {
    revokeObjectUrl(previousSrc);
  }

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
