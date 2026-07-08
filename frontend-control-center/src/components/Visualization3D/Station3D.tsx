/**
 * 车站建模（真实比例，坐标/10）
 * 站台14u长 × 1.2u宽，站间距约134u
 */

import React from 'react';
import type { Vec3 } from './CoordinateConverter';
import type { StationGeo } from '../../types/dispatch';

interface Props { station: StationGeo; position: Vec3; direction: Vec3; }

const PL = 14, PW = 1.2;

export default function Station3D({ station, position, direction }: Props) {
  const isT = station.id === 1 || station.id === 13;
  const isM = station.name.includes('西站') || station.name.includes('图书馆');
  const cH = isM ? 1.6 : 1.1;
  const rotY = Math.atan2(direction.x, -direction.z);

  return (
    <group position={[position.x, position.y, position.z]} rotation={[0, rotY, 0]}>
      {/* 站台面 */}
      <mesh position={[0, 0.03, 0]} receiveShadow>
        <boxGeometry args={[PL, 0.03, PW]} /><meshStandardMaterial color="#999" roughness={0.6} />
      </mesh>
      {/* 安全线 */}
      {[-1,1].map(s=><mesh key={`sa${s}`} position={[0,0.05,s*(PW/2-0.05)]}><boxGeometry args={[PL,0.005,0.03]}/><meshStandardMaterial color="#ffcc00" emissive="#ffcc00" emissiveIntensity={0.4}/></mesh>)}
      {/* 立柱 10对 */}
      {Array.from({length:10},(_,i)=>{const t=(i/9-.5)*PL;return[-1,1].map(s=><mesh key={`pi${i}${s}`} position={[t,.35,s*(PW/2-.1)]} castShadow><cylinderGeometry args={[.02,.03,.7,8]}/><meshStandardMaterial color="#c8c8c8" roughness={.4} metalness={.5}/></mesh>)})}
      {/* 顶棚 */}
      <mesh position={[0,cH,0]}><boxGeometry args={[PL,.04,PW+.2]}/><meshStandardMaterial color="#2a4a6a" roughness={.5} metalness={.4} transparent opacity={.5}/></mesh>
      {Array.from({length:12},(_,i)=>{const t=(i/11-.5)*PL;return<mesh key={`tr${i}`} position={[t,cH+.1,0]} castShadow><boxGeometry args={[.03,.2,PW+.2]}/><meshStandardMaterial color="#3a5a7a" roughness={.3} metalness={.6}/></mesh>})}
      {/* 屏蔽门 */}
      {[-1,1].map(s=>Array.from({length:20},(_,i)=>{const t=(i/19-.5)*PL;return<group key={`ps${s}${i}`}><mesh position={[t,.12,s*.25]}><boxGeometry args={[.008,.24,.008]}/><meshStandardMaterial color="#888" roughness={.4} metalness={.7}/></mesh><mesh position={[t,.12,s*(.25+.002*s)]}><boxGeometry args={[.12,.18,.002]}/><meshStandardMaterial color="#88ccff" roughness={.1} transparent opacity={.45}/></mesh></group>}))}
      {/* 信息屏 */}
      {[-.3,0,.3].map(o=>[-1,1].map(s=><mesh key={`in${o}${s}`} position={[o*PL,.45,s*(PW/2-.05)]}><boxGeometry args={[.2,.12,.01]}/><meshStandardMaterial color="#1a1a2e" emissive="#3b82f6" emissiveIntensity={.4}/></mesh>))}
      {/* 楼梯井 */}
      {[-1,1].map(s=><group key={`st${s}`}><mesh position={[s*3,.3,0]}><boxGeometry args={[.5,.6,.5]}/><meshStandardMaterial color="#334155" roughness={.6}/></mesh><mesh position={[s*3,.3,0]} rotation={[0,0,s*.5]}><boxGeometry args={[.4,.03,.3]}/><meshStandardMaterial color="#475569" roughness={.5}/></mesh></group>)}
      {/* 站名牌 */}
      <mesh position={[0,cH+.3,0]}><boxGeometry args={[.8,.15,.2]}/><meshStandardMaterial color={isT?'#e8a838':isM?'#9b59b6':'#3b82f6'} emissive={isT?'#e8a838':isM?'#9b59b6':'#3b82f6'} emissiveIntensity={.6} roughness={.2}/></mesh>
      {/* 终点球 */}
      {isT&&<mesh position={[0,cH+.5,0]}><sphereGeometry args={[.2,16,16]}/><meshStandardMaterial color={station.id===1?'#06d6a0':'#fc5c65'} emissive={station.id===1?'#06d6a0':'#fc5c65'} emissiveIntensity={.7} roughness={.2}/></mesh>}
      {/* 大型站入口 */}
      {isM&&[-1,1].map(s=><group key={`en${s}`}><mesh position={[s*3.5,.2,0]}><boxGeometry args={[.8,.4,.6]}/><meshStandardMaterial color="#1e3a5f" roughness={.5} metalness={.3} transparent opacity={.8}/></mesh><mesh position={[s*3.5,.35,0]}><torusGeometry args={[.25,.03,8,8,Math.PI]}/><meshStandardMaterial color="#3b82f6" roughness={.3} metalness={.4}/></mesh></group>)}
    </group>
  );
}
