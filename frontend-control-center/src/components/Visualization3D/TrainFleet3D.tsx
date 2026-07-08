/**
 * 列车编队管理（/10 比例）
 */

import React, { useMemo } from 'react';
import * as THREE from 'three';
import Train3D from './Train3D';
import { interpolatePosition } from './CoordinateConverter';
import type { Vec3 } from './CoordinateConverter';
import type { TrainState } from '../../types/dispatch';

interface Props { trains: TrainState[]; stationPositions: Vec3[]; stationKms: number[]; }

export default function TrainFleet3D({ trains, stationPositions, stationKms }: Props) {
  const curve = useMemo(() => {
    if (stationPositions.length < 2) return null;
    return new THREE.CatmullRomCurve3(
      stationPositions.map(p => new THREE.Vector3(p.x, p.y, p.z)), false, 'catmullrom', 0.5,
    );
  }, [stationPositions]);

  const getPT = (posMeters: number) => {
    const pos = interpolatePosition(posMeters, stationPositions, stationKms);
    let tang: Vec3 = { x: 0, y: 0, z: 1 };
    if (curve && stationKms.length >= 2) {
      const totalKm = stationKms[stationKms.length - 1];
      const km = posMeters / 10000;
      const t = Math.max(0, Math.min(1, km / totalKm));
      const t3 = curve.getTangentAt(t);
      tang = { x: t3.x, y: t3.y, z: t3.z };
    }
    return { position: pos, tangent: tang };
  };

  return (
    <group>
      {trains.filter(t => t.status !== 'FINISHED').map(train => {
        const { position, tangent } = getPT(train.positionMeters);
        return <Train3D key={train.trainId} train={train} position={position} tangent={tangent} />;
      })}
    </group>
  );
}
