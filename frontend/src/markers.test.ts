import { describe, expect, it, vi } from "vitest";

import { createMarkerPoller } from "./markers";
import { testManifest } from "./testManifest";

const enabledManifest = { ...testManifest, markerMode: "exact" };

describe("createMarkerPoller", () => {
  it("does nothing when marker mode is off", async () => {
    const fetchMock = vi.fn();
    const intervalMock = vi.fn();

    // Markers default to off. Polling anyway would be a privacy leak, not just wasted traffic.
    const poller = createMarkerPoller(testManifest, "minecraft:overworld", vi.fn(), {
      fetchImpl: fetchMock,
      setIntervalImpl: intervalMock,
    });

    await poller.start();

    expect(fetchMock).not.toHaveBeenCalled();
    expect(intervalMock).not.toHaveBeenCalled();
  });

  it("polls markers when marker mode is enabled", async () => {
    const player = { uuid: "1", name: "Player", dimension: "minecraft:overworld", x: 1, z: 2, updatedAt: 3 };
    const fetchMock = vi
      .fn()
      .mockImplementation(async () => new Response(JSON.stringify({ players: [player] }), { status: 200 }));
    const setIntervalMock = vi.fn<(callback: () => void, delayMs: number) => number>((callback) => {
      callback();
      return 1;
    });
    const clearIntervalMock = vi.fn();
    const updateMock = vi.fn();

    const poller = createMarkerPoller(enabledManifest, "minecraft:overworld", updateMock, {
      fetchImpl: fetchMock,
      setIntervalImpl: setIntervalMock,
      clearIntervalImpl: clearIntervalMock,
    });

    await poller.start();
    poller.stop();

    expect(fetchMock).toHaveBeenCalledWith("/markers?dimension=minecraft%3Aoverworld");
    expect(updateMock).toHaveBeenCalledWith([player]);
    expect(clearIntervalMock).toHaveBeenCalledWith(1);
  });

  it("uses the poll interval the server published", async () => {
    // The server owns the cadence because it also owns the publication delay policy.
    const setIntervalMock = vi.fn<(callback: () => void, delayMs: number) => number>(() => 1);

    const poller = createMarkerPoller(
      { ...enabledManifest, markerPollIntervalMs: 5000 },
      "minecraft:overworld",
      vi.fn(),
      {
        fetchImpl: vi.fn().mockResolvedValue(new Response(JSON.stringify({ players: [] }), { status: 200 })),
        setIntervalImpl: setIntervalMock,
      },
    );

    await poller.start();

    expect(setIntervalMock).toHaveBeenCalledWith(expect.any(Function), 5000);
  });

  it("keeps polling after a failed request", async () => {
    let callCount = 0;
    const fetchMock = vi.fn().mockImplementation(async () => {
      callCount += 1;
      // First call succeeds so start() resolves; the scheduled call then fails.
      return callCount === 1
        ? new Response(JSON.stringify({ players: [] }), { status: 200 })
        : new Response("boom", { status: 503 });
    });
    let scheduled: (() => void) | undefined;
    const setIntervalMock = vi.fn<(callback: () => void, delayMs: number) => number>((callback) => {
      scheduled = callback;
      return 1;
    });

    const poller = createMarkerPoller(enabledManifest, "minecraft:overworld", vi.fn(), {
      fetchImpl: fetchMock,
      setIntervalImpl: setIntervalMock,
    });

    await poller.start();

    // A transient server error must not tear down the timer.
    expect(() => scheduled?.()).not.toThrow();
    poller.stop();
  });
});
