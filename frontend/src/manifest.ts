import type { CartographyManifest } from "./types";

export type FetchLike = (input: string, init?: RequestInit) => Promise<Response>;

export async function loadManifest(fetchImpl: FetchLike = fetch): Promise<CartographyManifest> {
  const response = await fetchImpl("/manifest.json");
  if (!response.ok) {
    throw new Error(`Manifest request failed with ${response.status} ${response.statusText}`);
  }

  return (await response.json()) as CartographyManifest;
}
