/**
 * 轨道建模（真实比例，坐标/10）
 */

import React, { useMemo } from 'react';
import * as THREE from 'three';
import type { Vec3 } from './CoordinateConverter';

const SW = 0.26, SH = 0.02, SG = 0.06, RO = 0.144;

export default function TrackLine3D({ points }: { points: Vec3[] }) {
  const curve = useMemo(() => {
    if (points.length < 2) return null;
    return new THREE.CatmullRomCurve3(points.map((p) => new THREE.Vector3(p.x, p.y, p.z)), false, 'catmullrom', 0.5);
  }, [points]);

  const sleepers = useMemo(() => {
    if (!curve) return [];
    const len = curve.getLength(), count = Math.floor(len / SG);
    return Array.from({ length: count }, (_, i) => {
      const t = i / count;
      return { pos: curve.getPointAt(t), tan: curve.getTangentAt(t).normalize() };
    });
  }, [curve]);

  if (!curve || sleepers.length === 0) return null;
  const up = new THREE.Vector3(0, 1, 0);

  return (
    <group>
      <mesh geometry={new THREE.TubeGeometry(curve, 512, 0.45, 6, false)} receiveShadow>
        <meshStandardMaterial color="#3d3d30" roughness={0.95} />
      </mesh>
      {sleepers.map((s, i) => {
        const p = new THREE.Vector3().crossVectors(s.tan, up).normalize();
        const q = new THREE.Quaternion().setFromUnitVectors(new THREE.Vector3(0, 0, 1), p);
        return (
          <React.Fragment key={i}>
            <mesh position={[s.pos.x, s.pos.y - 0.04, s.pos.z]} quaternion={q}>
              <boxGeometry args={[SW, SH, 0.01]} /><meshStandardMaterial color="#8a8a7a" roughness={0.7} />
            </mesh>
            <Rail base={s.pos.clone().addScaledVector(p, -RO)} perp={p} />
            <Rail base={s.pos.clone().addScaledVector(p, RO)} perp={p} />
          </React.Fragment>
        );
      })}
      {sleepers.filter((_, i) => i % 30 === 0).map((s, i) => (
        <Pole key={`p${i}`} pos={s.pos} />
      ))}
      <mesh geometry={new THREE.TubeGeometry(curve, 512, 0.006, 4, false)}>
        <meshBasicMaterial color="#c0a060" transparent opacity={0.5} depthWrite={false} />
      </mesh>
    </group>
  );
}

function Rail({ base, perp }: { base: THREE.Vector3; perp: THREE.Vector3 }) {
  const q = new THREE.Quaternion().setFromUnitVectors(new THREE.Vector3(0, 1, 0), perp);
  return (
    <group position={[base.x, base.y + 0.02, base.z]} quaternion={q}>
      <mesh position={[0, -0.005, 0]}><boxGeometry args={[0.016, 0.005, 0.012]} /><meshStandardMaterial color="#8899aa" roughness={0.3} metalness={0.9} /></mesh>
      <mesh><boxGeometry args={[0.008, 0.012, 0.012]} /><meshStandardMaterial color="#778899" roughness={0.3} metalness={0.85} /></mesh>
      <mesh position={[0, 0.009, 0]}><boxGeometry args={[0.014, 0.004, 0.012]} /><meshStandardMaterial color="#aabbcc" roughness={0.2} metalness={0.95} /></mesh>
    </group>
  );
}

function Pole({ pos }: { pos: THREE.Vector3 }) {
  return (
    <group position={[pos.x, pos.y, pos.z]}>
      <mesh position={[0, 0.35, 0]} castShadow><cylinderGeometry args={[0.005, 0.008, 0.7, 6]} /><meshStandardMaterial color="#555" roughness={0.5} metalness={0.7} /></mesh>
      <mesh position={[0, 0.65, 0]}><boxGeometry args={[0.003, 0.003, 0.16]} /><meshStandardMaterial color="#777" roughness={0.4} /></mesh>
      {[-1,1].map(s=><mesh key={`i${s}`} position={[0,0.62, s*0.06]}><cylinderGeometry args={[0.002,0.002,0.02,6]}/><meshStandardMaterial color="#ddd"/></mesh>)}
    </group>
  );
}
