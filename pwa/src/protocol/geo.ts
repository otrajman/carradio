// Pure geodesy helpers (no deps) shared by the filter, room manager, and simulator.
const R_EARTH_M = 6371000;

export function toRad(deg: number): number {
  return (deg * Math.PI) / 180;
}

export function haversineM(lat1: number, lng1: number, lat2: number, lng2: number): number {
  const dLat = toRad(lat2 - lat1);
  const dLng = toRad(lng2 - lng1);
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLng / 2) ** 2;
  return 2 * R_EARTH_M * Math.asin(Math.sqrt(a));
}

/** Initial bearing from point 1 to point 2, degrees [0,360). */
export function bearingDeg(lat1: number, lng1: number, lat2: number, lng2: number): number {
  const φ1 = toRad(lat1);
  const φ2 = toRad(lat2);
  const Δλ = toRad(lng2 - lng1);
  const y = Math.sin(Δλ) * Math.cos(φ2);
  const x = Math.cos(φ1) * Math.sin(φ2) - Math.sin(φ1) * Math.cos(φ2) * Math.cos(Δλ);
  return ((Math.atan2(y, x) * 180) / Math.PI + 360) % 360;
}

/** Cosine of the angle between two headings (both in degrees). */
export function headingDot(aDeg: number, bDeg: number): number {
  return Math.cos(toRad(aDeg - bDeg));
}

/** Destination point given start, bearing (deg), and distance (m). */
export function destination(
  lat: number,
  lng: number,
  bearing: number,
  distanceM: number,
): { lat: number; lng: number } {
  const δ = distanceM / R_EARTH_M;
  const θ = toRad(bearing);
  const φ1 = toRad(lat);
  const λ1 = toRad(lng);
  const φ2 = Math.asin(
    Math.sin(φ1) * Math.cos(δ) + Math.cos(φ1) * Math.sin(δ) * Math.cos(θ),
  );
  const λ2 =
    λ1 +
    Math.atan2(
      Math.sin(θ) * Math.sin(δ) * Math.cos(φ1),
      Math.cos(δ) - Math.sin(φ1) * Math.sin(φ2),
    );
  return { lat: (φ2 * 180) / Math.PI, lng: ((((λ2 * 180) / Math.PI + 540) % 360) - 180) };
}
