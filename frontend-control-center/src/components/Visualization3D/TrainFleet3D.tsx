/**
 * 列车编队管理（/10 比例）
 * 使用 useFrame 对列车位置做帧间插值，消除 1Hz 快照导致的跳跃感。
 */

import React, { useMemo, useRef, useEffect } from 'react';
import { useFrame } from '@react-three/fiber';
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

  // ── 位置插值：存储每列车的上帧、目标、当前显示位置 ──
  const prevPositions = useRef<Map<string, number>>(new Map());
  const targetPositions = useRef<Map<string, number>>(new Map());
  const displayPositions = useRef<Map<string, number>>(new Map());

  useEffect(() => {
    for (const train of trains) {
      const tid = train.trainId;
      // 上一帧位置 = 当前显示位置（如果存在），否则直接设为目标位置
      const current = displayPositions.current.get(tid);
      if (current !== undefined) {
        prevPositions.current.set(tid, current);
      } else {
        // 首次出现，直接跳到目标位置
        prevPositions.current.set(tid, train.positionMeters);
        displayPositions.current.set(tid, train.positionMeters);
      }
      targetPositions.current.set(tid, train.positionMeters);
    }
    // 清理已下线的列车
    const activeIds = new Set(trains.map(t => t.trainId));
    for (const id of prevPositions.current.keys()) {
      if (!activeIds.has(id)) {
        prevPositions.current.delete(id);
        targetPositions.current.delete(id);
        displayPositions.current.delete(id);
      }
    }
  }, [trains]);

  // ── 每帧 lerp 插值 ──
  useFrame((_, delta) => {
    const lerpFactor = Math.min(1, delta * 10);
    for (const [id, target] of targetPositions.current) {
      const current = displayPositions.current.get(id);
      if (current === undefined) {
        displayPositions.current.set(id, target);
        continue;
      }
      displayPositions.current.set(id, current + (target - current) * lerpFactor);
    }
  });

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
        // 使用插值后的显示位置，而非原始快照位置
        const displayPos = displayPositions.current.get(train.trainId) ?? train.positionMeters;
        const { position, tangent } = getPT(displayPos);
        return <Train3D key={train.trainId} train={train} position={position} tangent={tangent} />;
      })}
    </group>
  );
}
