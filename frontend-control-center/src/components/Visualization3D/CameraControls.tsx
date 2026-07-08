/**
 * 预设视角控制（仅 R3F 场景内部分）
 * - OrbitControls 自由旋转缩放
 * - 暴露 flyTo 方法供外部 overlay 按钮调用
 */

import { useEffect, useRef } from 'react';
import { useThree } from '@react-three/fiber';
import { OrbitControls } from '@react-three/drei';
import type { OrbitControls as OrbitControlsImpl } from 'three-stdlib';

export const PRESETS = [
  { label: '俯视', position: [0, 300, -60], target: [0, 0, -60] },
  { label: '侧视', position: [200, 20, 0], target: [0, 0, -60] },
  { label: '全景', position: [-60, 80, 200], target: [0, 0, -60] },
];

export interface CameraApi {
  flyTo: (pos: number[], target: number[]) => void;
  controls: OrbitControlsImpl | null;
}

interface Props {
  onReady?: (api: CameraApi) => void;
}

export default function CameraControls({ onReady }: Props) {
  const controlsRef = useRef<OrbitControlsImpl | null>(null);
  const { camera } = useThree();

  const flyTo = (pos: number[], target: number[]) => {
    const ctrl = controlsRef.current;
    if (!ctrl) return;
    ctrl.target.set(target[0], target[1], target[2]);
    ctrl.update();
    const start = { x: camera.position.x, y: camera.position.y, z: camera.position.z };
    const end = { x: pos[0], y: pos[1], z: pos[2] };
    const duration = 800;
    const startTime = performance.now();

    function animate(now: number) {
      const elapsed = now - startTime;
      const t = Math.min(elapsed / duration, 1);
      const ease = t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
      camera.position.set(
        start.x + (end.x - start.x) * ease,
        start.y + (end.y - start.y) * ease,
        start.z + (end.z - start.z) * ease,
      );
      if (t < 1) requestAnimationFrame(animate);
    }
    requestAnimationFrame(animate);
  };

  useEffect(() => {
    if (onReady) {
      onReady({ flyTo, controls: controlsRef.current });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [camera]);

  return (
    <OrbitControls
      ref={controlsRef}
      makeDefault
      enableDamping
      dampingFactor={0.08}
      minDistance={500}
      maxDistance={20000}
      maxPolarAngle={Math.PI / 2.2}
      target={[0, 0, -7000]}
    />
  );
}
