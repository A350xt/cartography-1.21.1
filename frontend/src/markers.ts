import type { CartographyManifest, PlayerMarker } from "./types";

interface MarkerPollerOptions {
  fetchImpl?: (input: string, init?: RequestInit) => Promise<Response>;
  setIntervalImpl?: (callback: () => void, delayMs: number) => number;
  clearIntervalImpl?: (handle: number) => void;
}

/** Used only when the server did not state an interval. */
const FALLBACK_MARKER_POLL_INTERVAL_MS = 2000;

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
      // Markers default to off, and the server decides. Polling regardless would be a privacy leak
      // as much as wasted traffic.
      if (manifest.markerMode === "off") {
        return;
      }

      await pollOnce();
      // The server owns the cadence, since it also owns the publication delay policy.
      const intervalMs = manifest.markerPollIntervalMs > 0
        ? manifest.markerPollIntervalMs
        : FALLBACK_MARKER_POLL_INTERVAL_MS;
      intervalHandle = setIntervalImpl(() => {
        // A failed poll must not stop the timer; the next tick retries.
        void pollOnce().catch(() => undefined);
      }, intervalMs);
    },
    stop() {
      if (intervalHandle !== undefined) {
        clearIntervalImpl(intervalHandle);
        intervalHandle = undefined;
      }
    },
  };
}
