// Room-set math per docs/PROTOCOL.md §3 (pure; the network side lives in services/).
import { gridDisk, latLngToCell } from "h3-js";
import { PUBLISH_MAX_CELLS, PUBLISH_STEP_M, ROOM_RES, senderRadiusM } from "./constants";
import { destination } from "./geo";

export function roomCell(lat: number, lng: number): string {
  return latLngToCell(lat, lng, ROOM_RES);
}

/** Receiver subscribe set: own res-7 cell + k-ring 1 (7 cells). */
export function subscribeCells(lat: number, lng: number): string[] {
  return gridDisk(roomCell(lat, lng), 1);
}

/**
 * Sender publish set: own cell + res-7 cells sampled along the forward ray out to
 * senderRadius(speed), deduped, capped.
 */
export function publishCells(
  lat: number,
  lng: number,
  heading: number,
  speedMps: number,
): string[] {
  const cells = new Set<string>([roomCell(lat, lng)]);
  const reach = senderRadiusM(speedMps);
  for (let d = PUBLISH_STEP_M; d <= reach && cells.size < PUBLISH_MAX_CELLS; d += PUBLISH_STEP_M) {
    const p = destination(lat, lng, heading, d);
    cells.add(roomCell(p.lat, p.lng));
  }
  return [...cells].slice(0, PUBLISH_MAX_CELLS);
}

export function roomTopic(cell: string): string {
  return `room:${cell}`;
}
