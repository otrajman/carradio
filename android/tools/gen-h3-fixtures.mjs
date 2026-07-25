#!/usr/bin/env node
// gen-h3-fixtures.mjs
// Generates app/src/test/resources/h3_fixtures.json from official h3-js v4
// (uses the copy installed in pwa/node_modules). The fixtures validate the
// pure-Kotlin H3 port (H3Lite.kt) against the reference implementation:
//
//   - latLngToCell: seeded-random global points at res 7/8/9 plus extreme
//     cases (poles, equator, antimeridian) across res 0-15.
//   - gridDisk: k=1..3 at res 7/8/9 for every res-7/8/9 pentagon cell, every
//     pentagon neighbor, and seeded-random hexagon cells. Expected cell lists
//     are sorted — H3Lite's BFS ordering differs from h3-js spiral ordering,
//     so disks are compared as sets.
//
// Run from the repo root (node must resolve h3-js from pwa/node_modules):
//   node android/tools/gen-h3-fixtures.mjs

import { createRequire } from "node:module";
import { writeFileSync, mkdirSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));
const require = createRequire(join(here, "../../pwa/package.json"));
const h3 = require("h3-js");

// Deterministic PRNG (mulberry32) so regeneration is reproducible.
function mulberry32(seed) {
  let a = seed >>> 0;
  return function () {
    a |= 0;
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
const rand = mulberry32(0xca44ad10);

// Uniform point on the sphere (degrees).
function randomPoint() {
  const lat = (Math.asin(2 * rand() - 1) * 180) / Math.PI;
  const lng = rand() * 360 - 180;
  return { lat, lng };
}

// --- latLngToCell fixtures ---------------------------------------------------

const latLngCases = [];
function addLatLng(lat, lng, res) {
  latLngCases.push({ lat, lng, res, expected: h3.latLngToCell(lat, lng, res) });
}

// 2,400 random global points spanning res 7-9 (800 each).
for (const res of [7, 8, 9]) {
  for (let i = 0; i < 800; i++) {
    const { lat, lng } = randomPoint();
    addLatLng(lat, lng, res);
  }
}

// Extremes: poles, near-poles, equator, antimeridian — across res 0-15.
const extremePoints = [
  [90, 0], [90, 45], [90, -122.5], [-90, 0], [-90, 45], [-90, -122.5],
  [89.9999999, 0], [-89.9999999, 179.5], [89.999, -179.999], [-89.999, 0.001],
  [0, 180], [0, -180], [0.000001, 179.9999999], [-0.000001, -179.9999999],
  [45.5, 180], [-45.5, -180], [37.7749, -122.4194], [-33.8688, 151.2093],
  [0, 0], [1e-9, -1e-9],
];
for (let res = 0; res <= 15; res++) {
  for (const [lat, lng] of extremePoints) {
    addLatLng(lat, lng, res);
  }
}

// --- gridDisk fixtures -------------------------------------------------------

const diskCases = [];
const seenDisk = new Set();
function addDisk(cell, k) {
  const key = `${cell}:${k}`;
  if (seenDisk.has(key)) return;
  seenDisk.add(key);
  diskCases.push({ cell, k, expected: [...h3.gridDisk(cell, k)].sort() });
}

// Every res-7/8/9 pentagon, and every neighbor of each pentagon, at k=1..3.
let pentagonCells = 0;
for (const res of [7, 8, 9]) {
  const pentagons = h3.getPentagons(res);
  if (pentagons.length !== 12) throw new Error(`expected 12 pentagons at res ${res}`);
  pentagonCells += pentagons.length;
  for (const pent of pentagons) {
    for (const k of [1, 2, 3]) addDisk(pent, k);
    for (const neighbor of h3.gridDisk(pent, 1)) {
      if (neighbor === pent) continue;
      for (const k of [1, 2, 3]) addDisk(neighbor, k);
    }
  }
}

// Random hexagon cells at res 7-9, k=1..3.
for (const res of [7, 8, 9]) {
  for (let i = 0; i < 40; i++) {
    const { lat, lng } = randomPoint();
    const cell = h3.latLngToCell(lat, lng, res);
    for (const k of [1, 2, 3]) addDisk(cell, k);
  }
}

// --- write -------------------------------------------------------------------

const out = {
  meta: {
    generator: "android/tools/gen-h3-fixtures.mjs",
    h3js: require("h3-js/package.json").version,
    seed: "0xca44ad10",
    latLngToCellCount: latLngCases.length,
    gridDiskCount: diskCases.length,
    pentagonCellsCovered: pentagonCells,
  },
  latLngToCell: latLngCases,
  gridDisk: diskCases,
};

const dest = join(here, "../app/src/test/resources/h3_fixtures.json");
mkdirSync(dirname(dest), { recursive: true });
writeFileSync(dest, JSON.stringify(out));
console.log(
  `wrote ${dest}: ${latLngCases.length} latLngToCell cases, ` +
    `${diskCases.length} gridDisk cases (h3-js ${out.meta.h3js}, ` +
    `${pentagonCells} pentagon cells covered)`
);
