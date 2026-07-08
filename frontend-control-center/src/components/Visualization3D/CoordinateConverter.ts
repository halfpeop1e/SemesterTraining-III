/**
 * WGS-84 → 3D 局部坐标系（/10 缩放）
 * X=东, Y=上, Z=南→北为负
 */

import type { StationGeo } from '../../types/dispatch';

export interface Vec3 { x: number; y: number; z: number; }

const DL = 111_320;
const OLAT = 39.876, OLON = 116.303;
const COS = Math.cos((OLAT * Math.PI) / 180);
const DLON = DL * COS;
const SCALE = 10;

export function geoToLocal(lat: number, lon: number, alt = 0): Vec3 {
  return { x: (lon - OLON) * DLON / SCALE, y: alt / SCALE, z: -(lat - OLAT) * DL / SCALE };
}

export function stationsToLocal(stations: StationGeo[]): Vec3[] {
  return [...stations].sort((a, b) => a.id - b.id).map((s) => geoToLocal(s.latitude, s.longitude));
}

export function interpolatePosition(posMeters: number, stationPositions: Vec3[], stationKms: number[]): Vec3 {
  if (stationPositions.length < 2 || stationKms.length < 2) return stationPositions[0] || { x: 0, y: 0, z: 0 };
  const km = posMeters / (1000 * SCALE);
  if (km <= stationKms[0]) return stationPositions[0];
  if (km >= stationKms[stationKms.length - 1]) return stationPositions[stationKms.length - 1];
  let idx = 1;
  while (idx < stationKms.length && stationKms[idx] < km) idx++;
  const p0 = stationPositions[idx - 1], p1 = stationPositions[idx];
  const k0 = stationKms[idx - 1], k1 = stationKms[idx];
  const t = (km - k0) / (k1 - k0);
  return { x: p0.x + (p1.x - p0.x) * t, y: p0.y + (p1.y - p0.y) * t, z: p0.z + (p1.z - p0.z) * t };
}
