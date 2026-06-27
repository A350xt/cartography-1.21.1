import { describe, expect, it, vi } from "vitest";

import { loadManifest } from "./manifest";

describe("loadManifest", () => {
  it("parses the manifest payload from the server", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          tileSize: 256,
          minZoom: 0,
          maxZoom: 4,
          pixelsPerBlockAtMaxZoom: 1,
          dimensions: ["minecraft:overworld"],
          defaultDimension: "minecraft:overworld",
          tilesetVersion: "abc123",
          tileUrlTemplate: "/tiles/{tilesetVersion}/{dimension}/{z}/{x}/{y}.webp",
          markerMode: "off",
          pendingTileRetryMs: 1500,
        }),
        { status: 200 },
      ),
    );

    const manifest = await loadManifest(fetchMock);

    expect(fetchMock).toHaveBeenCalledWith("/manifest.json");
    expect(manifest.tilesetVersion).toBe("abc123");
    expect(manifest.defaultDimension).toBe("minecraft:overworld");
  });

  it("throws a readable error when the manifest request fails", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response("boom", { status: 503, statusText: "Unavailable" }));

    await expect(loadManifest(fetchMock)).rejects.toThrow(/503/);
  });
});
