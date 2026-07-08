import {
  useState,
  useEffect,
  useCallback,
  useRef,
  useMemo,
  lazy,
  Suspense,
} from "react";
import { Map } from "react-map-gl/maplibre";
import { DeckGL } from "@deck.gl/react";
import { ScatterplotLayer, GeoJsonLayer } from "@deck.gl/layers";
import type { MapViewState } from "@deck.gl/core";
import uPlot from "uplot";
import "maplibre-gl/dist/maplibre-gl.css";
import "uplot/dist/uPlot.min.css";
import {
  startSimulation,
  stepSimulation,
  getSnapshot,
  getLineMap,
  getTrackGeometry,
  getEnergy,
  getSignals,
  getSwitches,
  getSpeedLimits,
  getGradients,
  getTunnels,
  getAxleCounters,
  getBumpers,
  getCollisionZones,
  getFloodGates,
} from "../../api/dispatch";
import type {
  EnergySnapshot,
  SimulationSnapshot,
  StationGeo,
  TrainState,
  TrackWaypoint,
  Signal,
  SwitchGeo,
  SpeedLimitZone,
  GradientInfo,
} from "../../types/dispatch";

const Visualization3D = lazy(() => import("../../components/Visualization3D"));
const LineSchematic = lazy(() => import("../../components/LineSchematic"));

/* ============================================================
   Constants
   ============================================================ */

const TRAIN_COLORS = [
  "#38bdf8",
  "#22c55e",
  "#f59e0b",
  "#ef4444",
  "#a78bfa",
  "#fb923c",
  "#f472b6",
  "#2dd4bf",
];
const STATUS_COLOR: Record<string, string> = {
  STOPPED: "#64748b",
  RUNNING: "#22c55e",
  ARRIVING: "#f59e0b",
  DEPARTING: "#38bdf8",
  FINISHED: "#ef4444",
};
const STATUS_LABEL: Record<string, string> = {
  STOPPED: "待发",
  RUNNING: "运行",
  ARRIVING: "进站",
  DEPARTING: "发车",
  FINISHED: "终到",
};

const SPEED_OPTIONS = [
  { label: "1x", steps: 1 },
  { label: "2x", steps: 2 },
  { label: "5x", steps: 5 },
  { label: "10x", steps: 10 },
];

const MAP_STYLE =
  "https://basemaps.cartocdn.com/gl/dark-matter-gl-style/style.json";

const fmtTime = (s: number) =>
  [Math.floor(s / 3600), Math.floor((s % 3600) / 60), Math.floor(s % 60)]
    .map((n) => String(n).padStart(2, "0"))
    .join(":");
const fmtNum = (n: number) => Math.round(n).toLocaleString();

interface PositionResult {
  lng: number;
  lat: number;
  bearing: number;
}

function interpolatePos(
  posMeters: number,
  sorted: StationGeo[],
): PositionResult {
  const km = posMeters / 1000;
  if (km <= sorted[0].km) {
    const b = bearing(sorted[0], sorted[1]);
    return { lat: sorted[0].latitude, lng: sorted[0].longitude, bearing: b };
  }
  if (km >= sorted[sorted.length - 1].km) {
    const b = bearing(sorted[sorted.length - 2], sorted[sorted.length - 1]);
    return {
      lat: sorted[sorted.length - 1].latitude,
      lng: sorted[sorted.length - 1].longitude,
      bearing: b,
    };
  }
  let ni = 1;
  while (ni < sorted.length && sorted[ni].km < km) ni++;
  const p = sorted[ni - 1],
    n = sorted[ni];
  const r = (km - p.km) / (n.km - p.km);
  return {
    lat: p.latitude + (n.latitude - p.latitude) * r,
    lng: p.longitude + (n.longitude - p.longitude) * r,
    bearing: bearing(p, n),
  };
}

function interpolatePosWP(
  posMeters: number,
  wps: TrackWaypoint[],
): PositionResult {
  const km = posMeters / 1000;
  if (wps.length === 0) return { lat: 39.88, lng: 116.31, bearing: 0 };
  if (km <= wps[0].km) {
    const b = wps.length > 1 ? bearingWP(wps[0], wps[1]) : 0;
    return { lat: wps[0].lat, lng: wps[0].lng, bearing: b };
  }
  if (km >= wps[wps.length - 1].km) {
    const b =
      wps.length > 1 ? bearingWP(wps[wps.length - 2], wps[wps.length - 1]) : 0;
    return {
      lat: wps[wps.length - 1].lat,
      lng: wps[wps.length - 1].lng,
      bearing: b,
    };
  }
  let ni = 1;
  while (ni < wps.length && wps[ni].km < km) ni++;
  const p = wps[ni - 1],
    n = wps[ni];
  const r = (km - p.km) / (n.km - p.km);
  return {
    lat: p.lat + (n.lat - p.lat) * r,
    lng: p.lng + (n.lng - p.lng) * r,
    bearing: bearingWP(p, n),
  };
}

function bearing(
  a: { latitude: number; longitude: number },
  b: { latitude: number; longitude: number },
): number {
  const dLng = ((b.longitude - a.longitude) * Math.PI) / 180;
  const lat1 = (a.latitude * Math.PI) / 180;
  const lat2 = (b.latitude * Math.PI) / 180;
  const y = Math.sin(dLng) * Math.cos(lat2);
  const x =
    Math.cos(lat1) * Math.sin(lat2) -
    Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLng);
  return ((Math.atan2(y, x) * 180) / Math.PI + 360) % 360;
}

function bearingWP(
  a: { lat: number; lng: number },
  b: { lat: number; lng: number },
): number {
  const dLng = ((b.lng - a.lng) * Math.PI) / 180;
  const lat1 = (a.lat * Math.PI) / 180;
  const lat2 = (b.lat * Math.PI) / 180;
  const y = Math.sin(dLng) * Math.cos(lat2);
  const x =
    Math.cos(lat1) * Math.sin(lat2) -
    Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLng);
  return ((Math.atan2(y, x) * 180) / Math.PI + 360) % 360;
}

/* ============================================================
   Train Diagram (uPlot)
   ============================================================ */

function TrainDiagram({
  history,
  stations,
  simTime,
}: {
  history: any[];
  stations: StationGeo[];
  simTime: number;
}) {
  const containerRef = useRef<HTMLDivElement>(null);
  const plotRef = useRef<uPlot | null>(null);

  const sorted = useMemo(
    () => [...stations].sort((a, b) => a.id - b.id),
    [stations],
  );

  useEffect(() => {
    const el = containerRef.current;
    if (!el || sorted.length < 2) return;
    const maxKm = sorted[sorted.length - 1].km;

    const allTrains = [
      ...new Set(history.map((p: any) => p.trainId)),
    ] as string[];
    if (allTrains.length === 0) return;

    const series: uPlot.Series[] = [{} as any];

    allTrains.forEach((tid, idx) => {
      series.push({
        stroke: TRAIN_COLORS[idx % TRAIN_COLORS.length],
        width: 1.5,
        label: tid,
        points: { show: false },
      });
    });

    series.push({
      stroke: "rgba(239,68,68,0.3)",
      width: 1,
      dash: [8, 4],
      label: "",
      points: { show: false },
    });

    if (plotRef.current) plotRef.current.destroy();

    const data: (number | null)[][] = [[] as (number | null)[]];
    allTrains.forEach((tid) => {
      data.push(
        history
          .filter((p: any) => p.trainId === tid)
          .sort((a: any, b: any) => a.timeSeconds - b.timeSeconds)
          .map((p: any) => p.positionKm as number),
      );
    });
    data.push([maxKm as unknown as number]);

    const xData = history
      .filter((p: any) => p.trainId === allTrains[0])
      .sort((a: any, b: any) => a.timeSeconds - b.timeSeconds)
      .map((p: any) => p.timeSeconds as number);
    data[0] = xData;

    plotRef.current = new uPlot(
      {
        width: el.clientWidth,
        height: el.clientHeight,
        cursor: { show: true, drag: { x: true, y: false } },
        axes: [
          {
            stroke: "#334155",
            grid: { stroke: "rgba(148,163,184,0.06)", width: 0.5 },
            values: (_u: any, vals: number[]) =>
              vals.map((v: number) => fmtTime(v)),
          },
          {
            stroke: "#334155",
            grid: { stroke: "rgba(148,163,184,0.06)", width: 0.5 },
            values: (_u: any, vals: number[]) =>
              vals.map((v: number) => {
                const s = sorted.find((st) => Math.abs(st.km - v) < 0.01);
                return s ? s.name : "";
              }),
          },
        ],
        series,
        legend: { show: false },
      },
      data as uPlot.AlignedData,
      el,
    );

    return () => {
      plotRef.current?.destroy();
    };
  }, [history, sorted, simTime]);

  useEffect(() => {
    const onResize = () => {
      const el = containerRef.current;
      if (el && plotRef.current)
        plotRef.current.setSize({
          width: el.clientWidth,
          height: el.clientHeight,
        });
    };
    window.addEventListener("resize", onResize);
    return () => window.removeEventListener("resize", onResize);
  }, []);

  if (history.length === 0) {
    return (
      <div className="flex-1 flex items-center justify-center text-slate-600 text-xs">
        启动仿真后显示运行图
      </div>
    );
  }

  return <div ref={containerRef} className="flex-1 w-full" />;
}

/* ============================================================
   Map View (MapLibre + DeckGL)
   ============================================================ */

function MapView({
  stations,
  trains,
  waypoints,
}: {
  stations: StationGeo[];
  trains: TrainState[];
  waypoints: TrackWaypoint[];
}) {
  const sorted = useMemo(
    () => [...stations].sort((a, b) => a.id - b.id),
    [stations],
  );
  const [viewState, setViewState] = useState<MapViewState>({
    longitude: 116.31,
    latitude: 39.88,
    zoom: 12,
    pitch: 0,
    bearing: 0,
  });

  const routeGeoJson = useMemo(() => {
    const pts =
      waypoints.length >= 2
        ? waypoints
        : sorted.map((s) => ({ lng: s.longitude, lat: s.latitude }));
    if (pts.length < 2) return null;
    return {
      type: "FeatureCollection" as const,
      features: [
        {
          type: "Feature" as const,
          geometry: {
            type: "LineString" as const,
            coordinates: pts.map((p: any) => [p.lng, p.lat]),
          },
          properties: {},
        },
      ],
    };
  }, [waypoints, sorted]);

  const stationPoints = useMemo(
    () =>
      sorted.map((s) => ({
        position: [s.longitude, s.latitude],
        name: s.name,
        km: s.km,
        isTerminal: s.id === 1 || s.id === sorted.length,
      })),
    [sorted],
  );

  const trainHeadPoints = useMemo(
    () =>
      trains
        .filter((t) => t.status !== "FINISHED")
        .map((t) => {
          const useWP = waypoints.length >= 2;
          const hp = useWP
            ? interpolatePosWP(t.positionMeters, waypoints)
            : interpolatePos(t.positionMeters, sorted);
          const color = STATUS_COLOR[t.status] || "#64748b";
          const colorRGB = hexToRGB(color);
          return {
            position: [hp.lng, hp.lat] as [number, number],
            name: t.trainName,
            speed: t.speed,
            status: t.status,
            color: colorRGB,
            bearing: hp.bearing,
            carCount: t.carCount ?? 6,
            carLength: t.carLength ?? 19,
            positionMeters: t.positionMeters,
            cars: t.cars,
          };
        }),
    [trains, sorted, waypoints],
  );

  const trainBodyLines = useMemo((): GeoJSON.FeatureCollection | null => {
    const useWP = waypoints.length >= 2;
    const features: GeoJSON.Feature[] = [];
    trains
      .filter((t) => t.status !== "FINISHED")
      .forEach((t) => {
        const hp = useWP
          ? interpolatePosWP(t.positionMeters, waypoints)
          : interpolatePos(t.positionMeters, sorted);
        const trainLenMeters = (t.carCount ?? 6) * (t.carLength ?? 19);
        const tailMeters = t.positionMeters - trainLenMeters;
        const tp = useWP
          ? interpolatePosWP(Math.max(0, tailMeters), waypoints)
          : interpolatePos(Math.max(0, tailMeters), sorted);
        const colorRGB = hexToRGB(STATUS_COLOR[t.status] || "#64748b");
        features.push({
          type: "Feature",
          geometry: {
            type: "LineString",
            coordinates: [
              [tp.lng, tp.lat],
              [hp.lng, hp.lat],
            ],
          },
          properties: { color: colorRGB, trainName: t.trainName },
        });
      });
    if (features.length === 0) return null;
    return { type: "FeatureCollection", features };
  }, [trains, sorted, waypoints]);

  const carPoints = useMemo(() => {
    const useWP = waypoints.length >= 2;
    const pts: any[] = [];
    trains
      .filter((t) => t.status !== "FINISHED" && t.cars && t.cars.length > 1)
      .forEach((t) => {
        const colorRGB = hexToRGB(STATUS_COLOR[t.status] || "#64748b");
        (t.cars || []).slice(1).forEach((c: any) => {
          const cp = useWP
            ? interpolatePosWP(c.positionMeters, waypoints)
            : interpolatePos(c.positionMeters, sorted);
          pts.push({
            position: [cp.lng, cp.lat] as [number, number],
            color: colorRGB,
          });
        });
      });
    return pts;
  }, [trains, sorted, waypoints]);

  const layers = [
    routeGeoJson &&
      new GeoJsonLayer({
        id: "route-glow",
        data: routeGeoJson,
        lineWidthMinPixels: 6,
        getLineColor: [56, 189, 248, 30],
        lineCapRounded: true,
        lineJointRounded: true,
      }),
    routeGeoJson &&
      new GeoJsonLayer({
        id: "route-line",
        data: routeGeoJson,
        lineWidthMinPixels: 2,
        getLineColor: [56, 189, 248, 200],
        lineCapRounded: true,
        lineJointRounded: true,
      }),
    new ScatterplotLayer({
      id: "stations",
      data: stationPoints,
      pickable: true,
      stroked: true,
      filled: true,
      getPosition: (d: any) => d.position,
      getRadius: (d: any) => (d.isTerminal ? 9 : 6),
      getFillColor: [2, 6, 23, 240],
      getLineColor: [56, 189, 248, 240],
      getLineWidth: 2,
      radiusMinPixels: 6,
    }),
    trainBodyLines &&
      trainBodyLines.features.length > 0 &&
      new GeoJsonLayer({
        id: "train-bodies",
        data: trainBodyLines,
        pickable: true,
        lineWidthMinPixels: 4,
        getLineColor: (d: any) => d.properties.color.concat(180) as any,
        lineCapRounded: true,
      }),
    carPoints.length > 0 &&
      new ScatterplotLayer({
        id: "cars",
        data: carPoints,
        pickable: false,
        getPosition: (d: any) => d.position,
        getRadius: 4,
        getFillColor: (d: any) => d.color.concat(150),
        radiusMinPixels: 3.5,
      }),
    trainHeadPoints.length > 0 &&
      new ScatterplotLayer({
        id: "train-heads",
        data: trainHeadPoints,
        pickable: true,
        stroked: true,
        getPosition: (d: any) => d.position,
        getRadius: 10,
        getFillColor: (d: any) => d.color.concat(230),
        getLineColor: [255, 255, 255, 220],
        getLineWidth: 2,
        radiusMinPixels: 8,
        autoHighlight: true,
      }),
  ].filter(Boolean) as any;

  return (
    <DeckGL
      viewState={viewState}
      onViewStateChange={(e: any) => setViewState(e.viewState)}
      controller={{ dragRotate: false, touchRotate: false }}
      layers={layers}
      getTooltip={({ object }: any) => {
        if (!object) return null;
        if (object.properties?.trainName) {
          return {
            html: `<div class="font-semibold text-xs text-[#e2e8f0]">${object.properties.trainName} 车体</div>`,
            style: {
              background: "rgba(15,23,42,0.95)",
              border: "1px solid rgba(148,163,184,0.15)",
              borderRadius: "6px",
              padding: "6px 10px",
              color: "#e2e8f0",
              fontSize: "11px",
            },
          };
        }
        if (object.name !== undefined) {
          const parts = [];
          if (object.name)
            parts.push(
              `<div class="font-semibold text-xs text-[#e2e8f0]">${object.name}</div>`,
            );
          if (object.speed !== undefined)
            parts.push(
              `<div class="text-[10px] text-[#94a3b8]">${Math.round(object.speed)} km/h · ${STATUS_LABEL[object.status] || object.status}</div>`,
            );
          if (object.km !== undefined)
            parts.push(
              `<div class="text-[10px] text-[#64748b]">${object.km.toFixed(1)} km</div>`,
            );
          if (object.carCount !== undefined)
            parts.push(
              `<div class="text-[9px] text-[#617088]">${object.carCount}节编组 · ${object.carLength ?? 19}m/节</div>`,
            );
          if (parts.length === 0) return null;
          return {
            html: parts.join(""),
            style: {
              background: "rgba(15,23,42,0.95)",
              border: "1px solid rgba(148,163,184,0.15)",
              borderRadius: "6px",
              padding: "6px 10px",
              color: "#e2e8f0",
              fontSize: "11px",
            },
          };
        }
        return null;
      }}
    >
      <Map
        reuseMaps
        mapLib={import("maplibre-gl")}
        mapStyle={MAP_STYLE}
        style={{ width: "100%", height: "100%" }}
      />
    </DeckGL>
  );
}

function hexToRGB(hex: string): number[] {
  const m = hex.match(/^#?([a-f\d]{2})([a-f\d]{2})([a-f\d]{2})$/i);
  if (!m) return [100, 100, 100];
  return [parseInt(m[1], 16), parseInt(m[2], 16), parseInt(m[3], 16)];
}

/* ============================================================
   WebSocket Hook
   ============================================================ */

function useWebSocket(
  enabled: boolean,
  onMessage: (data: SimulationSnapshot) => void,
) {
  const wsRef = useRef<WebSocket | null>(null);

  useEffect(() => {
    if (!enabled) {
      wsRef.current?.close();
      wsRef.current = null;
      return;
    }
    const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
    const ws = new WebSocket(
      `${protocol}//${window.location.hostname}:8080/ws/simulation`,
    );
    ws.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data);
        onMessage(data);
      } catch {}
    };
    ws.onerror = () => {};
    ws.onclose = () => {
      wsRef.current = null;
    };
    wsRef.current = ws;
    return () => {
      ws.close();
    };
  }, [enabled, onMessage]);
}

/* ============================================================
   Tiny reusable sub-components (Tailwind-based)
   ============================================================ */

function Dot({ color }: { color: string }) {
  return (
    <span
      className="inline-block w-[5px] h-[5px] rounded-full"
      style={{ backgroundColor: color }}
    />
  );
}

function MonoCell({ children }: { children: React.ReactNode }) {
  return (
    <td className="px-2 py-1.5 text-[#cbd5e1] font-mono text-[10px]">
      {children}
    </td>
  );
}

function ThCell({ children }: { children: React.ReactNode }) {
  return (
    <th className="bg-[#0f172a]/80 text-[#64748b] font-semibold px-2 py-1.5 text-left border-b border-slate-400/[0.06] text-[9px] uppercase">
      {children}
    </th>
  );
}

function TdCentered({
  colSpan,
  children,
}: {
  colSpan: number;
  children: React.ReactNode;
}) {
  return (
    <td
      colSpan={colSpan}
      className="text-center text-[#374151] py-6 px-2 text-[11px]"
    >
      {children}
    </td>
  );
}

function TdName({ children }: { children: React.ReactNode }) {
  return (
    <td className="px-2 py-1.5 text-[#f1f5f9] font-bold font-mono">
      {children}
    </td>
  );
}

/* ============================================================
   Main Dispatch Page
   ============================================================ */

export default function Dispatch() {
  const [snapshot, setSnapshot] = useState<SimulationSnapshot | null>(null);
  const [stations, setStations] = useState<StationGeo[]>([]);
  const [waypoints, setWaypoints] = useState<TrackWaypoint[]>([]);
  const [isRunning, setIsRunning] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [speedIndex, setSpeedIndex] = useState(0);
  const [viewMode, setViewMode] = useState<"2d" | "3d" | "schematic">("2d");
  const [rightTab, setRightTab] = useState<
    "trains" | "arrivals" | "commands" | "energy"
  >("trains");
  const autoRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const energyRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const [energySnapshot, setEnergySnapshot] = useState<EnergySnapshot | null>(
    null,
  );

  // Schematic data
  const [sigData, setSigData] = useState<Signal[]>([]);
  const [swData, setSwData] = useState<SwitchGeo[]>([]);
  const [spLimitData, setSpLimitData] = useState<SpeedLimitZone[]>([]);
  const [gradData, setGradData] = useState<GradientInfo[]>([]);
  const [segmentsData, setSegmentsData] = useState<
    import("../../types/dispatch").TrackSegment[]
  >([]);
  const [tunnelData, setTunnelData] = useState<any[]>([]);
  const [axleCtrData, setAxleCtrData] = useState<any[]>([]);
  const [bumperData, setBumperData] = useState<any[]>([]);
  const [colZoneData, setColZoneData] = useState<any[]>([]);
  const [fgData, setFgData] = useState<any[]>([]);

  useWebSocket(
    isRunning,
    useCallback((data) => {
      setSnapshot(data);
    }, []),
  );

  useEffect(() => {
    getLineMap()
      .then((d) => {
        if (Array.isArray(d) && d.length > 0) setStations(d);
        else setError("failed load line");
      })
      .catch((e) => setError("Line load: " + (e?.message || "network")));
    getTrackGeometry()
      .then((d) => {
        if (d?.trackWaypoints?.length > 0) setWaypoints(d.trackWaypoints);
        if (d?.segments?.length > 0) setSegmentsData(d.segments);
      })
      .catch(() => {});
    // Load schematic data (silent fail)
    getSignals()
      .then(setSigData)
      .catch(() => {});
    getSwitches()
      .then(setSwData)
      .catch(() => {});
    getSpeedLimits()
      .then(setSpLimitData)
      .catch(() => {});
    getGradients()
      .then(setGradData)
      .catch(() => {});
    getTunnels()
      .then(setTunnelData)
      .catch(() => {});
    getAxleCounters()
      .then(setAxleCtrData)
      .catch(() => {});
    getBumpers()
      .then(setBumperData)
      .catch(() => {});
    getCollisionZones()
      .then(setColZoneData)
      .catch(() => {});
    getFloodGates()
      .then(setFgData)
      .catch(() => {});
  }, []);

  useEffect(
    () => () => {
      if (autoRef.current) clearInterval(autoRef.current);
    },
    [],
  );

  const refresh = useCallback(async () => {
    try {
      setError("");
      setSnapshot(await getSnapshot());
    } catch (e: any) {
      setError(e.message);
    }
  }, []);

  const start = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      await startSimulation(3600);
      await refresh();
      setIsRunning(true);
    } catch (e: any) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  }, [refresh]);

  useEffect(() => {
    if (!isRunning) return;
    if (autoRef.current) clearInterval(autoRef.current);
    autoRef.current = setInterval(async () => {
      try {
        await stepSimulation(SPEED_OPTIONS[speedIndex].steps);
      } catch {}
    }, 500);
    return () => {
      if (autoRef.current) clearInterval(autoRef.current);
    };
  }, [isRunning, speedIndex]);

  // 能耗数据轮询（每3秒更新一次）
  useEffect(() => {
    if (!isRunning) {
      if (energyRef.current) {
        clearInterval(energyRef.current);
        energyRef.current = null;
      }
      return;
    }
    const fetchEnergy = async () => {
      try {
        const data = await getEnergy();
        setEnergySnapshot(data);
      } catch {}
    };
    fetchEnergy();
    energyRef.current = setInterval(fetchEnergy, 3000);
    return () => {
      if (energyRef.current) {
        clearInterval(energyRef.current);
        energyRef.current = null;
      }
    };
  }, [isRunning]);

  const stop = useCallback(() => {
    if (autoRef.current) {
      clearInterval(autoRef.current);
      autoRef.current = null;
    }
    if (energyRef.current) {
      clearInterval(energyRef.current);
      energyRef.current = null;
    }
    setIsRunning(false);
  }, []);

  const step = useCallback(async () => {
    setLoading(true);
    try {
      await stepSimulation(1);
      await refresh();
    } catch (e: any) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  }, [refresh]);

  const trains = snapshot?.trains ?? [];
  const arrivals = snapshot?.stationArrivals ?? [];
  const commands = snapshot?.commands ?? [];
  const history = snapshot?.positionHistory ?? [];
  const headways = snapshot?.headways ?? [];
  const energy = snapshot?.totalEnergyKwh ?? 0;
  const tractionKwh = snapshot?.totalTractionKwh ?? 0;
  const regenKwh = snapshot?.totalRegenKwh ?? 0;
  const peakKw = snapshot?.peakPowerKw ?? 0;
  const speedLimit = snapshot?.maxSpeedLimit ?? 80;

  const speedBtnBase =
    "px-2 py-[3px] text-[10px] font-semibold font-mono border-0 border-r border-slate-400/[0.1] cursor-pointer transition-colors";
  const btnDisabled = "opacity-35 cursor-not-allowed";

  return (
    <div className="h-screen flex flex-col bg-[#020617]">
      {/* ═══ Top Bar ═══ */}
      <header className="flex items-center justify-between px-4 h-12 shrink-0 gap-2.5 bg-[#030712] border-b border-slate-400/[0.06] z-20">
        {/* Left brand */}
        <div className="flex items-center gap-2.5">
          <div className="flex items-center gap-2">
            <div className="w-7 h-7 rounded-md bg-blue-500 flex items-center justify-center text-white">
              <svg
                width="14"
                height="14"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth="2.5"
              >
                <circle cx="12" cy="12" r="3" />
                <path d="M12 1v4M12 19v4M1 12h4M19 12h4" />
              </svg>
            </div>
            <div>
              <span className="text-[13px] font-bold text-slate-100">
                调度控制
              </span>
              <span className="block text-[9px] text-slate-500 tracking-[1px] font-semibold">
                DISPATCH · LINE 9
              </span>
            </div>
          </div>
        </div>

        {/* Center clock */}
        <div className="text-center flex-1">
          <span className="block text-[8px] text-slate-500 tracking-[3px] font-bold">
            SIM TIME
          </span>
          <span className="font-mono text-xl font-bold text-slate-100 tracking-[2px]">
            {snapshot ? fmtTime(snapshot.simulationTime) : "00:00:00"}
          </span>
        </div>

        {/* Right controls */}
        <div className="flex items-center gap-1.5">
          {error && (
            <span className="text-[10px] text-red-500 max-w-[150px] overflow-hidden text-ellipsis whitespace-nowrap">
              {error}
            </span>
          )}

          {/* 2D / 3D / Schematic toggle */}
          <div className="flex rounded overflow-hidden border border-slate-400/[0.1]">
            {(["2d", "3d", "schematic"] as const).map((mode, idx) => (
              <button
                key={mode}
                type="button"
                onClick={() => setViewMode(mode)}
                className={`px-2.5 py-[3px] text-[10px] font-semibold font-mono uppercase border-0 cursor-pointer transition-colors ${
                  idx < 2 ? "border-r border-slate-400/[0.1]" : ""
                }`}
                style={{
                  background: viewMode === mode ? "#8b5cf6" : "transparent",
                  color: viewMode === mode ? "#fff" : "#64748b",
                }}
              >
                {mode === "3d" ? "3D" : mode === "schematic" ? "线路图" : "2D"}
              </button>
            ))}
          </div>

          {/* Speed selector */}
          <div className="flex rounded overflow-hidden border border-slate-400/[0.1]">
            {SPEED_OPTIONS.map((o, i) => (
              <button
                key={o.label}
                type="button"
                onClick={() => setSpeedIndex(i)}
                disabled={loading && !isRunning}
                className={`${speedBtnBase} ${
                  i === speedIndex
                    ? "bg-blue-500 text-white"
                    : "bg-transparent text-slate-500"
                } ${loading && !isRunning ? btnDisabled : ""}`}
              >
                {o.label}
              </button>
            ))}
          </div>

          {/* Action buttons */}
          <button
            type="button"
            onClick={start}
            disabled={loading || isRunning}
            className={`flex items-center gap-1 px-3 py-1.5 border-0 rounded text-[11px] font-semibold cursor-pointer transition-opacity ${
              loading || isRunning ? btnDisabled : ""
            }`}
            style={{ background: "#22c55e", color: "#000" }}
          >
            <svg width="12" height="12" viewBox="0 0 24 24" fill="currentColor">
              <polygon points="8,5 19,12 8,19" />
            </svg>
            启动
          </button>
          <button
            type="button"
            onClick={step}
            disabled={loading || isRunning}
            className={`px-3 py-1.5 border-0 rounded text-[11px] font-semibold cursor-pointer transition-opacity ${
              loading || isRunning ? btnDisabled : ""
            }`}
            style={{ background: "rgba(148,163,184,0.08)", color: "#94a3b8" }}
          >
            推进
          </button>
          {isRunning && (
            <button
              type="button"
              onClick={stop}
              className="flex items-center gap-1 px-3 py-1.5 border-0 rounded text-[11px] font-semibold cursor-pointer bg-red-500 text-white"
            >
              <svg
                width="12"
                height="12"
                viewBox="0 0 24 24"
                fill="currentColor"
              >
                <rect x="6" y="6" width="12" height="12" rx="1.5" />
              </svg>
              停止
            </button>
          )}
        </div>
      </header>

      {/* ═══ Body ═══ */}
      <div className="flex flex-1 p-2 overflow-hidden min-h-0 gap-2">
        {/* === Left Column === */}
        <div className="flex-1 min-w-0 flex flex-col gap-2">
          {/* Map / 3D panel */}
          <div className="flex-1 min-h-[200px] bg-[#0f172a] rounded-lg overflow-hidden border border-slate-400/[0.08] flex flex-col">
            {/* Panel header */}
            <div className="flex items-center justify-between px-3 py-1.5 bg-[#0f172a]/80 border-b border-slate-400/[0.06] shrink-0">
              <span className="text-[11px] font-semibold text-slate-100 flex items-center gap-1.5">
                <span
                  className="w-1.5 h-1.5 rounded-full shrink-0"
                  style={{
                    background:
                      viewMode === "3d"
                        ? "#a78bfa"
                        : viewMode === "schematic"
                          ? "#22c55e"
                          : "#38bdf8",
                  }}
                />
                {viewMode === "3d"
                  ? "3D 运行视图"
                  : viewMode === "schematic"
                    ? "线路示意图"
                    : "实时运行图"}
              </span>
              <span className="text-[10px] text-slate-500">
                {stations.length}站 · {snapshot?.activeTrains ?? 0}/
                {snapshot?.totalTrains ?? 0}车
                {viewMode === "schematic" &&
                  sigData.length > 0 &&
                  ` · ${sigData.length}信号机 · ${swData.length}道岔`}
              </span>
            </div>

            {/* Map / 3D / Schematic body */}
            <div className="flex-1 relative h-[calc(100%-33px)]">
              {viewMode === "3d" ? (
                <Suspense
                  fallback={
                    <div className="flex items-center justify-center h-full text-slate-500 text-sm">
                      加载 3D 场景...
                    </div>
                  }
                >
                  <Visualization3D snapshot={snapshot} stations={stations} />
                </Suspense>
              ) : viewMode === "schematic" ? (
                <Suspense
                  fallback={
                    <div className="flex items-center justify-center h-full text-slate-500 text-sm">
                      加载线路示意图...
                    </div>
                  }
                >
                  <LineSchematic
                    stations={stations}
                    waypoints={waypoints}
                    segments={segmentsData}
                    signals={sigData}
                    switches={swData}
                    speedLimits={spLimitData}
                    gradients={gradData}
                    trains={trains}
                    tunnels={tunnelData}
                    axleCounters={axleCtrData}
                    bumpers={bumperData}
                    collisionZones={colZoneData}
                    floodGates={fgData}
                  />
                </Suspense>
              ) : (
                <MapView
                  stations={stations}
                  trains={trains}
                  waypoints={waypoints}
                />
              )}
            </div>

            {/* Stats bar */}
            <div className="flex border-t border-slate-400/[0.06] bg-[#0f172a]/60 shrink-0">
              {[
                { v: snapshot?.activeTrains ?? "--", l: "在线" },
                {
                  v:
                    headways.length > 0
                      ? Math.min(
                          ...headways.map((h: any) => h.timeSeconds),
                        ).toFixed(0) + "s"
                      : "--",
                  l: "最小时距",
                },
                { v: speedLimit + " km/h", l: "区段限速" },
                { v: energy.toFixed(2) + " kWh", l: "净能耗" },
                { v: tractionKwh.toFixed(2) + " kWh", l: "牵引" },
                { v: regenKwh.toFixed(2) + " kWh", l: "回收" },
              ].map((s, i) => (
                <div
                  key={i}
                  className={`flex-1 text-center py-1.5 px-1 ${
                    i < 5 ? "border-r border-slate-400/[0.06]" : ""
                  }`}
                >
                  <span className="block font-mono text-[11px] font-bold text-slate-100">
                    {s.v}
                  </span>
                  <span className="text-[9px] text-slate-500">{s.l}</span>
                </div>
              ))}
            </div>
          </div>

          {/* Train Diagram */}
          <div className="h-[180px] shrink-0 bg-[#0f172a] rounded-lg overflow-hidden border border-slate-400/[0.08] flex flex-col">
            <div className="flex items-center justify-between px-3 py-1.5 bg-[#0f172a]/80 border-b border-slate-400/[0.06] shrink-0">
              <span className="text-[11px] font-semibold text-slate-100 flex items-center gap-1.5">
                <span className="w-1.5 h-1.5 rounded-full bg-amber-500 shrink-0" />
                运行图
              </span>
              <span className="text-[10px] text-slate-500">
                {history.length} 采样点
              </span>
            </div>
            <TrainDiagram
              history={history}
              stations={stations}
              simTime={snapshot?.simulationTime ?? 0}
            />
          </div>
        </div>

        {/* === Right Panel === */}
        <div className="w-[380px] shrink-0 flex flex-col min-h-0 overflow-hidden">
          {/* Tab bar */}
          <div className="flex gap-0.5 mb-2 shrink-0">
            {[
              { id: "trains", label: "列车状态", dot: "#22c55e" },
              { id: "arrivals", label: "到站时刻", dot: "#3b82f6" },
              { id: "commands", label: "调度指令", dot: "#ef4444" },
              { id: "energy", label: "能耗监测", dot: "#f59e0b" },
            ].map((tab) => (
              <button
                key={tab.id}
                type="button"
                onClick={() => setRightTab(tab.id as typeof rightTab)}
                className={`flex items-center gap-1.5 px-3 py-1.5 text-[11px] font-semibold rounded-[5px] cursor-pointer transition-colors ${
                  rightTab === tab.id
                    ? "bg-[#0f172a] text-slate-100 border border-slate-400/[0.12]"
                    : "bg-transparent text-slate-500 border-0"
                }`}
              >
                <span
                  className="inline-block w-[5px] h-[5px] rounded-full"
                  style={{ backgroundColor: tab.dot }}
                />
                {tab.label}
              </button>
            ))}
          </div>

          {/* Tab content */}
          <div className="flex-1 overflow-y-auto min-h-0">
            {/* ── Trains Tab ── */}
            {rightTab === "trains" && (
              <div>
                <div className="bg-[#0f172a] rounded-lg overflow-hidden border border-slate-400/[0.08]">
                  <table className="w-full border-collapse text-[11px]">
                    <thead>
                      <tr>
                        {["车次", "编组", "位置", "速度", "状态", "下站"].map(
                          (h) => (
                            <ThCell key={h}>{h}</ThCell>
                          ),
                        )}
                      </tr>
                    </thead>
                    <tbody>
                      {trains.length === 0 ? (
                        <tr>
                          <TdCentered colSpan={6}>点击「启动」</TdCentered>
                        </tr>
                      ) : (
                        trains.map((t: any) => (
                          <tr
                            key={t.trainId}
                            className="border-b border-slate-400/[0.04]"
                          >
                            <TdName>{t.trainName}</TdName>
                            <MonoCell>{t.carCount ?? 6}节</MonoCell>
                            <MonoCell>{fmtNum(t.positionMeters)}</MonoCell>
                            <MonoCell>
                              {t.speed > 0 ? t.speed.toFixed(0) + " km/h" : "—"}
                            </MonoCell>
                            <td className="px-2 py-1.5">
                              <Dot color={STATUS_COLOR[t.status]} />
                              <span className="ml-1">
                                {STATUS_LABEL[t.status] || t.status}
                              </span>
                            </td>
                            <MonoCell>{t.nextStationKm?.toFixed(1)}</MonoCell>
                          </tr>
                        ))
                      )}
                    </tbody>
                  </table>
                </div>

                {/* Headways table */}
                <div className="mt-2 bg-[#0f172a] rounded-lg overflow-hidden border border-slate-400/[0.08]">
                  <div className="flex items-center justify-between px-3 py-1.5 bg-[#0f172a]/80 border-b border-slate-400/[0.06]">
                    <span className="text-[11px] font-semibold text-slate-100 flex items-center gap-1.5">
                      <span className="w-1.5 h-1.5 rounded-full bg-amber-500 shrink-0" />
                      车头时距
                    </span>
                    <span className="text-[10px] text-slate-500">
                      {headways.length}
                    </span>
                  </div>
                  <table className="w-full border-collapse text-[11px]">
                    <thead>
                      <tr>
                        {["前车", "后车", "距离", "时距", "状态"].map((h) => (
                          <ThCell key={h}>{h}</ThCell>
                        ))}
                      </tr>
                    </thead>
                    <tbody>
                      {headways.length === 0 ? (
                        <tr>
                          <TdCentered colSpan={5}>暂无</TdCentered>
                        </tr>
                      ) : (
                        headways.map((h: any, i: number) => {
                          const c =
                            h.status === "SAFE"
                              ? "#22c55e"
                              : h.status === "WARNING"
                                ? "#f59e0b"
                                : "#ef4444";
                          return (
                            <tr
                              key={i}
                              className="border-b border-slate-400/[0.04]"
                            >
                              <TdName>{h.fromTrainId}</TdName>
                              <TdName>{h.toTrainId}</TdName>
                              <MonoCell>{fmtNum(h.distanceMeters)}</MonoCell>
                              <MonoCell>{h.timeSeconds.toFixed(0)}s</MonoCell>
                              <td className="px-2 py-1.5">
                                <span
                                  className="inline-block px-1.5 py-px rounded-[3px] text-[9px] font-semibold"
                                  style={{
                                    color: c,
                                    background: c + "18",
                                  }}
                                >
                                  {h.status === "SAFE"
                                    ? "正常"
                                    : h.status === "WARNING"
                                      ? "注意"
                                      : "危险"}
                                </span>
                              </td>
                            </tr>
                          );
                        })
                      )}
                    </tbody>
                  </table>
                </div>
              </div>
            )}

            {/* ── Arrivals Tab ── */}
            {rightTab === "arrivals" && (
              <div className="bg-[#0f172a] rounded-lg overflow-hidden border border-slate-400/[0.08]">
                <table className="w-full border-collapse text-[11px]">
                  <thead>
                    <tr>
                      {["车次", "车站", "到站", "发车", "停站"].map((h) => (
                        <ThCell key={h}>{h}</ThCell>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {arrivals.length === 0 ? (
                      <tr>
                        <TdCentered colSpan={5}>暂无到站记录</TdCentered>
                      </tr>
                    ) : (
                      [...arrivals].reverse().map((a: any, i: number) => (
                        <tr
                          key={i}
                          className="border-b border-slate-400/[0.04]"
                        >
                          <TdName>{a.trainId}</TdName>
                          <td className="px-2 py-1.5 font-semibold text-slate-100">
                            {a.stationName}
                          </td>
                          <MonoCell>{fmtTime(a.arrivalTimeSeconds)}</MonoCell>
                          <MonoCell>
                            {a.departureTimeSeconds > 0
                              ? fmtTime(a.departureTimeSeconds)
                              : "—"}
                          </MonoCell>
                          <MonoCell>
                            {a.dwellSeconds > 0
                              ? a.dwellSeconds.toFixed(0) + "s"
                              : "—"}
                          </MonoCell>
                        </tr>
                      ))
                    )}
                  </tbody>
                </table>
              </div>
            )}

            {/* ── Commands Tab ── */}
            {rightTab === "commands" && (
              <div className="bg-[#0f172a] rounded-lg overflow-hidden border border-slate-400/[0.08] flex-1 min-h-0 flex flex-col">
                <div className="overflow-y-auto flex-1">
                  {commands.length === 0 ? (
                    <div className="text-center text-[#374151] py-6 text-[11px]">
                      暂无调度指令
                    </div>
                  ) : (
                    commands.map((c: any, i: number) => {
                      const cmdMap: Record<string, [string, string]> = {
                        DEPART: ["发车", "#22c55e"],
                        HOLD: ["等待", "#f59e0b"],
                        SLOW: ["减速", "#ef4444"],
                        ARRIVE: ["到站", "#3b82f6"],
                        FINISH: ["终到", "#a78bfa"],
                      };
                      const [label, color] = cmdMap[c.commandType] || [
                        c.commandType,
                        "#64748b",
                      ];
                      return (
                        <div
                          key={i}
                          className="flex items-center gap-1.5 px-3 py-1.5 border-b border-slate-400/[0.04] text-[11px]"
                        >
                          <span
                            className="inline-block px-1.5 py-px rounded-[3px] text-[9px] font-bold"
                            style={{ color, background: color + "14" }}
                          >
                            {label}
                          </span>
                          <span className="font-bold text-slate-100 font-mono text-[10px]">
                            {c.trainId}
                          </span>
                          <span className="text-[#94a3b8] flex-1 text-[10px]">
                            {c.reason}
                          </span>
                        </div>
                      );
                    })
                  )}
                </div>
              </div>
            )}

            {/* ── Energy Tab ── */}
            {rightTab === "energy" && (
              <div className="flex flex-col gap-2">
                <div className="bg-[#0f172a] rounded-lg overflow-hidden border border-slate-400/8">
                  <div className="flex items-center justify-between px-3 py-1.5 bg-[#0f172a]/80 border-b border-slate-400/6">
                    <span className="text-[11px] font-semibold text-slate-100 flex items-center gap-1.5">
                      <span className="w-1.5 h-1.5 rounded-full bg-amber-500 shrink-0" />
                      能耗总览
                    </span>
                  </div>
                  <div className="p-3 space-y-2">
                    {energySnapshot ? (
                      <>
                        <div className="grid grid-cols-2 gap-2">
                          {[
                            [
                              "牵引能耗",
                              energySnapshot.totalTractionKwh.toFixed(1),
                              "kWh",
                              "#38bdf8",
                            ],
                            [
                              "回收能量",
                              energySnapshot.totalRegenKwh.toFixed(1),
                              "kWh",
                              "#22c55e",
                            ],
                            [
                              "净能耗",
                              energySnapshot.netEnergyKwh.toFixed(1),
                              "kWh",
                              "#f59e0b",
                            ],
                            [
                              "峰值功率",
                              energySnapshot.maxPeakKw.toFixed(0),
                              "kW",
                              "#ef4444",
                            ],
                          ].map(([l, v, u, c]) => (
                            <div
                              key={l as string}
                              className="p-2 rounded-lg text-center"
                              style={{
                                background: "rgba(15,23,42,0.6)",
                                border: "1px solid rgba(148,163,184,0.06)",
                              }}
                            >
                              <div className="text-[9px] text-slate-500">
                                {l}
                              </div>
                              <div
                                className="text-lg font-bold font-mono"
                                style={{ color: c as string }}
                              >
                                {v as string}
                              </div>
                              <div className="text-[9px] text-slate-600">
                                {u}
                              </div>
                            </div>
                          ))}
                        </div>

                        {/* 供电风险 */}
                        <div
                          className="p-2.5 rounded-lg flex items-center gap-3"
                          style={{
                            background: "rgba(15,23,42,0.6)",
                            border: "1px solid rgba(148,163,184,0.06)",
                          }}
                        >
                          <div
                            className="w-12 h-12 rounded-full flex items-center justify-center text-[10px] font-bold shrink-0"
                            style={{
                              color:
                                energySnapshot.riskLevel === "safe"
                                  ? "#22c55e"
                                  : energySnapshot.riskLevel === "warning"
                                    ? "#f59e0b"
                                    : "#ef4444",
                              background:
                                energySnapshot.riskLevel === "safe"
                                  ? "rgba(34,197,94,0.1)"
                                  : energySnapshot.riskLevel === "warning"
                                    ? "rgba(245,158,11,0.1)"
                                    : "rgba(239,68,68,0.1)",
                              fontFamily: "'Orbitron', monospace",
                            }}
                          >
                            {energySnapshot.riskLevel === "safe"
                              ? "安全"
                              : energySnapshot.riskLevel === "warning"
                                ? "警告"
                                : "危险"}
                          </div>
                          <div className="text-[10px] space-y-0.5">
                            <div className="text-slate-400">
                              供电阈值:{" "}
                              <span className="text-slate-200">
                                {energySnapshot.powerSupplyThreshold.toFixed(0)}{" "}
                                kW
                              </span>
                            </div>
                            <div className="text-slate-400">
                              峰值/阈值:{" "}
                              <span className="text-slate-200">
                                {(energySnapshot.thresholdRatio * 100).toFixed(
                                  0,
                                )}
                                %
                              </span>
                            </div>
                          </div>
                        </div>
                      </>
                    ) : (
                      <div className="text-center text-[#374151] py-4 text-[11px]">
                        启动仿真后显示能耗数据
                      </div>
                    )}
                  </div>
                </div>

                {/* 各列车能耗明细 */}
                {energySnapshot?.energyRecords?.length ? (
                  <div className="bg-[#0f172a] rounded-lg overflow-hidden border border-slate-400/8">
                    <table className="w-full border-collapse text-[11px]">
                      <thead>
                        <tr>
                          {["车次", "牵引", "回收", "净耗"].map((h) => (
                            <ThCell key={h}>{h}</ThCell>
                          ))}
                        </tr>
                      </thead>
                      <tbody>
                        {energySnapshot.energyRecords.map((r) => (
                          <tr
                            key={r.trainId}
                            className="border-b border-slate-400/4"
                          >
                            <TdName>
                              {"TC" + String(r.trainId).padStart(2, "0")}
                            </TdName>
                            <MonoCell>
                              {r.totalTractionEnergyKwh.toFixed(2)}
                            </MonoCell>
                            <MonoCell>
                              {r.totalRegenEnergyKwh.toFixed(2)}
                            </MonoCell>
                            <MonoCell>{r.netEnergyKwh.toFixed(2)}</MonoCell>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                ) : null}
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
