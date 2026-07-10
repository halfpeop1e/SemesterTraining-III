/**
 * ThreeRailwayView — Three.js 驾驶视角组件
 * 加载 /models/vehicle/railway.glb，失败则 /models/vehicle/train-cab.glb，
 * 两者均失败显示兜底 CSS 轨道视图，不白屏。
 * React unmount: dispose renderer/geometry/material/texture + 停止 rAF。
 * VISUAL/SIM: platform length is assumed for HMI visualization only.
 */
import { useEffect, useRef, useState } from 'react';
import type * as THREE_TYPES from 'three';
import type { StationStop, TrainState } from '../../../types/vehicle';

export interface ThreeRailwayViewProps {
  currentState: TrainState | null;
  /** speed / speedLimit, 0–1 */
  speedRatio: number;
  targetStopPosition: number;
  stationStops?: StationStop[];
  className?: string;
}

// VISUAL/SIM: platform length is assumed for HMI visualization only.
const PLATFORM_LENGTH_M = 120;
const WORLD_LOOP_LENGTH_M = 252;
const WORLD_WRAP_NEAR_Z = 24;
type LoadState = 'loading' | 'ready' | 'error';
type ModelPreset = {
  label: string;
  targetMaxDim: number;
  position: [number, number, number];
  rotation?: [number, number, number];
  scrollCopies?: number;
};

const MODEL_PRESETS: Record<string, ModelPreset> = {
  '/models/vehicle/railway.glb': {
    label: 'railway.glb',
    targetMaxDim: 42,
    position: [0, 0.02, -24],
    scrollCopies: 7,
  },
  '/models/vehicle/train-cab.glb': {
    label: 'train-cab.glb',
    targetMaxDim: 15,
    position: [0, 0.02, -16],
    rotation: [0, Math.PI / 2, 0],
    scrollCopies: 3,
  },
};

export default function ThreeRailwayView({
  currentState,
  speedRatio,
  targetStopPosition,
  stationStops,
  className = '',
}: ThreeRailwayViewProps) {
  const mountRef = useRef<HTMLDivElement>(null);
  const [loadState, setLoadState] = useState<LoadState>('loading');
  const [errorMsg, setErrorMsg] = useState('');
  const stateRef = useRef({ currentState, speedRatio, targetStopPosition, stationStops });
  stateRef.current = { currentState, speedRatio, targetStopPosition, stationStops };

  useEffect(() => {
    // eslint-disable-next-line @typescript-eslint/no-non-null-assertion
    const container = mountRef.current!;
    if (!container) return;

    let cancelled = false;
    let rafId = 0;
    let disposeAll: (() => void) | null = null;

    async function init() {
      try {
        const THREE = await import('three');
        const { GLTFLoader } = await import('three/examples/jsm/loaders/GLTFLoader.js');
        if (cancelled) return;

        // Renderer
        const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
        renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
        renderer.shadowMap.enabled = true;
        renderer.toneMapping = THREE.ACESFilmicToneMapping;
        renderer.toneMappingExposure = 1.15;
        container.appendChild(renderer.domElement);

        const resize = () => {
          const w = container.clientWidth || 400;
          const h = container.clientHeight || 300;
          renderer.setSize(w, h);
          camera.aspect = w / h;
          camera.updateProjectionMatrix();
        };
        const ro = new ResizeObserver(resize);
        ro.observe(container as Element);

        // Scene
        const scene = new THREE.Scene();
        scene.background = new THREE.Color('#07111a');
        scene.fog = new THREE.FogExp2('#07111a', 0.018);

        const camera = new THREE.PerspectiveCamera(60, 1, 0.1, 600);
        camera.position.set(0, 2.4, 8);
        camera.lookAt(0, 0.9, -18);

        // Lights
        scene.add(new THREE.AmbientLight('#b0cfe0', 0.65));
        scene.add(new THREE.HemisphereLight('#d8f3ff', '#1f2933', 0.85));
        const dirLight = new THREE.DirectionalLight('#ffffff', 1.1);
        dirLight.position.set(4, 10, 6);
        dirLight.castShadow = true;
        scene.add(dirLight);

        const platformLight = new THREE.PointLight('#97e8d6', 1.8, 35);
        platformLight.position.set(0, 3, -5);
        scene.add(platformLight);

        let scrollingObjects: Array<{
          object: THREE_TYPES.Object3D;
          baseZ: number;
          kind: 'track' | 'station';
          wrap: boolean;
        }> = [];
        const registerScrollingObject = (
          object: THREE_TYPES.Object3D,
          kind: 'track' | 'station' = 'track',
          wrap = true,
          baseZ = object.position.z,
        ) => {
          scrollingObjects.push({
            object,
            baseZ,
            kind,
            wrap,
          });
        };
        const applyScroll = (travelMeters: number) => {
          scrollingObjects.forEach(({ object, baseZ, wrap }) => {
            let z = baseZ + travelMeters;
            if (wrap && z > WORLD_WRAP_NEAR_Z) {
              z -= Math.ceil((z - WORLD_WRAP_NEAR_Z) / WORLD_LOOP_LENGTH_M) * WORLD_LOOP_LENGTH_M;
            } else if (wrap && z < WORLD_WRAP_NEAR_Z - WORLD_LOOP_LENGTH_M) {
              z += Math.ceil((WORLD_WRAP_NEAR_Z - WORLD_LOOP_LENGTH_M - z) / WORLD_LOOP_LENGTH_M) * WORLD_LOOP_LENGTH_M;
            }
            object.position.z = z;
            if (!wrap) {
              object.visible = z > -520 && z < 42;
            }
          });
        };

        // Ground track (scrolls with TrainState.position)
        const trackGeo = new THREE.PlaneGeometry(6, 400);
        const trackMat = new THREE.MeshStandardMaterial({ color: '#1a2530', roughness: 0.9 });
        const track = new THREE.Mesh(trackGeo, trackMat);
        track.rotation.x = -Math.PI / 2;
        scene.add(track);
        registerScrollingObject(track);

        for (const x of [-0.75, 0.75]) {
          const rail = new THREE.Mesh(
            new THREE.BoxGeometry(0.08, 0.06, 400),
            new THREE.MeshStandardMaterial({ color: '#8899a0', roughness: 0.4, metalness: 0.7 }),
          );
          rail.position.set(x, 0.03, 0);
          scene.add(rail);
          registerScrollingObject(rail);
        }

        const createMaterial = (color: string, roughness = 0.8, emissive?: string, emissiveIntensity = 0.2) =>
          new THREE.MeshStandardMaterial({
            color,
            roughness,
            emissive: emissive ?? '#000000',
            emissiveIntensity: emissive ? emissiveIntensity : 0,
          });

        // ========== 3D Tunnel ==========
        const TUNNEL_WALL_X = 4.5;
        const TUNNEL_WALL_TOP_Y = 4.2;
        const TUNNEL_CROWN_Y = 5.0;
        const TUNNEL_HAUNCH_OUTER_X = 2.0;
        const TUNNEL_CONTINUOUS_LEN = 400;
        const RIB_SPACING_M = 12;
        const LIGHT_SPACING_M = 6;
        const SLEEPER_SPACING_M = 2;
        const RIB_COUNT = Math.floor(WORLD_LOOP_LENGTH_M / RIB_SPACING_M);
        const LIGHT_COUNT = Math.floor(WORLD_LOOP_LENGTH_M / LIGHT_SPACING_M);
        const SLEEPER_COUNT = Math.floor(WORLD_LOOP_LENGTH_M / SLEEPER_SPACING_M);

        const tunnelWallMat = new THREE.MeshStandardMaterial({ color: '#2c3238', roughness: 0.93, metalness: 0.04 });
        const tunnelRibMat = new THREE.MeshStandardMaterial({ color: '#353b42', roughness: 0.9, metalness: 0.06 });
        const tunnelSleeperMat = new THREE.MeshStandardMaterial({ color: '#1c2026', roughness: 0.95 });
        const tunnelLightMat = new THREE.MeshStandardMaterial({
          color: '#fff6e2', roughness: 0.35, metalness: 0.1,
          emissive: '#ffe4a8', emissiveIntensity: 1.1,
        });

        // Haunch geometry (shared by ribs and continuous ceiling slopes)
        const haunchDx = TUNNEL_WALL_X - TUNNEL_HAUNCH_OUTER_X;
        const haunchDy = TUNNEL_CROWN_Y - TUNNEL_WALL_TOP_Y;
        const haunchLen = Math.sqrt(haunchDx * haunchDx + haunchDy * haunchDy);
        const haunchAngle = Math.atan2(haunchDy, haunchDx);

        // Continuous walls
        const wallGeo = new THREE.BoxGeometry(0.18, TUNNEL_WALL_TOP_Y, TUNNEL_CONTINUOUS_LEN);
        const leftWall = new THREE.Mesh(wallGeo, tunnelWallMat);
        leftWall.position.set(-TUNNEL_WALL_X, TUNNEL_WALL_TOP_Y / 2, 0);
        scene.add(leftWall);
        registerScrollingObject(leftWall);

        const rightWall = new THREE.Mesh(wallGeo, tunnelWallMat);
        rightWall.position.set(TUNNEL_WALL_X, TUNNEL_WALL_TOP_Y / 2, 0);
        scene.add(rightWall);
        registerScrollingObject(rightWall);

        // Continuous ceiling: two angled slopes + flat crown
        const ceilingSlopeGeo = new THREE.BoxGeometry(haunchLen, 0.12, TUNNEL_CONTINUOUS_LEN);
        const leftCeiling = new THREE.Mesh(ceilingSlopeGeo, tunnelWallMat);
        leftCeiling.rotation.z = haunchAngle;
        leftCeiling.position.set(
          -(TUNNEL_WALL_X + TUNNEL_HAUNCH_OUTER_X) / 2,
          (TUNNEL_WALL_TOP_Y + TUNNEL_CROWN_Y) / 2,
          0,
        );
        scene.add(leftCeiling);
        registerScrollingObject(leftCeiling);

        const rightCeiling = new THREE.Mesh(ceilingSlopeGeo, tunnelWallMat);
        rightCeiling.rotation.z = -haunchAngle;
        rightCeiling.position.set(
          (TUNNEL_WALL_X + TUNNEL_HAUNCH_OUTER_X) / 2,
          (TUNNEL_WALL_TOP_Y + TUNNEL_CROWN_Y) / 2,
          0,
        );
        scene.add(rightCeiling);
        registerScrollingObject(rightCeiling);

        const ceilingCrownGeo = new THREE.BoxGeometry(TUNNEL_HAUNCH_OUTER_X * 2, 0.12, TUNNEL_CONTINUOUS_LEN);
        const ceilingCrown = new THREE.Mesh(ceilingCrownGeo, tunnelWallMat);
        ceilingCrown.position.set(0, TUNNEL_CROWN_Y, 0);
        scene.add(ceilingCrown);
        registerScrollingObject(ceilingCrown);

        // Shared geometries for ribs / lights / sleepers
        const ribLegGeo = new THREE.BoxGeometry(0.14, TUNNEL_WALL_TOP_Y, 0.14);
        const ribHaunchGeo = new THREE.BoxGeometry(haunchLen, 0.14, 0.14);
        const ribCrownGeo = new THREE.BoxGeometry(TUNNEL_HAUNCH_OUTER_X * 2, 0.14, 0.14);

        const sleeperGeo = new THREE.BoxGeometry(2.6, 0.05, 0.18);
        const lightGeo = new THREE.BoxGeometry(1.6, 0.05, 0.12);

        const createTunnelRib = (zPos: number) => {
          const rib = new THREE.Group();
          const leftLeg = new THREE.Mesh(ribLegGeo, tunnelRibMat);
          leftLeg.position.set(-TUNNEL_WALL_X, TUNNEL_WALL_TOP_Y / 2, 0);
          rib.add(leftLeg);
          const rightLeg = new THREE.Mesh(ribLegGeo, tunnelRibMat);
          rightLeg.position.set(TUNNEL_WALL_X, TUNNEL_WALL_TOP_Y / 2, 0);
          rib.add(rightLeg);
          const leftHaunch = new THREE.Mesh(ribHaunchGeo, tunnelRibMat);
          leftHaunch.rotation.z = haunchAngle;
          leftHaunch.position.set(
            -(TUNNEL_WALL_X + TUNNEL_HAUNCH_OUTER_X) / 2,
            (TUNNEL_WALL_TOP_Y + TUNNEL_CROWN_Y) / 2,
            0,
          );
          rib.add(leftHaunch);
          const rightHaunch = new THREE.Mesh(ribHaunchGeo, tunnelRibMat);
          rightHaunch.rotation.z = -haunchAngle;
          rightHaunch.position.set(
            (TUNNEL_WALL_X + TUNNEL_HAUNCH_OUTER_X) / 2,
            (TUNNEL_WALL_TOP_Y + TUNNEL_CROWN_Y) / 2,
            0,
          );
          rib.add(rightHaunch);
          const crown = new THREE.Mesh(ribCrownGeo, tunnelRibMat);
          crown.position.set(0, TUNNEL_CROWN_Y, 0);
          rib.add(crown);
          rib.position.z = zPos;
          return rib;
        };

        const createSleeper = (zPos: number) => {
          const sleeper = new THREE.Mesh(sleeperGeo, tunnelSleeperMat);
          sleeper.position.set(0, 0.025, zPos);
          return sleeper;
        };

        const createTunnelLight = (zPos: number) => {
          const light = new THREE.Mesh(lightGeo, tunnelLightMat);
          light.position.set(0, TUNNEL_CROWN_Y - 0.09, zPos);
          return light;
        };

        // Place ribs, lights, sleepers covering one loop length
        const ribStartZ = WORLD_WRAP_NEAR_Z - RIB_SPACING_M;
        for (let i = 0; i < RIB_COUNT; i++) {
          const z = ribStartZ - i * RIB_SPACING_M;
          const rib = createTunnelRib(z);
          scene.add(rib);
          registerScrollingObject(rib, 'track', true, z);
        }

        const lightStartZ = WORLD_WRAP_NEAR_Z - LIGHT_SPACING_M;
        for (let i = 0; i < LIGHT_COUNT; i++) {
          const z = lightStartZ - i * LIGHT_SPACING_M;
          const light = createTunnelLight(z);
          scene.add(light);
          registerScrollingObject(light, 'track', true, z);
        }

        const sleeperStartZ = WORLD_WRAP_NEAR_Z - SLEEPER_SPACING_M;
        for (let i = 0; i < SLEEPER_COUNT; i++) {
          const z = sleeperStartZ - i * SLEEPER_SPACING_M;
          const sleeper = createSleeper(z);
          scene.add(sleeper);
          registerScrollingObject(sleeper, 'track', true, z);
        }
        // ========== End 3D Tunnel ==========

        const createTextPlane = (
          text: string,
          width: number,
          height: number,
          background: string,
          foreground: string,
        ) => {
          const canvas = document.createElement('canvas');
          canvas.width = 512;
          canvas.height = 128;
          const ctx = canvas.getContext('2d');
          if (ctx) {
            ctx.fillStyle = background;
            ctx.fillRect(0, 0, canvas.width, canvas.height);
            ctx.strokeStyle = foreground;
            ctx.lineWidth = 8;
            ctx.strokeRect(8, 8, canvas.width - 16, canvas.height - 16);
            ctx.fillStyle = foreground;
            ctx.font = 'bold 52px Arial, sans-serif';
            ctx.textAlign = 'center';
            ctx.textBaseline = 'middle';
            ctx.fillText(text, canvas.width / 2, canvas.height / 2);
          }
          const texture = new THREE.CanvasTexture(canvas);
          texture.colorSpace = THREE.SRGBColorSpace;
          const material = new THREE.MeshBasicMaterial({
            map: texture,
            transparent: true,
            side: THREE.DoubleSide,
          });
          return new THREE.Mesh(new THREE.PlaneGeometry(width, height), material);
        };
        const disposeObject = (object: THREE_TYPES.Object3D) => {
          object.traverse((obj) => {
            const mesh = obj as THREE_TYPES.Mesh;
            if (mesh.geometry) mesh.geometry.dispose();
            if (mesh.material) {
              const mats: THREE_TYPES.Material[] = Array.isArray(mesh.material)
                ? mesh.material
                : [mesh.material as THREE_TYPES.Material];
              mats.forEach((m) => {
                Object.values(m as unknown as Record<string, unknown>).forEach((v) => {
                  const tex = v as THREE_TYPES.Texture;
                  if (tex && tex.isTexture) tex.dispose();
                });
                m.dispose();
              });
            }
          });
        };

        const createStationGroup = (stationName: string) => {
          const platformGroup = new THREE.Group();

          for (const side of [-1, 1]) {
            const x = side * 3.0;
            const plat = new THREE.Mesh(
              new THREE.BoxGeometry(1.45, 0.52, PLATFORM_LENGTH_M),
              createMaterial('#46535a', 0.86),
            );
            plat.position.set(x, 0.26, 0);
            platformGroup.add(plat);

            const edge = new THREE.Mesh(
              new THREE.BoxGeometry(0.08, 0.03, PLATFORM_LENGTH_M),
              createMaterial('#facc15', 0.72, '#facc15', 0.35),
            );
            edge.position.set(side > 0 ? x - 0.72 : x + 0.72, 0.54, 0);
            platformGroup.add(edge);

            const canopy = new THREE.Mesh(
              new THREE.BoxGeometry(1.85, 0.12, PLATFORM_LENGTH_M * 0.62),
              createMaterial('#5f6b70', 0.68),
            );
            canopy.position.set(x, 2.05, -4);
            platformGroup.add(canopy);

            for (let z = -42; z <= 34; z += 19) {
              const column = new THREE.Mesh(
                new THREE.BoxGeometry(0.12, 1.55, 0.12),
                createMaterial('#c7d0d5', 0.58),
              );
              column.position.set(side > 0 ? x - 0.48 : x + 0.48, 1.25, z);
              platformGroup.add(column);

              const lamp = new THREE.Mesh(
                new THREE.BoxGeometry(0.28, 0.05, 0.28),
                createMaterial('#9ee7ff', 0.45, '#9ee7ff', 0.85),
              );
              lamp.position.set(side > 0 ? x - 0.48 : x + 0.48, 1.82, z);
              platformGroup.add(lamp);
            }
          }

          const stationNameBoard = new THREE.Mesh(
            new THREE.BoxGeometry(2.3, 0.42, 0.08),
            createMaterial('#0f172a', 0.55, '#38bdf8', 0.25),
          );
          stationNameBoard.position.set(-3.0, 1.48, -18);
          platformGroup.add(stationNameBoard);

          const stationNameText = createTextPlane(stationName || 'STATION', 1.95, 0.42, '#0f172a', '#67e8f9');
          stationNameText.position.set(-3.0, 1.5, -17.91);
          platformGroup.add(stationNameText);

          const stopMarker = new THREE.Mesh(
            new THREE.BoxGeometry(2.35, 0.06, 0.08),
            createMaterial('#ef4444', 0.55, '#ef4444', 0.7),
          );
          stopMarker.position.set(0, 0.62, 18);
          platformGroup.add(stopMarker);

          const stopBoard = new THREE.Mesh(
            new THREE.BoxGeometry(0.72, 0.34, 0.08),
            createMaterial('#dc2626', 0.52, '#ef4444', 0.35),
          );
          stopBoard.position.set(2.18, 1.2, 18);
          platformGroup.add(stopBoard);

          const stopBoardText = createTextPlane('STOP', 0.66, 0.3, '#dc2626', '#ffffff');
          stopBoardText.position.set(2.18, 1.22, 18.07);
          platformGroup.add(stopBoardText);

          return platformGroup;
        };

        let stationVisuals: THREE_TYPES.Object3D[] = [];
        let stationVisualKey = '';
        const getStationTargets = () => {
          const { stationStops: stops, targetStopPosition: fallbackTarget } = stateRef.current;
          const rawTargets = stops && stops.length > 0
            ? stops.map((stop) => ({
              position: stop.targetPosition,
              name: stop.stationName,
            }))
            : [{
              position: fallbackTarget,
              name: '终点站',
            }];

          const unique = new Map<string, { position: number; name: string }>();
          rawTargets.forEach((target) => {
            if (Number.isFinite(target.position) && target.position > 0) {
              unique.set(target.position.toFixed(1), target);
            }
          });
          return [...unique.values()].sort((a, b) => a.position - b.position);
        };
        const syncStationVisuals = () => {
          const targets = getStationTargets();
          const nextKey = targets.map((target) => `${target.position.toFixed(1)}:${target.name}`).join('|');
          if (nextKey === stationVisualKey) {
            return;
          }

          stationVisuals.forEach((visual) => {
            scene.remove(visual);
            disposeObject(visual);
          });
          scrollingObjects = scrollingObjects.filter((item) => item.kind !== 'station');
          stationVisuals = targets.map((target) => {
            const visual = createStationGroup(target.name);
            visual.position.z = -target.position;
            visual.visible = false;
            scene.add(visual);
            registerScrollingObject(visual, 'station', false, -target.position);
            return visual;
          });
          stationVisualKey = nextKey;
          console.log('ThreeRailwayView: station visuals synced', targets);
        };

        // Load GLB model
        const loader = new GLTFLoader();
        let modelLoaded = false;
        const fitModelToPreset = (model: THREE_TYPES.Object3D, preset: ModelPreset) => {
          const rawBox = new THREE.Box3().setFromObject(model);
          const rawSize = new THREE.Vector3();
          rawBox.getSize(rawSize);
          const maxDim = Math.max(rawSize.x, rawSize.y, rawSize.z);
          const finalScale = maxDim > 0 ? preset.targetMaxDim / maxDim : 1;

          model.scale.setScalar(finalScale);
          if (preset.rotation) model.rotation.set(...preset.rotation);
          model.updateMatrixWorld(true);

          const scaledBox = new THREE.Box3().setFromObject(model);
          const scaledCenter = new THREE.Vector3();
          scaledBox.getCenter(scaledCenter);
          const targetPosition = new THREE.Vector3(...preset.position);
          model.position.x += targetPosition.x - scaledCenter.x;
          model.position.y += targetPosition.y - scaledBox.min.y;
          model.position.z += targetPosition.z - scaledCenter.z;
          model.updateMatrixWorld(true);

          const finalBox = new THREE.Box3().setFromObject(model);
          const finalSize = new THREE.Vector3();
          finalBox.getSize(finalSize);

          console.log('ThreeRailwayView: GLB model loaded', {
            model: preset.label,
            boxSize: {
              x: rawSize.x,
              y: rawSize.y,
              z: rawSize.z,
            },
            finalScale,
            position: {
              x: model.position.x,
              y: model.position.y,
              z: model.position.z,
            },
            finalBoxSize: {
              x: finalSize.x,
              y: finalSize.y,
              z: finalSize.z,
            },
            scrollCopies: preset.scrollCopies ?? 1,
          });

          return finalSize;
        };
        const tryLoad = (url: string): Promise<void> =>
          new Promise((res, rej) => {
            loader.load(
              url,
              (gltf) => {
                if (cancelled) { res(); return; }
                const model = gltf.scene;
                const preset = MODEL_PRESETS[url] ?? {
                  label: url,
                  targetMaxDim: 12,
                  position: [0, 0.02, -18] as [number, number, number],
                };
                const finalSize = fitModelToPreset(model, preset);
                model.traverse((child) => {
                  const mesh = child as THREE_TYPES.Mesh;
                  if (mesh.isMesh) {
                    mesh.castShadow = true;
                    mesh.receiveShadow = true;
                    const mats: THREE_TYPES.Material[] = Array.isArray(mesh.material)
                      ? mesh.material
                      : [mesh.material as THREE_TYPES.Material];
                    mats.forEach((material) => {
                      material.side = THREE.DoubleSide;
                      material.needsUpdate = true;
                    });
                  }
                });
                const tileLength = Math.max(finalSize.z, preset.targetMaxDim, 12);
                const copyCount = Math.max(1, preset.scrollCopies ?? 1);
                for (let index = 0; index < copyCount; index += 1) {
                  const instance = index === 0 ? model : model.clone(true);
                  instance.position.z = model.position.z - tileLength * index;
                  scene.add(instance);
                  registerScrollingObject(instance);
                }
                modelLoaded = true;
                res();
              },
              undefined,
              (e) => rej(e),
            );
          });

        try { await tryLoad('/models/vehicle/railway.glb'); }
        catch { try { await tryLoad('/models/vehicle/train-cab.glb'); } catch (e2) { console.warn('ThreeRailwayView: both models failed', e2); } }

        if (!cancelled) {
          setLoadState(modelLoaded ? 'ready' : 'error');
          if (!modelLoaded) setErrorMsg('3D 模型加载失败，显示轨道场景');
        }
        resize();

        // Animation loop
        let visualTravel = Number.NaN;
        const clock = new THREE.Clock();
        const animate = () => {
          rafId = requestAnimationFrame(animate);
          const dt = clock.getDelta();
          const { currentState: cs, speedRatio: sr } = stateRef.current;
          const rawTravel = cs?.position ?? cs?.absolutePosition ?? 0;
          const targetTravel = Number.isFinite(rawTravel) ? Math.max(0, rawTravel) : 0;
          if (!Number.isFinite(visualTravel) || Math.abs(targetTravel - visualTravel) > 80) {
            visualTravel = targetTravel;
          } else {
            visualTravel += (targetTravel - visualTravel) * Math.min(1, dt * 12);
          }
          syncStationVisuals();
          applyScroll(visualTravel);
          const phase = (cs?.phase ?? '').toLowerCase();
          if (phase === 'dwell' || phase === 'stopped') {
            platformLight.color.set('#22c55e'); platformLight.intensity = 2.2;
          } else if (phase === 'braking') {
            platformLight.color.set('#fb923c'); platformLight.intensity = 1.8;
          } else {
            platformLight.color.set('#97e8d6'); platformLight.intensity = 1.4;
          }
          camera.position.x = sr > 0.5 ? Math.sin(Date.now() * 0.08) * 0.012 * sr : 0;
          renderer.render(scene, camera);
        };
        animate();

        disposeAll = () => {
          cancelAnimationFrame(rafId);
          ro.disconnect();
          scene.traverse((obj) => {
            const mesh = obj as THREE_TYPES.Mesh;
            if (mesh.geometry) mesh.geometry.dispose();
            if (mesh.material) {
              const mats: THREE_TYPES.Material[] = Array.isArray(mesh.material)
                ? mesh.material
                : [mesh.material as THREE_TYPES.Material];
              mats.forEach((m) => {
                Object.values(m as unknown as Record<string, unknown>).forEach((v) => {
                  const tex = v as THREE_TYPES.Texture;
                  if (tex && tex.isTexture) tex.dispose();
                });
                m.dispose();
              });
            }
          });
          renderer.dispose();
          if (container.contains(renderer.domElement)) {
            container.removeChild(renderer.domElement);
          }
        };

      } catch (err) {
        if (!cancelled) {
          setLoadState('error');
          setErrorMsg(String(err));
        }
      }
    }

    init();
    return () => {
      cancelled = true;
      cancelAnimationFrame(rafId);
      if (disposeAll) disposeAll();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <div style={{ position: 'relative', width: '100%', height: '100%' }} className={className}>
      <div
        ref={mountRef}
        style={{ position: 'absolute', inset: 0, display: loadState === 'error' ? 'none' : 'block' }}
        aria-label="Three.js 3D 轨道驾驶视角"
      />
      {/* Fallback when both models fail — track-only CSS view, no white screen */}
      {loadState === 'error' && (
        <div
          style={{
            position: 'absolute', inset: 0,
            background: 'linear-gradient(180deg,#06090d 0%,#132123 46%,#050608 100%)',
            display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center',
            color: '#6b8e9a', fontSize: 13, gap: 8,
          }}
          aria-label="3D 兜底轨道视图"
        >
          <span style={{ fontSize: 24 }}>🚇</span>
          <span>轨道仿真视图</span>
          {errorMsg && <span style={{ fontSize: 11, color: '#4a6570' }}>{errorMsg}</span>}
        </div>
      )}
      {loadState === 'loading' && (
        <div style={{ position: 'absolute', bottom: 10, right: 12, color: '#4a6570', fontSize: 11, background: 'rgba(0,0,0,0.5)', borderRadius: 4, padding: '2px 8px' }}>
          加载 3D 场景…
        </div>
      )}
    </div>
  );
}
