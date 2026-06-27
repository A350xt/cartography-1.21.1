import { describe, expect, it, vi } from "vitest";

import { createMarkerPoller } from "./markers";
import type { CartographyManifest } from "./types";

const baseManifest: CartographyManifest = {
  tileSize: 256,
  minZoom: 0,
  maxZoom: 4,
  pixelsPerBlockAtMaxZoom: 1,
  dimensions: ["minecraft:overworld"],
  defaultDimension: "minecraft:overworld",
  tilesetVersion: "tileset-v1",
  tileUrlTemplate: "/tiles/{tilesetVersion}/{dimension}/{z}/{x}/{y}.webp",
  markerMode: "players",
  pendingTileRetryMs: 1250,
};

describe("createMarkerPoller", () => {
  it("does nothing when marker mode is off", async () => {
    const fetchMock = vi.fn();
    const intervalMock = vi.fn();

    const poller = createMarkerPoller(
      { ...baseManifest, markerMode: "off" },
      "minecraft:overworld",
      vi.fn(),
      {
        fetchImpl: fetchMock,
        setIntervalImpl: intervalMock,
      },
    );

    await poller.start();

    expect(fetchMock).not.toHaveBeenCalled();
    expect(intervalMock).not.toHaveBeenCalled();
  });

  it("polls markers when marker mode is enabled", async () => {
    const fetchMock = vi.fn().mockImplementation(async () =>
      new Response(
        JSON.stringify({
          players: [{ uuid: "1", name: "Player", dimension: "minecraft:overworld", x: 1, z: 2, updatedAt: 3 }],
        }),
        { status: 200 },
      ),
    );
    const setIntervalMock = vi.fn<(callback: () => void, delayMs: number) => number>((callback) => {
      callback();
      return 1;
    });
    const clearIntervalMock = vi.fn();
    const updateMock = vi.fn();

    const poller = createMarkerPoller(baseManifest, "minecraft:overworld", updateMock, {
      fetchImpl: fetchMock,
      setIntervalImpl: setIntervalMock,
      clearIntervalImpl: clearIntervalMock,
    });

    await poller.start();
    poller.stop();

    expect(fetchMock).toHaveBeenCalledWith("/markers?dimension=minecraft%3Aoverworld");
    expect(setIntervalMock).toHaveBeenCalledWith(expect.any(Function), 2000);
    expect(updateMock).toHaveBeenCalledWith([
      { uuid: "1", name: "Player", dimension: "minecraft:overworld", x: 1, z: 2, updatedAt: 3 },
    ]);
    expect(clearIntervalMock).toHaveBeenCalledWith(1);
  });
});
