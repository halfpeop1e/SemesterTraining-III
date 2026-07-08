/**
 * 列车建模（真实比例，坐标/10）
 * 单节车厢 1.9u × 0.28u × 0.38u
 * 6编组约 11.4u
 */

import React, { useMemo } from 'react';
import type { Vec3 } from './CoordinateConverter';
import type { TrainState } from '../../types/dispatch';

interface Props { train: TrainState; position: Vec3; tangent: Vec3; }

const CL = 1.9, CW = 0.28, CH = 0.38, BO = 0.45, GW = 0.12;

const SC: Record<string, string> = { STOPPED:'#64748b', RUNNING:'#22c55e', ARRIVING:'#f59e0b', DEPARTING:'#3b82f6', FINISHED:'#ef4444' };

export default function Train3D({ train, position, tangent }: Props) {
  const cc = train.carCount ?? 6;
  const bc = SC[train.status] || '#64748b';
  const mv = train.speed > 0;
  const ry = useMemo(() => Math.atan2(tangent.x, -tangent.z), [tangent]);
  const tl = cc * CL + (cc - 1) * GW;
  const so = -tl / 2 + CL / 2;

  const cars = useMemo(() => Array.from({ length: cc }, (_, i) => ({
    idx: i, off: so + i * (CL + GW), hd: i === 0, pw: i === 0 || i === cc - 1 || i === Math.floor(cc / 2),
  })), [cc, so]);

  return (
    <group position={[position.x, position.y + 0.06, position.z]}>
      <group rotation={[0, ry, 0]}>
        {cars.map(c => <Car key={c.idx} {...c} bc={bc} mv={mv} />)}
      </group>
    </group>
  );
}

function Car({ off, hd, pw, bc, mv }: { off: number; hd: boolean; pw: boolean; bc: string; mv: boolean }) {
  const hw = CW / 2;
  return (
    <group position={[off, 0, 0]}>
      <mesh castShadow><boxGeometry args={[CL - 0.02, CH, CW]} /><meshStandardMaterial color={hd ? bc : '#4a5a6a'} roughness={0.25} metalness={0.7} /></mesh>
      {[-1, 1].map(e => (
        <mesh key={`rd${e}`} position={[e * (CL / 2 - 0.02), 0, 0]} rotation={[0, 0, Math.PI / 2]}>
          <cylinderGeometry args={[hw, hw, 0.04, 16]} /><meshStandardMaterial color={hd && e === 1 ? bc : '#4a5a6a'} roughness={0.25} metalness={0.7} />
        </mesh>
      ))}
      {Array.from({ length: 3 }, (_, i) => {
        const wx = (i / 2 - 0.5) * (CL - 0.12);
        return <group key={`w${i}`} position={[wx, 0.05, 0]}>
          <mesh><boxGeometry args={[0.25, 0.1, CW - 0.04]} /><meshStandardMaterial color="#333" roughness={0.3} /></mesh>
          <mesh><boxGeometry args={[0.22, 0.08, CW - 0.06]} /><meshStandardMaterial color="#87ceeb" roughness={0.1} emissive="#87ceeb" emissiveIntensity={hd && mv ? 0.25 : 0.05} transparent opacity={0.7} /></mesh>
        </group>;
      })}
      {[-1, 1].map(s => [CL * 0.3, -CL * 0.3].map((dx, di) => (
        <group key={`dr${s}${di}`} position={[dx, -0.02, s * (CW / 2 + 0.005)]}>
          <mesh><boxGeometry args={[0.18, 0.25, 0.004]} /><meshStandardMaterial color="#555" roughness={0.3} /></mesh>
          <mesh><boxGeometry args={[0.004, 0.22, 0.008]} /><meshStandardMaterial color="#222" /></mesh>
        </group>
      )))}
      <Bog p={[BO, -CH / 2 - 0.04, 0]} /><Bog p={[-BO, -CH / 2 - 0.04, 0]} />
      <mesh position={[CL / 2 + 0.1, -0.08, 0]}><boxGeometry args={[0.15, 0.02, 0.02]} /><meshStandardMaterial color="#666" roughness={0.3} metalness={0.8} /></mesh>
      <mesh position={[-CL / 2 - 0.1, -0.08, 0]}><boxGeometry args={[0.15, 0.02, 0.02]} /><meshStandardMaterial color="#666" roughness={0.3} metalness={0.8} /></mesh>
      {pw && <Pant />}
      {[-CL * 0.25, CL * 0.25].map(hx => <mesh key={`hv${hx}`} position={[hx, CH / 2 + 0.03, 0]}><boxGeometry args={[0.3, 0.08, CW * 0.7]} /><meshStandardMaterial color="#ddd" roughness={0.3} /></mesh>)}
      <mesh position={[0, CH / 2 + 0.12, 0]}><boxGeometry args={[CL * 0.6, 0.05, 0.08]} /><meshStandardMaterial color={bc} emissive={bc} emissiveIntensity={0.5} roughness={0.2} /></mesh>
      {hd && <>
        <mesh position={[CL / 2 + 0.12, 0.02, 0]} rotation={[0, 0, 0.15]}><boxGeometry args={[0.15, 0.2, CW - 0.12]} /><meshStandardMaterial color="#1a2a4a" roughness={0.1} emissive={mv ? '#4a90d9' : '#0a1a3a'} emissiveIntensity={mv ? 0.5 : 0.1} /></mesh>
        {[-1, 1].map(s => <mesh key={`hl${s}`} position={[CL / 2 + 0.16, -0.08, s * CW / 3]}><sphereGeometry args={[0.04, 8, 8]} /><meshStandardMaterial color="#ffffcc" emissive="#ffffcc" emissiveIntensity={mv ? 1.5 : 0.3} roughness={0.1} /></mesh>)}
      </>}
    </group>
  );
}

function Bog({ p }: { p: [number, number, number] }) {
  return (
    <group position={p}>
      <mesh><boxGeometry args={[0.3, 0.04, CW * 0.8]} /><meshStandardMaterial color="#444" roughness={0.3} metalness={0.9} /></mesh>
      {[-1, 1].map(a => (
        <group key={`a${a}`} position={[a * 0.12, -0.02, 0]}>
          <mesh rotation={[0, 0, Math.PI / 2]}><cylinderGeometry args={[0.015, 0.015, CW * 0.8, 8]} /><meshStandardMaterial color="#666" roughness={0.2} metalness={0.95} /></mesh>
          {[-1, 1].map(w => <mesh key={`w${w}`} position={[0, 0, w * CW * 0.3]}><cylinderGeometry args={[0.045, 0.045, 0.012, 16]} /><meshStandardMaterial color="#555" roughness={0.2} metalness={0.9} /></mesh>)}
        </group>
      ))}
    </group>
  );
}

function Pant() {
  return (
    <group position={[0, CH / 2 + 0.01, 0]}>
      <mesh><boxGeometry args={[0.2, 0.015, 0.15]} /><meshStandardMaterial color="#555" roughness={0.3} metalness={0.8} /></mesh>
      {[-1, 1].map(s => (
        <group key={`ar${s}`}>
          <mesh position={[0, 0.12, s * 0.08]} rotation={[s * 0.4, 0, 0]}><boxGeometry args={[0.015, 0.3, 0.015]} /><meshStandardMaterial color="#777" roughness={0.3} metalness={0.7} /></mesh>
          <mesh position={[0, 0.24, s * 0.08]} rotation={[-s * 0.5, 0, 0]}><boxGeometry args={[0.012, 0.25, 0.012]} /><meshStandardMaterial color="#888" roughness={0.3} metalness={0.7} /></mesh>
        </group>
      ))}
      <mesh position={[0, 0.36, 0]}><boxGeometry args={[0.15, 0.01, 0.25]} /><meshStandardMaterial color="#222" roughness={0.5} /></mesh>
      {[-1, 1].map(s => <mesh key={`hr${s}`} position={[0, 0.37, s * 0.13]}><boxGeometry args={[0.15, 0.02, 0.015]} /><meshStandardMaterial color="#c0a060" roughness={0.3} metalness={0.6} /></mesh>)}
    </group>
  );
}
