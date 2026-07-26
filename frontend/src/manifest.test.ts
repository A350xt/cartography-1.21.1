import { describe, expect, it, vi } from "vitest";

import { loadManifest } from "./manifest";
import { testManifest } from "./testManifest";

describe("loadManifest", () => {
  it("parses the manifest payload from the server", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify(testManifest), { status: 200 }));

    const manifest = await loadManifest(fetchMock);

    expect(fetchMock).toHaveBeenCalledWith("/manifest.json");
    expect(manifest.tilesetVersion).toBe("tileset-v1");
    expect(manifest.defaultDimension).toBe("minecraft:overworld");
  });

  it("exposes the tile grid the client needs to place features on the basemap", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify(testManifest), { status: 200 }));

    const manifest = await loadManifest(fetchMock);

    // Without these a client cannot map a Minecraft coordinate onto a tile pixel.
    expect(manifest.crs).toBe("cartography:mc-crs");
    expect(manifest.dataCoordinate).toBe("minecraft-xz");
    expect(manifest.tileGrid.pixelsPerBlockAtMaxZoom).toBeGreaterThan(0);
    expect(manifest.tileGrid.blocksPerTileAtMaxZoom).toBe(128);
    expect(manifest.tileGrid.tileOriginX).toBe(0);
    expect(manifest.tileGrid.tileOriginZ).toBe(0);
  });

  it("throws a readable error when the manifest request fails", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response("boom", { status: 503, statusText: "Unavailable" }));

    await expect(loadManifest(fetchMock)).rejects.toThrow(/503/);
  });
});
