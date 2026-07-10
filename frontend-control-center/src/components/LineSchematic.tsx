import {
  useCallback,
  useMemo,
  useRef,
  useState,
  useEffect,
} from "react";
import type {
  Signal,
  SwitchGeo,
  StationGeo,
  TrackSegment,
  TrackWaypoint,
  SpeedLimitZone,
  GradientInfo,
  TrainState,
} from "../types/dispatch";

/* ============================================================
   LineSchematic Pro — 全功能轨道交通线路示意图
   Phase1: 图层控制 + 悬停Tooltip + 位置导航
   Phase2: 道岔详图 + 隧道渲染
   Phase3: 车档/防淹门/碰撞区/计轴器/竖曲线
   支持鼠标滚轮缩放 + 拖拽平移
   ============================================================ */

// ---- 扩展数据类型 ----
interface Tunnel { 索引编号: number; 隧道所处Seg编号: number; 起点所处Seg偏移量: number; 终点所处Seg偏移量: number; 隧道长度: number }
interface AxleCounter { 索引编号: number; 计轴器名称: string; 所处公里标: number }
interface Bumper { 索引编号: number; 所属Seg编号: number; 所属Seg偏移量: number; 车档类型: number; 互联互通编号: number }
interface CollisionZone { 索引编号: number; 点类型: number; 车档编号: number; 碰撞点限速: number; 逻辑区段IDs: number[]; 逻辑区段数量: number }
interface FloodGate { 索引编号: number; 所处Seg编号: number; 所处Seg偏移量: number; 防淹门区域长度: number; 所属CI: number }

interface Props {
  stations: StationGeo[];
  waypoints: TrackWaypoint[];
  segments: TrackSegment[];
  signals: Signal[];
  switches: SwitchGeo[];
  speedLimits: SpeedLimitZone[];
  gradients: GradientInfo[];
  trains: TrainState[];
  tunnels?: Tunnel[];
  axleCounters?: AxleCounter[];
  bumpers?: Bumper[];
  collisionZones?: CollisionZone[];
  floodGates?: FloodGate[];
}

const C = {
  bg: "#020617",
  trackDown: "#38bdf8", trackUp: "#f472b6", trackDepot: "#475569",
  station: "#e2e8f0", stationBg: "#0f172a",
  signalGreen: "#22c55e", signalYellow: "#f59e0b", signalRed: "#ef4444", signalEntry: "#a78bfa",
  switchPoint: "#8b5cf6",
  speedLimitLow: "#ef4444", speedLimitMid: "#f59e0b",
  gradientUp: "#ef4444", gradientDown: "#3b82f6",
  gridLine: "rgba(148,163,184,0.04)",
  text: "#64748b", textBright: "#94a3b8",
  train: "#22c55e", trainArriving: "#f59e0b", trainDeparting: "#38bdf8",
  tunnelFill: "rgba(100,116,139,0.12)", tunnelStroke: "rgba(100,116,139,0.3)",
  dangerZone: "rgba(239,68,68,0.18)", bumper: "#f97316",
  floodGate: "rgba(59,130,246,0.3)", axleDot: "#a78bfa",
};

export default function LineSchematic({
  stations, waypoints, segments, signals, switches: switchData,
  speedLimits, gradients, trains,
  tunnels = [], axleCounters = [], bumpers = [], collisionZones = [], floodGates = [],
}: Props) {
  const PAD = { top: 35, right: 40, bottom: 100, left: 70 };
  const TRACK_Y = 155, GRADIENT_Y = 285, STATION_Y = TRACK_Y - 55;

  const [svgW, setSvgW] = useState(1200);
  const svgH = 550;

  // Zoom & Pan
  const [scale, setScale] = useState(1);
  const [panX, setPanX] = useState(0);
  const [panY, setPanY] = useState(0);
  const scaleRef = useRef(scale);
  const panRef = useRef({ x: 0, y: 0 });
  const dragging = useRef(false);
  const dragStart = useRef({ x: 0, y: 0 });
  const svgContainerRef = useRef<HTMLDivElement>(null);

  // Layer visibility
  const [layers, setLayers] = useState({
    signals: true, switches: true, speedLimits: true, gradients: true,
    tunnels: true, axleCounters: false, bumpers: true, collisionZones: true,
    floodGates: false, grid: true,
  });
  const toggleLayer = (k: keyof typeof layers) => setLayers((p) => ({ ...p, [k]: !p[k] }));

  // Hover tooltip
  const [tooltip, setTooltip] = useState<{ x: number; y: number; html: string } | null>(null);
  const [tooltipSticky, setTooltipSticky] = useState<{ x: number; y: number; html: string } | null>(null);

  // Switch detail popup
  const [detailSwitch, setDetailSwitch] = useState<SwitchGeo | null>(null);

  // Position search
  const [searchKm, setSearchKm] = useState("");
  const jumpToKm = useCallback(() => {
    const km = parseFloat(searchKm);
    if (isNaN(km)) return;
    const targetX = toX(km);
    setPanX(-(targetX - svgW / 2) * scale + svgW / 2 - targetX);
    setPanY(0);
  }, [searchKm, scale, svgW]);

  useEffect(() => { scaleRef.current = scale; }, [scale]);
  useEffect(() => { panRef.current = { x: panX, y: panY }; }, [panX, panY]);

  // Responsive
  useEffect(() => {
    const r = () => { const el = document.getElementById("schematic-root"); if (el) setSvgW(Math.max(1000, el.clientWidth - 16)); };
    r(); window.addEventListener("resize", r); return () => window.removeEventListener("resize", r);
  }, []);

  // Zoom
  const handleWheel = useCallback((e: React.WheelEvent) => {
    e.preventDefault();
    const rect = svgContainerRef.current?.getBoundingClientRect();
    if (!rect) return;
    const mx = e.clientX - rect.left, my = e.clientY - rect.top;
    const delta = e.deltaY > 0 ? -0.1 : 0.1;
    setScale((prev) => {
      const ns = Math.min(5, Math.max(0.3, prev + delta));
      scaleRef.current = ns;
      const ratio = ns / prev;
      setPanX((px) => mx - ratio * (mx - px));
      setPanY((py) => my - ratio * (my - py));
      return ns;
    });
  }, []);
  const zoomIn = useCallback(() => setScale((p) => Math.min(5, p + 0.2)), []);
  const zoomOut = useCallback(() => setScale((p) => Math.max(0.3, p - 0.2)), []);
  const zoomReset = useCallback(() => { setScale(1); setPanX(0); setPanY(0); }, []);

  // Pan
  const handleMouseDown = useCallback((e: React.MouseEvent) => {
    if (e.button !== 0) return;
    dragging.current = true;
    dragStart.current = { x: e.clientX - panRef.current.x, y: e.clientY - panRef.current.y };
    if (svgContainerRef.current) svgContainerRef.current.style.cursor = "grabbing";
  }, []);
  useEffect(() => {
    const mm = (e: MouseEvent) => { if (!dragging.current) return; setPanX(e.clientX - dragStart.current.x); setPanY(e.clientY - dragStart.current.y); };
    const mu = () => { dragging.current = false; if (svgContainerRef.current) svgContainerRef.current.style.cursor = "grab"; };
    window.addEventListener("mousemove", mm); window.addEventListener("mouseup", mu);
    return () => { window.removeEventListener("mousemove", mm); window.removeEventListener("mouseup", mu); };
  }, []);

  // segByKm lookup
  const segByKm = useMemo(() => {
    const map = new Map<number, TrackSegment>();
    const sortedWps = [...waypoints].sort((a, b) => a.km - b.km);
    for (const seg of segments) {
      const sw = sortedWps.find((w) => Math.abs(w.km - seg.startPointId * 0.05) < 0.3);
      if (sw) map.set(seg.id, { ...seg, _approxKm: sw.km } as any);
    }
    return map;
  }, [waypoints, segments]);

  // Compute all positions
  const data = useMemo(() => {
    const sortedStations = [...stations].sort((a, b) => a.km - b.km);
    const minKm = -0.3, maxKm = 16.8;
    const toXFn = (km: number) => PAD.left + ((km - minKm) / (maxKm - minKm)) * (svgW - PAD.left - PAD.right);

    // Signals
    const sigPositions: { x: number; s: Signal; km: number; onUp: boolean }[] = [];
    for (const sig of signals) {
      const seg = segByKm.get(sig.所处Seg编号);
      const baseKm = seg ? (seg as any)._approxKm : sig.索引编号 * 0.05;
      const km = baseKm + (sig.所处Seg偏移量 || 0) / 100000;
      if (km >= minKm && km <= maxKm) sigPositions.push({ x: toXFn(km), s: sig, km, onUp: sig.索引编号 % 2 === 0 });
    }

    // Switches with actual track geometry
    // Build wpById: waypoint index+1 → km
    const wpByIndex = new Map<number, TrackWaypoint>();
    waypoints.forEach((wp, i) => wpByIndex.set(i + 1, wp));

    // Build segById
    const segById = new Map<number, TrackSegment>();
    segments.forEach((s) => segById.set(s.id, s));

    // Helper: get waypoint km by index (1-based)
    const wpKm = (idx: number) => {
      const wp = wpByIndex.get(idx);
      return wp ? wp.km : (idx * 0.05);
    };

    const swGeometries: { sw: SwitchGeo; converge: { x1: number; y1: number; x2: number; y2: number }; normal: { x1: number; y1: number; x2: number; y2: number }; reverse: { x1: number; y1: number; x2: number; y2: number }; junctionX: number; junctionKm: number }[] = [];
    for (const sw of switchData) {
      const cSeg = segById.get(sw.汇合SegID);
      const nSeg = segById.get(sw.定位SegID);
      const rSeg = segById.get(sw.反位SegID);
      if (!cSeg || !nSeg || !rSeg) continue;

      // Find the junction point - the waypoint shared by at least 2 of the 3 segments
      const pts = new Set<number>();
      if (cSeg) { pts.add(cSeg.startPointId); pts.add(cSeg.endPointId); }
      if (nSeg) { pts.add(nSeg.startPointId); pts.add(nSeg.endPointId); }
      if (rSeg) { pts.add(rSeg.startPointId); pts.add(rSeg.endPointId); }

      // Find the point that appears in at least 2 segments (the junction)
      const counts = new Map<number, number>();
      for (const p of pts) counts.set(p, (counts.get(p) || 0) + 1);
      // Actually, we need to count appearances across segments
      const allPts: number[] = [];
      [cSeg, nSeg, rSeg].forEach((s) => {
        if (s) { allPts.push(s.startPointId); allPts.push(s.endPointId); }
      });
      const jCounts = new Map<number, number>();
      allPts.forEach((p) => jCounts.set(p, (jCounts.get(p) || 0) + 1));
      let junctionPt = 0;
      for (const [p, c] of jCounts) { if (c >= 2) { junctionPt = p; break; } }
      if (!junctionPt) continue;

      const jKm = wpKm(junctionPt);
      if (jKm < -0.3 || jKm > 16.8) continue;

      const jX = toXFn(jKm);
      const BASE_Y = TRACK_Y;

      // Converging segment: draw from non-junction end to junction
      const cOther = cSeg.startPointId === junctionPt ? cSeg.endPointId : cSeg.startPointId;
      const cOtherKm = wpKm(cOther);
      const cX1 = toXFn(cOtherKm), cY1 = BASE_Y;
      const cX2 = jX, cY2 = BASE_Y;

      // Normal segment: draw from junction to other end (straight ahead)
      const nOther = nSeg.startPointId === junctionPt ? nSeg.endPointId : nSeg.startPointId;
      const nOtherKm = wpKm(nOther);
      const nX1 = jX, nY1 = BASE_Y;
      const nX2 = toXFn(nOtherKm), nY2 = BASE_Y;

      // Reverse segment: draw from junction diverging downward (side track)
      const rOther = rSeg.startPointId === junctionPt ? rSeg.endPointId : rSeg.startPointId;
      const rOtherKm = wpKm(rOther);
      const rX1 = jX, rY1 = BASE_Y;
      const rX2 = toXFn(rOtherKm), rY2 = BASE_Y + 24;

      swGeometries.push({ sw, converge: { x1: cX1, y1: cY1, x2: cX2, y2: cY2 }, normal: { x1: nX1, y1: nY1, x2: nX2, y2: nY2 }, reverse: { x1: rX1, y1: rY1, x2: rX2, y2: rY2 }, junctionX: jX, junctionKm: jKm });
    }

    // Speed limit zones
    const speedZones = speedLimits
      .filter((z) => z.限速值 > 0 && z.限速值 < 150)
      .slice(0, 40)
      .map((z) => {
        const seg = segByKm.get(z.限速区段所处seg编号);
        const baseKm = seg ? (seg as any)._approxKm : z.索引编号 * 0.05;
        return { fromKm: baseKm + (z.起点所处seg偏移量 || 0) / 100000, toKm: baseKm + (z.终点所处seg偏移量 || 300) / 100000, limit: z.限速值, switchId: z.关联道岔编号 };
      });

    // Gradients
    const gradBars = gradients
      .filter((g) => g.坡度值 !== 0)
      .slice(0, 60)
      .map((g) => {
        const seg = segByKm.get(g.坡度起点所处seg编号);
        const baseKm = seg ? (seg as any)._approxKm : (g.索引编号 / gradients.length) * maxKm;
        return { fromKm: baseKm + (g.坡度起点所处seg偏移量 || 0) / 100000, toKm: baseKm + (g.坡度终点所处seg偏移量 || (g.坡度值 > 0 ? 200 : 150)) / 100000, value: g.坡度值, idx: g.索引编号, verticalRadius: (g as any).竖曲线半径 || 0 };
      });

    // Tunnels
    const tunnelZones = tunnels.map((t) => {
      const seg = segByKm.get(t.隧道所处Seg编号);
      const baseKm = seg ? (seg as any)._approxKm : t.索引编号 * 0.07;
      return { fromKm: baseKm + (t.起点所处Seg偏移量 || 0) / 100000, toKm: baseKm + (t.终点所处Seg偏移量 || 0) / 100000, length: t.隧道长度 };
    });

    // Axle counters
    const axlePositions = axleCounters.map((a) => ({
      x: toXFn(a.所处公里标 / 100000), name: a.计轴器名称, km: a.所处公里标 / 100000,
    }));

    // Bumpers
    const bumperPositions = bumpers.map((b) => {
      const seg = segByKm.get(b.所属Seg编号);
      const km = seg ? (seg as any)._approxKm : b.索引编号 * 0.07;
      return { x: toXFn(km + (b.所属Seg偏移量 || 0) / 100000), type: b.车档类型 };
    });

    // Collision zones
    const colZones = collisionZones.map((c) => {
      const bk = bumperPositions.find((_, i) => i + 1 === c.车档编号);
      return { x: bk ? bk.x : toXFn(c.索引编号 * 0.05), limit: c.碰撞点限速 };
    });

    // Flood gates
    const fgPositions = floodGates.map((f) => {
      const seg = segByKm.get(f.所处Seg编号);
      const km = seg ? (seg as any)._approxKm : f.索引编号 * 0.07;
      return { x: toXFn(km + (f.所处Seg偏移量 || 0) / 100000), len: f.防淹门区域长度, ci: f.所属CI };
    });

    return { stns: sortedStations, toX: toXFn, minKm, maxKm, sigPositions, swGeometries, speedZones, gradBars, tunnelZones, axlePositions, bumperPositions, colZones, fgPositions };
  }, [stations, waypoints, signals, switchData, speedLimits, gradients, segments, segByKm, svgW, tunnels, axleCounters, bumpers, collisionZones, floodGates]);

  const _toX = useCallback((km: number) => data.toX(km), [data.toX]);
  const { stns, toX, minKm, maxKm, sigPositions, swGeometries, speedZones, gradBars, tunnelZones, axlePositions, bumperPositions, colZones, fgPositions } = data;

  const trainMarkers = useMemo(
    () => trains.filter((t) => t.status !== "FINISHED").map((t) => ({ x: toX(t.positionMeters / 1000), name: t.trainName, speed: t.speed, status: t.status, posM: t.positionMeters })),
    [trains, toX],
  );

  const zoomPct = Math.round(scale * 100);

  return (
    <div id="schematic-root" ref={svgContainerRef}
      className="w-full h-full bg-[#020617] overflow-hidden relative select-none"
      style={{ cursor: "grab" }}
      onWheel={handleWheel} onMouseDown={handleMouseDown}
    >
      {/* ===== LAYER PANEL (left) ===== */}
      <div className="absolute top-2 left-2 z-20 bg-[#0f172a]/95 border border-slate-400/10 rounded-lg p-2.5 min-w-[140px] shadow-xl">
        <div className="text-[10px] font-bold text-slate-300 mb-2 font-mono tracking-wide">图层</div>
        {([
          ["signals", `信号机 ${sigPositions.length}`],
          ["switches", `道岔 ${swGeometries.length}`],
          ["speedLimits", `限速 ${speedZones.length}`],
          ["gradients", "坡度剖面"],
          ["tunnels", `隧道 ${tunnelZones.length}`],
          ["collisionZones", "碰撞区"],
          ["bumpers", `车档 ${bumperPositions.length}`],
          ["axleCounters", "计轴器"],
          ["floodGates", "防淹门"],
          ["grid", "公里标网格"],
        ] as [keyof typeof layers, string][]).map(([k, label]) => (
          <label key={k} className="flex items-center gap-2 py-0.5 cursor-pointer hover:text-slate-200 text-[#94a3b8] text-[10px] font-mono">
            <input type="checkbox" checked={layers[k]} onChange={() => toggleLayer(k)} className="w-3 h-3 accent-blue-500 cursor-pointer" />
            {label}
          </label>
        ))}
      </div>

      {/* ===== SEARCH BAR (top center) ===== */}
      <div className="absolute top-2 left-1/2 -translate-x-1/2 z-20 flex items-center gap-1.5 bg-[#0f172a]/95 border border-slate-400/10 rounded-lg px-2.5 py-1.5 shadow-xl">
        <span className="text-[10px] text-slate-400 font-mono">km</span>
        <input
          value={searchKm} onChange={(e) => setSearchKm(e.target.value)}
          onKeyDown={(e) => e.key === "Enter" && jumpToKm()}
          placeholder="跳转..."
          className="w-[70px] bg-transparent border-none outline-none text-[11px] text-slate-200 font-mono placeholder:text-slate-600"
        />
        <button onClick={jumpToKm} className="text-[10px] text-blue-400 font-mono hover:text-blue-300 border-none bg-transparent cursor-pointer">Go</button>
        <span className="text-[9px] text-slate-600 font-mono">{minKm}~{maxKm}km</span>
      </div>

      {/* ===== ZOOM CONTROLS (right bottom) ===== */}
      <div className="absolute bottom-3 right-3 flex items-center gap-1 z-20">
        <button onClick={zoomOut} className="w-7 h-7 rounded bg-[#0f172a] border border-slate-400/15 text-slate-300 text-sm leading-none flex items-center justify-center hover:bg-[#1e293b] transition-colors" title="缩小">−</button>
        <span className="text-[10px] text-slate-400 font-mono min-w-[40px] text-center cursor-pointer hover:text-slate-200" onClick={zoomReset} title="点击重置">{zoomPct}%</span>
        <button onClick={zoomIn} className="w-7 h-7 rounded bg-[#0f172a] border border-slate-400/15 text-slate-300 text-sm leading-none flex items-center justify-center hover:bg-[#1e293b] transition-colors" title="放大">+</button>
        <button onClick={zoomReset} className="w-7 h-7 rounded bg-[#0f172a] border border-slate-400/15 text-slate-300 text-xs leading-none flex items-center justify-center hover:bg-[#1e293b] transition-colors ml-1" title="重置视图">↺</button>
      </div>

      {/* ===== HINT ===== */}
      {scale === 1 && panX === 0 && panY === 0 && (
        <div className="absolute bottom-3 left-44 text-[9px] text-slate-600 font-mono pointer-events-none z-10">滚轮缩放 · 拖拽平移 · 悬停查看详情 · 点击道岔看详图</div>
      )}

      <svg width={svgW} height={svgH} viewBox={`0 0 ${svgW} ${svgH}`} style={{ display: "block", minWidth: "100%" }}>
        <rect x={0} y={0} width={svgW} height={svgH} fill={C.bg} />

        <g transform={`translate(${panX}, ${panY}) scale(${scale})`}>

          {/* ===== GRID ===== */}
          {layers.grid && Array.from({ length: 18 }, (_, i) => {
            const x = toX(i);
            return (
              <g key={`g${i}`}>
                <line x1={x} y1={10} x2={x} y2={PAD.top + 380} stroke={C.gridLine} strokeWidth={0.5} />
                <text x={x} y={PAD.top + 390} textAnchor="middle" fill={C.text} fontSize={8} fontFamily="monospace" style={{ cursor: "pointer" }}
                  onClick={() => { setSearchKm(String(i)); /* jump */ }}>{i}km</text>
              </g>
            );
          })}

          {/* ===== TITLE ===== */}
          <text x={PAD.left + 10} y={20} fill={C.textBright} fontSize={12} fontFamily="monospace" fontWeight="bold">北京地铁9号线 · 线路示意图</text>
          <text x={PAD.left + 10} y={33} fill={C.text} fontSize={8} fontFamily="monospace">
            郭公庄 → 国家图书馆 (16.049km) · {stns.length}站 · {sigPositions.length}信号 · {swGeometries.length}道岔 · {tunnelZones.length}隧道
          </text>

          {/* ===== TUNNELS (Phase2) ===== */}
          {layers.tunnels && tunnelZones.map((t, i) => {
            const x1 = toX(t.fromKm), x2 = toX(t.toKm);
            return (
              <g key={`tun${i}`}>
                <rect x={x1} y={TRACK_Y - 36} width={Math.max(4, x2 - x1)} height={68} rx={3}
                  fill={C.tunnelFill} stroke={C.tunnelStroke} strokeWidth={0.5} strokeDasharray="2,4" />
                {Math.abs(x2 - x1) > 60 && (
                  <text x={(x1 + x2) / 2} y={TRACK_Y - 39} textAnchor="middle" fill={C.text} fontSize={6.5} fontFamily="monospace">
                    隧道 {t.length > 0 ? (t.length).toFixed(0) + "m" : ""}
                  </text>
                )}
              </g>
            );
          })}

          {/* ===== COLLISION ZONES (Phase3) ===== */}
          {layers.collisionZones && colZones.map((c, i) => (
            <g key={`cz${i}`}>
              <rect x={c.x - 10} y={TRACK_Y - 14} width={20} height={28} rx={2}
                fill="rgba(239,68,68,0.12)" stroke={C.speedLimitLow} strokeWidth={0.8} strokeDasharray="2,2" />
              <text x={c.x} y={TRACK_Y - 18} textAnchor="middle" fill={C.speedLimitLow} fontSize={6} fontFamily="monospace" fontWeight="bold">!</text>
              <text x={c.x} y={TRACK_Y + 16} textAnchor="middle" fill={C.speedLimitLow} fontSize={5.5} fontFamily="monospace">{c.limit}</text>
            </g>
          ))}

          {/* ===== STATIONS ===== */}
          {stns.map((st, i) => {
            const x = toX(st.km), isTerm = i === 0 || i === stns.length - 1;
            return (
              <g key={`st${st.id}`} style={{ cursor: "pointer" }}
                onMouseEnter={(e) => { const r = svgContainerRef.current?.getBoundingClientRect(); if (r) setTooltip({ x: e.clientX - r.left, y: e.clientY - r.top - 40, html: `<b>${st.name}</b><br/>${st.code} · ${st.km.toFixed(3)}km<br/>${isTerm ? '端点站' : '中间站'}` }); }}
                onMouseLeave={() => setTooltip(null)}
                onClick={() => { setPanX(-(x - svgW / 2) * scale + svgW / 2 - x); setPanY(0); }}>
                <rect x={x - 24} y={STATION_Y} width={48} height={30} rx={4}
                  fill={isTerm ? "#0f172a" : C.stationBg} stroke={isTerm ? "#22c55e" : "rgba(148,163,184,0.15)"} strokeWidth={isTerm ? 1.5 : 0.8} />
                <text x={x} y={STATION_Y + 14} textAnchor="middle" fill={C.station} fontSize={10.5} fontWeight="bold" fontFamily="sans-serif">{st.name}</text>
                <text x={x} y={STATION_Y + 26} textAnchor="middle" fill={C.text} fontSize={7.5} fontFamily="monospace">{st.code} · {st.km.toFixed(2)}km</text>
                <rect x={x - 18} y={STATION_Y + 33} width={36} height={3.5} rx={1} fill="rgba(56,189,248,0.25)" />
                <rect x={x - 18} y={STATION_Y + 37} width={36} height={2} rx={0.5} fill="rgba(244,114,182,0.2)" />
              </g>
            );
          })}

          {/* ===== TRACKS ===== */}
          <line x1={toX(minKm)} y1={TRACK_Y - 5} x2={toX(maxKm)} y2={TRACK_Y - 5} stroke={C.trackDown} strokeWidth={3} strokeLinecap="round" />
          <line x1={toX(minKm)} y1={TRACK_Y - 5} x2={toX(maxKm)} y2={TRACK_Y - 5} stroke="rgba(56,189,248,0.15)" strokeWidth={7} strokeLinecap="round" />
          <line x1={toX(minKm)} y1={TRACK_Y + 5} x2={toX(maxKm)} y2={TRACK_Y + 5} stroke={C.trackUp} strokeWidth={3} strokeLinecap="round" />
          <line x1={toX(minKm)} y1={TRACK_Y + 5} x2={toX(maxKm)} y2={TRACK_Y + 5} stroke="rgba(244,114,182,0.15)" strokeWidth={7} strokeLinecap="round" />

          {/* Direction */}
          <text x={toX(minKm) + 25} y={TRACK_Y - 12} fill={C.trackDown} fontSize={9} fontFamily="monospace" fontWeight="bold">下行 ↓</text>
          <text x={toX(minKm) + 25} y={TRACK_Y + 14} fill={C.trackUp} fontSize={9} fontFamily="monospace" fontWeight="bold">上行 ↑</text>

          {/* ===== SPEED LIMIT ZONES ===== */}
          {layers.speedLimits && speedZones.map((z, i) => {
            const x1 = toX(z.fromKm), x2 = toX(z.toKm), w = Math.max(5, x2 - x1);
            const fc = z.limit < 30 ? C.speedLimitLow : z.limit < 55 ? C.speedLimitMid : "rgba(245,158,11,0.25)";
            const alpha = z.limit < 30 ? 0.3 : z.limit < 55 ? 0.2 : 0.1;
            return (
              <g key={`sp${i}`} style={{ cursor: "pointer" }}
                onMouseEnter={(e) => { const r = svgContainerRef.current?.getBoundingClientRect(); if (r) setTooltip({ x: e.clientX - r.left, y: e.clientY - r.top - 40, html: `<b>限速 ${z.limit} km/h</b><br/>${z.fromKm.toFixed(2)} ~ ${z.toKm.toFixed(2)} km<br/>${z.switchId > 0 ? '关联道岔 #' + z.switchId : '普通限速区段'}` }); }}
                onMouseLeave={() => setTooltip(null)}>
                <rect x={x1} y={TRACK_Y - 28} width={w} height={18} rx={2} fill={fc} opacity={alpha}
                  stroke={z.limit < 30 ? "rgba(239,68,68,0.5)" : "rgba(245,158,11,0.3)"} strokeWidth={0.5} />
                {w > 20 && <text x={(x1 + x2) / 2} y={TRACK_Y - 16} textAnchor="middle" fill={z.limit < 30 ? C.speedLimitLow : C.speedLimitMid} fontSize={8} fontFamily="monospace" fontWeight="bold">{z.limit}</text>}
              </g>
            );
          })}
          {layers.speedLimits && <text x={PAD.left - 5} y={TRACK_Y - 25} fill={C.text} fontSize={9} fontFamily="monospace" textAnchor="end">限速</text>}

          {/* ===== SIGNALS ===== */}
          {layers.signals && sigPositions.filter((_, i) => i % 2 === 0).slice(0, 80).map((sp) => {
            const sy = sp.onUp ? TRACK_Y + 5 : TRACK_Y - 5;
            const dir = sp.onUp ? 1 : -1;
            const fill = sp.s.类型 === 1 ? C.signalGreen : sp.s.类型 === 2 ? C.signalEntry : sp.s.类型 === 3 ? C.signalYellow : C.signalRed;
            return (
              <g key={`sig${sp.s.索引编号}`} style={{ cursor: "pointer" }}
                onMouseEnter={(e) => { const r = svgContainerRef.current?.getBoundingClientRect(); if (r) setTooltip({ x: e.clientX - r.left, y: e.clientY - r.top - 40, html: `<b>信号机 ${sp.s.名称}</b><br/>类型: ${sp.s.类型} · Seg: ${sp.s.所处Seg编号}<br/>防护方向: ${sp.s.防护方向} · 灯列: ${sp.s.灯列信息}<br/>${sp.km.toFixed(3)} km` }); }}
                onMouseLeave={() => setTooltip(null)}>
                <polygon points={`${sp.x},${sy + dir * 10} ${sp.x - 3},${sy + dir * 15} ${sp.x + 3},${sy + dir * 15}`} fill={fill} stroke="rgba(255,255,255,0.3)" strokeWidth={0.5} />
              </g>
            );
          })}

          {/* ===== SWITCHES - Real track geometry ===== */}
          {layers.switches && swGeometries.map((g, i) => (
            <g key={`sw${g.sw.索引编号}`} style={{ cursor: "pointer" }}
              onMouseEnter={(e) => { const r = svgContainerRef.current?.getBoundingClientRect(); if (r) setTooltip({ x: e.clientX - r.left, y: e.clientY - r.top - 40, html: `<b>道岔 #${g.sw.索引编号}</b><br/>定位Seg: ${g.sw.定位SegID} · 反位Seg: ${g.sw.反位SegID}<br/>汇合Seg: ${g.sw.汇合SegID} · 侧向限速: ${g.sw.侧向静态限速} cm/s<br/>${g.junctionKm.toFixed(3)} km<br/><span style='color:#8b5cf6'>点击查看详图</span>` }); }}
              onMouseLeave={() => setTooltip(null)}
              onClick={() => setDetailSwitch(g.sw)}>

              {/* Converging track (汇合 → 道岔点) */}
              <line x1={g.converge.x1} y1={g.converge.y1} x2={g.converge.x2} y2={g.converge.y2}
                stroke="#94a3b8" strokeWidth={2.5} strokeLinecap="round" />

              {/* Normal position track (道岔点 → 定位方向, straight) */}
              <line x1={g.normal.x1} y1={g.normal.y1} x2={g.normal.x2} y2={g.normal.y2}
                stroke="#22c55e" strokeWidth={3} strokeLinecap="round" />

              {/* Reverse position track (道岔点 → 反位方向, diverging) */}
              <line x1={g.reverse.x1} y1={g.reverse.y1} x2={g.reverse.x2} y2={g.reverse.y2}
                stroke="#f97316" strokeWidth={2.5} strokeLinecap="round" strokeDasharray="4,3" />

              {/* Switch point indicator */}
              <circle cx={g.junctionX} cy={TRACK_Y} r={5} fill={C.switchPoint} stroke="#fff" strokeWidth={1.5} opacity={0.95} />

              {/* Label on diverging branch */}
              <text x={g.reverse.x2 + 4} y={g.reverse.y2 + 3}
                fill="#f97316" fontSize={7} fontFamily="monospace" fontWeight="bold">
                #{g.sw.索引编号}
              </text>
            </g>
          ))}

          {/* ===== BUMPERS (Phase3) ===== */}
          {layers.bumpers && bumperPositions.map((b, i) => (
            <g key={`bmp${i}`} style={{ cursor: "pointer" }}
              onMouseEnter={(e) => { const r = svgContainerRef.current?.getBoundingClientRect(); if (r) setTooltip({ x: e.clientX - r.left, y: e.clientY - r.top - 40, html: `<b>车档</b><br/>类型: ${b.type === 1 ? '可碰撞' : '不可碰撞'}` }); }}
              onMouseLeave={() => setTooltip(null)}>
              <rect x={b.x - 5} y={TRACK_Y - 10} width={10} height={20} rx={1} fill={C.bumper} stroke="#fff" strokeWidth={1} />
              <text x={b.x} y={TRACK_Y + 20} textAnchor="middle" fill={C.bumper} fontSize={6} fontFamily="monospace" fontWeight="bold">▣</text>
            </g>
          ))}

          {/* ===== AXLE COUNTERS (Phase3) ===== */}
          {layers.axleCounters && axlePositions.map((a, i) => (
            <g key={`ac${i}`} style={{ cursor: "pointer" }}
              onMouseEnter={(e) => { const r = svgContainerRef.current?.getBoundingClientRect(); if (r) setTooltip({ x: e.clientX - r.left, y: e.clientY - r.top - 40, html: `<b>计轴器 ${a.name}</b><br/>${a.km.toFixed(3)} km` }); }}
              onMouseLeave={() => setTooltip(null)}>
              <circle cx={a.x} cy={TRACK_Y - 8} r={2.5} fill={C.axleDot} stroke="#fff" strokeWidth={0.5} />
            </g>
          ))}

          {/* ===== FLOOD GATES (Phase3) ===== */}
          {layers.floodGates && fgPositions.map((f, i) => (
            <g key={`fg${i}`} style={{ cursor: "pointer" }}
              onMouseEnter={(e) => { const r = svgContainerRef.current?.getBoundingClientRect(); if (r) setTooltip({ x: e.clientX - r.left, y: e.clientY - r.top - 40, html: `<b>防淹门</b><br/>区域长度: ${f.len}m · CI: ${f.ci}` }); }}
              onMouseLeave={() => setTooltip(null)}>
              <rect x={f.x - 6} y={TRACK_Y - 18} width={12} height={36} rx={2} fill={C.floodGate} stroke="rgba(59,130,246,0.6)" strokeWidth={0.8} />
              <text x={f.x} y={TRACK_Y + 24} textAnchor="middle" fill="#3b82f6" fontSize={6} fontFamily="monospace" fontWeight="bold">防淹门</text>
            </g>
          ))}

          {/* ===== DEPOT ===== */}
          <line x1={PAD.left} y1={TRACK_Y + 38} x2={toX(2.5)} y2={TRACK_Y + 38} stroke={C.trackDepot} strokeWidth={1.2} strokeDasharray="5,5" opacity={0.25} />
          <text x={PAD.left + 10} y={TRACK_Y + 52} fill={C.text} fontSize={8} fontFamily="monospace">郭公庄车辆段</text>

          {/* ===== GRADIENT PROFILE ===== */}
          {layers.gradients && (
            <>
              <text x={PAD.left - 5} y={GRADIENT_Y - 5} fill={C.text} fontSize={10} fontFamily="monospace" textAnchor="end">坡度</text>
              <line x1={PAD.left} y1={GRADIENT_Y} x2={toX(maxKm)} y2={GRADIENT_Y} stroke={C.gridLine} strokeWidth={1} />
              {gradBars.map((g, i) => {
                const x1 = toX(g.fromKm), x2 = toX(g.toKm);
                const h = Math.abs(g.value) * 0.25;
                const y = g.value > 0 ? GRADIENT_Y - h : GRADIENT_Y;
                return (
                  <g key={`grad${i}`} style={{ cursor: "pointer" }}
                    onMouseEnter={(e) => { const r = svgContainerRef.current?.getBoundingClientRect(); if (r) setTooltip({ x: e.clientX - r.left, y: e.clientY - r.top - 40, html: `<b>坡度 ${g.value > 0 ? '+' : ''}${g.value}‰</b><br/>${g.fromKm.toFixed(2)} ~ ${g.toKm.toFixed(2)} km<br/>${g.verticalRadius > 0 ? '竖曲线半径: ' + g.verticalRadius + 'm' : ''}` }); }}
                    onMouseLeave={() => setTooltip(null)}>
                    <rect x={x1} y={y} width={Math.max(2, x2 - x1)} height={h} fill={g.value > 0 ? C.gradientUp : C.gradientDown} opacity={0.45} />
                    {i % 6 === 0 && <text x={(x1 + x2) / 2} y={y - 2} textAnchor="middle" fill={C.text} fontSize={6.5} fontFamily="monospace">{g.value > 0 ? "+" : ""}{g.value}‰</text>}
                    {g.verticalRadius > 0 && Math.abs(x2 - x1) > 30 && (
                      <text x={(x1 + x2) / 2} y={y + h + 12} textAnchor="middle" fill={C.text} fontSize={5.5} fontFamily="monospace">Rv={g.verticalRadius}m</text>
                    )}
                  </g>
                );
              })}
            </>
          )}

          {/* ===== TRAINS ===== */}
          {trainMarkers.map((tm, i) => {
            const fill = tm.status === "ARRIVING" ? C.trainArriving : tm.status === "DEPARTING" ? C.trainDeparting : C.train;
            return (
              <g key={`train${i}`} style={{ cursor: "pointer" }}
                onMouseEnter={(e) => { const r = svgContainerRef.current?.getBoundingClientRect(); if (r) setTooltip({ x: e.clientX - r.left, y: e.clientY - r.top - 40, html: `<b>${tm.name}</b><br/>速度: ${Math.round(tm.speed)} km/h<br/>位置: ${(tm.posM / 1000).toFixed(3)} km<br/>状态: ${tm.status}` }); }}
                onMouseLeave={() => setTooltip(null)}>
                <rect x={tm.x - 6} y={TRACK_Y - 9} width={12} height={12} rx={2.5} fill={fill} stroke="#fff" strokeWidth={1} />
                <text x={tm.x} y={TRACK_Y + 19} textAnchor="middle" fill="#fff" fontSize={9} fontFamily="monospace" fontWeight="bold">{tm.name}</text>
                <text x={tm.x} y={TRACK_Y + 29} textAnchor="middle" fill={C.text} fontSize={7} fontFamily="monospace">{Math.round(tm.speed)}km/h</text>
              </g>
            );
          })}

          {/* ===== LEGEND ===== */}
          <g transform={`translate(${svgW - 305}, 8)`}>
            <rect x={0} y={0} width={295} height={92} rx={5} fill="rgba(15,23,42,0.92)" stroke="rgba(148,163,184,0.08)" strokeWidth={1} />
            <text x={147} y={13} textAnchor="middle" fill={C.textBright} fontSize={9} fontFamily="monospace" fontWeight="bold">图 例</text>
            {/* Row 1 */}
            <line x1={8} y1={26} x2={32} y2={26} stroke={C.trackDown} strokeWidth={2} /><text x={36} y={29} fill={C.text} fontSize={6.5} fontFamily="monospace">下行</text>
            <line x1={72} y1={26} x2={96} y2={26} stroke={C.trackUp} strokeWidth={2} /><text x={100} y={29} fill={C.text} fontSize={6.5} fontFamily="monospace">上行</text>
            <polygon points="143,26 140,30 146,30" fill={C.signalGreen} /><text x={150} y={29} fill={C.text} fontSize={6.5} fontFamily="monospace">信号</text>
            {/* Switch 3-branch icon */}
            <line x1={175} y1={28} x2={186} y2={28} stroke="#94a3b8" strokeWidth={1.5} />
            <line x1={186} y1={28} x2={198} y2={22} stroke="#22c55e" strokeWidth={1.5} />
            <line x1={186} y1={28} x2={198} y2={34} stroke="#f97316" strokeWidth={1.2} strokeDasharray="2,2" />
            <circle cx={186} cy={28} r={2.5} fill={C.switchPoint} />
            <text x={203} y={29} fill={C.text} fontSize={6.5} fontFamily="monospace">道岔</text>
            <rect x={228} y={24} width={10} height={5} rx={1} fill="rgba(239,68,68,0.2)" stroke="rgba(239,68,68,0.4)" strokeWidth={0.5} /><text x={242} y={29} fill={C.text} fontSize={6.5} fontFamily="monospace">限速</text>
            {/* Row 2 */}
            <rect x={8} y={40} width={12} height={6} rx={1} fill={C.gradientUp} opacity={0.45} /><text x={24} y={46} fill={C.text} fontSize={6.5} fontFamily="monospace">上坡</text>
            <rect x={60} y={40} width={12} height={6} rx={1} fill={C.gradientDown} opacity={0.45} /><text x={76} y={46} fill={C.text} fontSize={6.5} fontFamily="monospace">下坡</text>
            <rect x={115} y={40} width={12} height={6} rx={1} fill={C.tunnelFill} stroke={C.tunnelStroke} strokeWidth={0.5} strokeDasharray="1,2" /><text x={131} y={46} fill={C.text} fontSize={6.5} fontFamily="monospace">隧道</text>
            <rect x={160} y={40} width={6} height={6} rx={0.5} fill={C.bumper} /><text x={170} y={46} fill={C.text} fontSize={6.5} fontFamily="monospace">车档</text>
            <circle cx={210} cy={43} r={2} fill={C.axleDot} /><text x={216} y={46} fill={C.text} fontSize={6.5} fontFamily="monospace">计轴器</text>
            <text x={253} y={46} fill={C.text} fontSize={6.5} fontFamily="monospace" fontWeight="bold" fillOpacity={0.7}>⚠碰撞区</text>
            {/* Row 3 */}
            <rect x={8} y={57} width={10} height={5} rx={1} fill={C.floodGate} stroke="rgba(59,130,246,0.6)" strokeWidth={0.5} /><text x={23} y={62} fill={C.text} fontSize={6.5} fontFamily="monospace">防淹门</text>
            <rect x={68} y={57} width={10} height={5} rx={1} fill={C.train} stroke="#fff" strokeWidth={0.5} /><text x={83} y={62} fill={C.text} fontSize={6.5} fontFamily="monospace">列车</text>
            <rect x={118} y={57} width={10} height={5} rx={1} fill={C.stationBg} stroke="rgba(148,163,184,0.15)" strokeWidth={0.5} /><text x={133} y={62} fill={C.text} fontSize={6.5} fontFamily="monospace">车站</text>
            <rect x={168} y={57} width={10} height={5} rx={1} fill="#0f172a" stroke="#22c55e" strokeWidth={1} /><text x={183} y={62} fill={C.text} fontSize={6.5} fontFamily="monospace">端点</text>
            <line x1={215} y1={60} x2={235} y2={60} stroke={C.trackDepot} strokeWidth={0.8} strokeDasharray="2,2" opacity={0.4} /><text x={239} y={62} fill={C.text} fontSize={6.5} fontFamily="monospace">车辆段</text>
          </g>
        </g>
      </svg>

      {/* ===== TOOLTIP OVERLAY ===== */}
      {tooltip && !tooltipSticky && (
        <div className="absolute z-30 pointer-events-none" style={{ left: tooltip.x + 12, top: tooltip.y }}>
          <div className="bg-[#0f172a]/98 border border-slate-400/15 rounded-md px-2.5 py-1.5 text-[10px] text-slate-200 leading-relaxed shadow-xl max-w-[220px]"
            dangerouslySetInnerHTML={{ __html: tooltip.html }} />
        </div>
      )}

      {/* ===== SWITCH DETAIL MODAL (Phase2) ===== */}
      {detailSwitch && (
        <div className="absolute inset-0 z-40 flex items-center justify-center bg-black/60" onClick={() => setDetailSwitch(null)}>
          <div className="bg-[#0f172a] border border-slate-400/15 rounded-xl p-5 shadow-2xl min-w-[320px]" onClick={(e) => e.stopPropagation()}>
            <div className="flex items-center justify-between mb-4">
              <div className="flex items-center gap-2">
                <div className="w-3 h-3 rounded-full" style={{ background: C.switchPoint }} />
                <span className="text-sm font-bold text-slate-100 font-mono">道岔 #{detailSwitch.索引编号}</span>
              </div>
              <button onClick={() => setDetailSwitch(null)} className="text-slate-500 hover:text-slate-300 text-lg leading-none border-none bg-transparent cursor-pointer">✕</button>
            </div>
            {/* Switch diagram */}
            <div className="bg-[#020617] rounded-lg p-4 mb-4 flex items-center justify-center">
              <svg width="260" height="140" viewBox="0 0 260 140">
                {/* Converging Seg */}
                <line x1={130} y1={70} x2={80} y2={30} stroke={C.trackDepot} strokeWidth={2.5} strokeLinecap="round" />
                <text x={62} y={22} fill={C.text} fontSize={9} fontFamily="monospace" textAnchor="end">汇合Seg_{detailSwitch.汇合SegID}</text>
                {/* Normal position */}
                <line x1={130} y1={70} x2={200} y2={30} stroke="#22c55e" strokeWidth={3} strokeLinecap="round" />
                <text x={210} y={28} fill="#22c55e" fontSize={9} fontFamily="monospace">定位Seg_{detailSwitch.定位SegID}</text>
                {/* Reverse position */}
                <line x1={130} y1={70} x2={200} y2={110} stroke="#ef4444" strokeWidth={2.5} strokeLinecap="round" strokeDasharray="4,3" />
                <text x={210} y={118} fill="#ef4444" fontSize={9} fontFamily="monospace">反位Seg_{detailSwitch.反位SegID}</text>
                {/* Switch point */}
                <circle cx={130} cy={70} r={7} fill={C.switchPoint} stroke="#fff" strokeWidth={2} />
                <text x={130} y={65} textAnchor="middle" fill="#fff" fontSize={8} fontFamily="monospace" fontWeight="bold">道岔</text>
              </svg>
            </div>
            {/* Info grid */}
            <div className="grid grid-cols-2 gap-2 text-[11px]">
              {[
                ["定位Seg", detailSwitch.定位SegID],
                ["反位Seg", detailSwitch.反位SegID],
                ["汇合Seg", detailSwitch.汇合SegID],
                ["侧向限速", detailSwitch.侧向静态限速 + " cm/s"],
                ["方向", detailSwitch.方向 || "-"],
                ["联动道岔", detailSwitch.联动道岔编号 || "-"],
                ["互联互通ID", String(detailSwitch.互联互通编号).slice(0, 10) + "..."],
              ].map(([l, v]) => (
                <div key={l as string} className="flex justify-between bg-[#020617] rounded px-2.5 py-1.5">
                  <span className="text-slate-500 font-mono">{l}</span>
                  <span className="text-slate-200 font-mono">{String(v)}</span>
                </div>
              ))}
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
