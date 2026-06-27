import type { CartographyManifest, PlayerMarker } from "./types";

interface MarkerPollerOptions {
  fetchImpl?: (input: string, init?: RequestInit) => Promise<Response>;
  setIntervalImpl?: (callback: () => void, delayMs: number) => number;
  clearIntervalImpl?: (handle: number) => void;
}

const DEFAULT_MARKER_POLL_INTERVAL_MS = 2000;

export function createMarkerPoller(
  manifest: CartographyManifest,
  dimension: string,
  onMarkers: (markers: PlayerMarker[]) => void,
  options: MarkerPollerOptions = {},
) {
  const fetchImpl = options.fetchImpl ?? fetch;
  const setIntervalImpl = options.setIntervalImpl ?? ((callback, delayMs) => window.setInterval(callback, delayMs));
  const clearIntervalImpl = options.clearIntervalImpl ?? ((handle) => window.clearInterval(handle));
  let intervalHandle: number | undefined;

  const pollOnce = async () => {
    const response = await fetchImpl(`/markers?dimension=${encodeURIComponent(dimension)}`);
    if (!response.ok) {
      throw new Error(`Marker request failed with ${response.status}`);
    }
    const payload = (await response.json()) as { players: PlayerMarker[] };
    onMarkers(payload.players);
  };

  return {
    async start() {
      if (manifest.markerMode === "off") {
        return;
      }

      await pollOnce();
      intervalHandle = setIntervalImpl(() => {
        void pollOnce();
      }, DEFAULT_MARKER_POLL_INTERVAL_MS);
    },
    stop() {
      if (intervalHandle !== undefined) {
        clearIntervalImpl(intervalHandle);
        intervalHandle = undefined;
      }
    },
  };
}
