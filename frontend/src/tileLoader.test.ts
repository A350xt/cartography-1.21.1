import { describe, expect, it, vi } from "vitest";

import { buildTileUrl, loadTileIntoImage } from "./tileLoader";
import type { CartographyManifest } from "./types";

const manifest: CartographyManifest = {
  tileSize: 256,
  minZoom: 0,
  maxZoom: 4,
  pixelsPerBlockAtMaxZoom: 1,
  dimensions: ["minecraft:overworld"],
  defaultDimension: "minecraft:overworld",
  tilesetVersion: "tileset-v1",
  tileUrlTemplate: "/tiles/{tilesetVersion}/{dimension}/{z}/{x}/{y}.webp",
  markerMode: "off",
  pendingTileRetryMs: 1250,
};

describe("tileLoader", () => {
  it("builds versioned tile urls from the manifest template", () => {
    expect(buildTileUrl(manifest, "minecraft:overworld", 4, 3, 7)).toBe(
      "/tiles/tileset-v1/minecraft:overworld/4/3/7.webp",
    );
  });

  it("retries pending tiles based on response headers", async () => {
    const image = document.createElement("img");
    const blob = new Blob(["tile"]);
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(blob, {
          status: 200,
          headers: {
            "X-Cartography-Tile-State": "pending",
            "Retry-After": "2",
          },
        }),
      )
      .mockResolvedValueOnce(new Response(blob, { status: 200 }));
    const scheduleMock = vi.fn<(callback: () => void, delayMs: number) => void>((callback) => callback());
    const objectUrlFactory = vi.fn().mockReturnValue("blob:tile-ready");

    await loadTileIntoImage(image, "/tiles/tileset-v1/minecraft:overworld/4/0/0.webp", {
      fetchImpl: fetchMock,
      manifest,
      scheduleRetry: scheduleMock,
      createObjectUrl: objectUrlFactory,
    });

    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(scheduleMock).toHaveBeenCalledWith(expect.any(Function), 2000);
    expect(image.src).toContain("blob:tile-ready");
  });
});
