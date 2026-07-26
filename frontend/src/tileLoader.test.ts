import { describe, expect, it, vi } from "vitest";

import { buildTileUrl, loadTileIntoImage } from "./tileLoader";
import { testManifest } from "./testManifest";

describe("tileLoader", () => {
  it("builds versioned tile urls from the manifest template", () => {
    expect(buildTileUrl(testManifest, "minecraft:overworld", 8, 3, 7)).toBe(
      "/tiles/raster/survival/minecraft:overworld/fast/tileset-v1/8/x3/y7.png",
    );
  });

  it("encodes negative tile coordinates unambiguously", () => {
    // The x/y prefixes are what keep a leading minus from reading as a path segment.
    expect(buildTileUrl(testManifest, "minecraft:overworld", 8, -3, -12)).toBe(
      "/tiles/raster/survival/minecraft:overworld/fast/tileset-v1/8/x-3/y-12.png",
    );
  });

  it("puts the tileset version in the path so stale tiles cannot be reused", () => {
    const url = buildTileUrl(testManifest, "minecraft:overworld", 8, 0, 0);
    const rerendered = buildTileUrl({ ...testManifest, tilesetVersion: "tileset-v2" }, "minecraft:overworld", 8, 0, 0);

    expect(url).toContain("tileset-v1");
    expect(rerendered).toContain("tileset-v2");
    expect(url).not.toBe(rerendered);
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
    const revokeMock = vi.fn();

    await loadTileIntoImage(image, "/tiles/raster/survival/minecraft:overworld/fast/tileset-v1/8/x0/y0.png", {
      fetchImpl: fetchMock,
      manifest: testManifest,
      scheduleRetry: scheduleMock,
      createObjectUrl: objectUrlFactory,
      revokeObjectUrl: revokeMock,
    });

    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(scheduleMock).toHaveBeenCalledWith(expect.any(Function), 2000);
    expect(image.src).toContain("blob:tile-ready");
  });

  it("stops retrying once the tile is ready", async () => {
    const image = document.createElement("img");
    const fetchMock = vi.fn().mockResolvedValue(new Response(new Blob(["tile"]), { status: 200 }));
    const scheduleMock = vi.fn();

    await loadTileIntoImage(image, "/tiles/raster/survival/minecraft:overworld/fast/tileset-v1/8/x0/y0.png", {
      fetchImpl: fetchMock,
      manifest: testManifest,
      scheduleRetry: scheduleMock,
      createObjectUrl: vi.fn().mockReturnValue("blob:done"),
      revokeObjectUrl: vi.fn(),
    });

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(scheduleMock).not.toHaveBeenCalled();
  });

  it("falls back to the manifest retry interval when the server sends no Retry-After", async () => {
    const image = document.createElement("img");
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(new Blob(["tile"]), {
          status: 200,
          headers: { "X-Cartography-Tile-State": "pending" },
        }),
      )
      .mockResolvedValueOnce(new Response(new Blob(["tile"]), { status: 200 }));
    const scheduleMock = vi.fn<(callback: () => void, delayMs: number) => void>((callback) => callback());

    await loadTileIntoImage(image, "/tiles/raster/survival/minecraft:overworld/fast/tileset-v1/8/x0/y0.png", {
      fetchImpl: fetchMock,
      manifest: testManifest,
      scheduleRetry: scheduleMock,
      createObjectUrl: vi.fn().mockReturnValue("blob:pending"),
      revokeObjectUrl: vi.fn(),
    });

    expect(scheduleMock).toHaveBeenCalledWith(expect.any(Function), testManifest.pendingTileRetryMs);
  });

  it("releases the previous object url so panning does not leak blobs", async () => {
    const image = document.createElement("img");
    image.src = "blob:previous-tile";
    const revokeMock = vi.fn();

    await loadTileIntoImage(image, "/tiles/raster/survival/minecraft:overworld/fast/tileset-v1/8/x0/y0.png", {
      fetchImpl: vi.fn().mockResolvedValue(new Response(new Blob(["tile"]), { status: 200 })),
      manifest: testManifest,
      createObjectUrl: vi.fn().mockReturnValue("blob:new-tile"),
      revokeObjectUrl: revokeMock,
    });

    expect(revokeMock).toHaveBeenCalledWith("blob:previous-tile");
  });

  it("raises a readable error when a tile request fails", async () => {
    await expect(
      loadTileIntoImage(document.createElement("img"), "/tiles/raster/x", {
        fetchImpl: vi.fn().mockResolvedValue(new Response("boom", { status: 500 })),
        manifest: testManifest,
      }),
    ).rejects.toThrow(/500/);
  });
});
