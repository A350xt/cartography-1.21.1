import { describe, expect, it } from "vitest";

import { blockToPixel, blockToSignedTile, blocksPerTile, pixelToBlock, resolutions, signedExtent, toSignedTileY } from "./crs";
import { testTileGrid } from "./testManifest";

/**
 * The technical plan lists the coordinate round-trip as a mandatory invariant. The failure it guards
 * against is features drifting off the basemap and negative regions rendering into the wrong tile.
 */
describe("mc-crs transform", () => {
  it("round-trips every probe from block to pixel and back", () => {
    const probes: [number, number][] = [
      [0, 0],
      [1, 1],
      [-1, -1],
      [127, 127],
      [128, 128],
      [-128, -128],
      [-129, -129],
      [513, -129],
      [-10000, -10000],
      [23456, -7891],
    ];

    for (const [blockX, blockZ] of probes) {
      const [pixelX, pixelY] = blockToPixel(testTileGrid, blockX, blockZ);
      const [roundTrippedX, roundTrippedZ] = pixelToBlock(testTileGrid, pixelX, pixelY);

      expect(roundTrippedX).toBe(blockX);
      expect(roundTrippedZ).toBe(blockZ);
    }
  });

  it("negates the Z axis because Minecraft Z grows south and map Y grows north", () => {
    const [, northPixelY] = blockToPixel(testTileGrid, 0, -100);
    const [, southPixelY] = blockToPixel(testTileGrid, 0, 100);

    expect(northPixelY).toBeGreaterThan(southPixelY);
  });

  it("honours a tile origin away from the world origin without moving data", () => {
    const shifted = { ...testTileGrid, tileOriginX: -512, tileOriginZ: 384 };

    expect(blockToPixel(shifted, -512, 384)).toEqual([0, -0]);
    expect(pixelToBlock(shifted, 0, 0)).toEqual([-512, 384]);
  });

  it("places negative blocks in the tile below the origin, not tile zero", () => {
    const maxZoom = testTileGrid.maxZoom;

    expect(blockToSignedTile(testTileGrid, maxZoom, 0, 0)).toEqual([0, 0]);
    expect(blockToSignedTile(testTileGrid, maxZoom, 127, 127)).toEqual([0, 0]);
    expect(blockToSignedTile(testTileGrid, maxZoom, -1, -1)).toEqual([-1, -1]);
    expect(blockToSignedTile(testTileGrid, maxZoom, -128, -128)).toEqual([-1, -1]);
    expect(blockToSignedTile(testTileGrid, maxZoom, -129, -129)).toEqual([-2, -2]);
  });

  it("doubles the block footprint of a tile at each zoom step out", () => {
    expect(blocksPerTile(testTileGrid, 8)).toBe(128);
    expect(blocksPerTile(testTileGrid, 7)).toBe(256);
    expect(blocksPerTile(testTileGrid, 0)).toBe(128 * 2 ** 8);
  });

  it("produces one resolution per zoom level, finest last", () => {
    const levels = resolutions(testTileGrid);

    expect(levels).toHaveLength(testTileGrid.maxZoom - testTileGrid.minZoom + 1);
    expect(levels[levels.length - 1]).toBe(1);
    expect(levels[0]).toBe(2 ** testTileGrid.maxZoom);
  });

  it("derives a pixel extent that covers the rendered signed tile range", () => {
    const [minX, minY, maxX, maxY] = signedExtent(testTileGrid);

    expect(minX).toBeLessThan(maxX);
    expect(minY).toBeLessThan(maxY);

    // The north-west corner of the lowest signed tile must sit inside the extent.
    const [cornerX, cornerY] = blockToPixel(
      testTileGrid,
      testTileGrid.minSignedTileX * testTileGrid.blocksPerTileAtMaxZoom,
      testTileGrid.minSignedTileY * testTileGrid.blocksPerTileAtMaxZoom,
    );
    expect(cornerX).toBeGreaterThanOrEqual(minX);
    expect(cornerY).toBeLessThanOrEqual(maxY);
  });

  it("maps OpenLayers tile Y onto the signed tile the server addresses", () => {
    // OpenLayers counts Y downward from the extent top; the server uses signed coordinates. An
    // off-by-one here shifts the entire map by one tile.
    expect(toSignedTileY(-1)).toBe(0);
    expect(toSignedTileY(0)).toBe(-1);
    expect(toSignedTileY(-13)).toBe(12);
  });
});
