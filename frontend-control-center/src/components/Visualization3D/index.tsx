/**
 * 列车运行 3D 可视化主容器（Three.js + WebGL）
 * 统一比例：坐标/50, 几何 1:1
 */

import React, { Suspense, useState, useMemo, useCallback } from "react";
import * as THREE from "three";
import { Canvas } from "@react-three/fiber";

import Environment3D from "./Environment3D";
import CameraControls, { PRESETS } from "./CameraControls";
import type { CameraApi } from "./CameraControls";
import TrackLine3D from "./TrackLine3D";
import Station3D from "./Station3D";
import TrainFleet3D from "./TrainFleet3D";
import { stationsToLocal } from "./CoordinateConverter";
import type { Vec3 } from "./CoordinateConverter";
import type { SimulationSnapshot, StationGeo } from "../../types/dispatch";

const SCALE = 10;

interface Props {
  snapshot: SimulationSnapshot | null;
  stations: StationGeo[];
}

function Scene3D({
  snapshot,
  stations,
  onCameraReady,
}: Props & { onCameraReady: (api: CameraApi) => void }) {
  const stationPositions = useMemo<Vec3[]>(
    () => stationsToLocal(stations),
    [stations],
  );

  // 缩放后的里程 (km/SCALE)
  const stationKms = useMemo(
    () => [...stations].sort((a, b) => a.id - b.id).map((s) => s.km / SCALE),
    [stations],
  );

  const sortedStations = useMemo(
    () => [...stations].sort((a, b) => a.id - b.id),
    [stations],
  );

  // 轨道曲线方向
  const stationDirections = useMemo<Vec3[]>(() => {
    if (stationPositions.length < 2) return [];
    const pts = stationPositions.map((p) => new THREE.Vector3(p.x, p.y, p.z));
    const curve = new THREE.CatmullRomCurve3(pts, false, "catmullrom", 0.5);
    return stationPositions.map((_, i) => {
      const t = i / (stationPositions.length - 1 || 1);
      const tan = curve.getTangentAt(t).normalize();
      return { x: tan.x, y: tan.y, z: tan.z };
    });
  }, [stationPositions]);

  const trains = snapshot?.trains ?? [];

  return (
    <>
      <Environment3D />
      <CameraControls onReady={onCameraReady} />
      <TrackLine3D points={stationPositions} />
      {sortedStations.map((s, i) => (
        <Station3D
          key={s.id}
          station={s}
          position={stationPositions[i]}
          direction={stationDirections[i] ?? { x: 0, y: 0, z: 1 }}
        />
      ))}
      <TrainFleet3D
        trains={trains}
        stationPositions={stationPositions}
        stationKms={stationKms}
      />
    </>
  );
}

export default function Visualization3D({ snapshot, stations }: Props) {
  const [cameraApi, setCameraApi] = useState<CameraApi | null>(null);
  const handleCameraReady = useCallback(
    (api: CameraApi) => setCameraApi(api),
    [],
  );

  return (
    <div className="w-full h-full relative">
      <Canvas
        shadows
        camera={{ position: [0, 40, 200], fov: 45, near: 0.1, far: 3000 }}
        gl={{
          antialias: true,
          toneMapping: 3,
          toneMappingExposure: 1.2,
          alpha: false,
        }}
        style={{ background: "#060b11" }}
        dpr={[1, 1.5]}
      >
        <Suspense fallback={null}>
          <Scene3D
            snapshot={snapshot}
            stations={stations}
            onCameraReady={handleCameraReady}
          />
        </Suspense>
      </Canvas>

      <div className="absolute bottom-4 left-1/2 -translate-x-1/2 flex gap-2 z-10">
        {PRESETS.map((p) => (
          <button
            key={p.label}
            type="button"
            onClick={() => cameraApi?.flyTo(p.position, p.target)}
            className="px-3.5 py-1 text-[11px] font-semibold rounded border border-slate-400/20 bg-[#0d1520]/85 text-slate-400 cursor-pointer hover:bg-[#1e324b]/90 hover:text-[#cbd5e1] transition-colors"
          >
            {p.label}
          </button>
        ))}
      </div>

      {snapshot && (
        <div className="absolute top-3 left-4 z-10 flex flex-col gap-1.5 pointer-events-none">
          <Hud
            label="SIM TIME"
            value={snapshot.simTimeFormatted}
            color="#00a8e8"
          />
          <Hud
            label="在线列车"
            value={`${snapshot.activeTrains}/${snapshot.totalTrains}`}
            color="#06d6a0"
          />
          <Hud
            label="牵引用电"
            value={`${snapshot.totalEnergyKwh.toFixed(1)} kWh`}
            color="#f7b731"
          />
        </div>
      )}

      <div className="absolute top-3 right-4 z-10 flex flex-col gap-1 py-2 px-3 rounded-md bg-[#0d1520]/85 backdrop-blur-sm border border-slate-400/12 pointer-events-none">
        {[
          ["#22c55e", "运行中"],
          ["#f59e0b", "进站中"],
          ["#3b82f6", "发车中"],
          ["#64748b", "待发"],
          ["#ef4444", "终到"],
        ].map(([c, l]) => (
          <div
            key={l}
            className="flex items-center gap-1.5 text-[10px] text-[#94a3b8]"
          >
            <span
              className="inline-block w-2 h-2 rounded-full"
              style={{ background: c }}
            />
            {l}
          </div>
        ))}
      </div>
    </div>
  );
}

function Hud({
  label,
  value,
  color,
}: {
  label: string;
  value: string;
  color: string;
}) {
  return (
    <div
      className="flex items-center gap-2 px-2.5 py-1 rounded backdrop-blur-sm"
      style={{
        background: "rgba(13,21,32,0.85)",
        border: `1px solid ${color}22`,
      }}
    >
      <span className="text-[8px] font-bold text-[#617088] tracking-[1.5px] uppercase">
        {label}
      </span>
      <span className="text-[13px] font-bold font-mono" style={{ color }}>
        {value}
      </span>
    </div>
  );
}
