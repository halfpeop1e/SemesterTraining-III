import { useState, useEffect, useCallback, useRef, useMemo } from 'react';
import { Link } from 'react-router-dom';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import ReactEChartsCore from 'echarts-for-react/lib/core';
import * as echarts from 'echarts/core';
import { LineChart } from 'echarts/charts';
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';

echarts.use([LineChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer]);
import {
  startSimulation,
  stepSimulation,
  getSnapshot,
  getLineMap,
  applyStrategy,
} from '../../api/dispatch';
import type {
  SimulationSnapshot,
  StationGeo,
  TrainState,
  TrainPositionPoint,
  StationArrival,
  HeadwayInfo,
  TrainCommand,
} from '../../types/dispatch';

/* ================================================================
   Fix Leaflet icons
   ================================================================ */

delete (L.Icon.Default.prototype as any)._getIconUrl;
L.Icon.Default.mergeOptions({
  iconRetinaUrl: 'https://unpkg.com/leaflet@1.9.4/dist/images/marker-icon-2x.png',
  iconUrl: 'https://unpkg.com/leaflet@1.9.4/dist/images/marker-icon.png',
  shadowUrl: 'https://unpkg.com/leaflet@1.9.4/dist/images/marker-shadow.png',
});

/* ================================================================
   Tile layers & Speed options
   ================================================================ */

const TILE_LAYERS = {
  dark:     { url: 'https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png', label: 'Dark', attribution: '&copy; CartoDB' },
  standard: { url: 'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', label: 'Standard', attribution: '&copy; OSM' },
  satellite:{ url: 'https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}', label: 'Satellite', attribution: '&copy; Esri' },
} as const;
type TileMode = keyof typeof TILE_LAYERS;

const SPEED_OPTIONS = [{ label: '1x', steps: 1 }, { label: '2x', steps: 2 }, { label: '5x', steps: 5 }, { label: '10x', steps: 10 }];

/* ================================================================
   Data Helpers
   ================================================================ */

const STATUS_LABEL: Record<TrainState['status'], string> = {
  DEPOT_WAITING: '待发', DEPARTING: '起动', ACCELERATING: '加速', CRUISING: '巡航', BRAKING: '制动', DWELLING: '站停', TURNING_BACK: '折返', FINISHED: '终到',
};
const STATUS_COLOR: Record<TrainState['status'], string> = {
  DEPOT_WAITING: '#617088', DEPARTING: '#45aaf2', ACCELERATING: '#00a8e8', CRUISING: '#06d6a0', BRAKING: '#f7b731', DWELLING: '#9b59b6', TURNING_BACK: '#ff9f43', FINISHED: '#fc5c65',
};
const TRAIN_COLORS = ['#00a8e8','#06d6a0','#f7b731','#fc5c65','#9b59b6','#45aaf2','#f368e0','#ff9f43'];

const fmtTime = (s: number) =>
  [Math.floor(s / 3600), Math.floor((s % 3600) / 60), Math.floor(s % 60)]
    .map((n) => String(n).padStart(2, '0')).join(':');
const fmtNum = (n: number) => Math.round(n).toLocaleString();

function interpolatePos(posMeters: number, sorted: StationGeo[]) {
  const km = posMeters / 1000;
  if (km <= sorted[0].km) return { lat: sorted[0].latitude, lng: sorted[0].longitude };
  if (km >= sorted[sorted.length - 1].km) return { lat: sorted[sorted.length - 1].latitude, lng: sorted[sorted.length - 1].longitude };
  let ni = 1;
  while (ni < sorted.length && sorted[ni].km < km) ni++;
  const p = sorted[ni - 1], n = sorted[ni];
  const r = (km - p.km) / (n.km - p.km);
  return { lat: p.latitude + (n.latitude - p.latitude) * r, lng: p.longitude + (n.longitude - p.longitude) * r };
}

/* ================================================================
   Train Diagram (运行图) — ECharts
   ================================================================ */

function TrainDiagram({
  history,
  stations,
  simTime,
  plannedPoints,
}: {
  history: TrainPositionPoint[];
  stations: StationGeo[];
  simTime: number;
  plannedPoints: TrainPositionPoint[];
}) {
  const sortedStations = useMemo(() => [...stations].sort((a, b) => a.id - b.id), [stations]);

  const option = useMemo(() => {
    if (sortedStations.length < 2) return {};

    const maxKm = sortedStations[sortedStations.length - 1].km;
    const maxTime = Math.max(simTime, 120);

    // Group by trainId
    const byTrain: Record<string, TrainPositionPoint[]> = {};
    history.forEach((p) => {
      if (!byTrain[p.trainId]) byTrain[p.trainId] = [];
      byTrain[p.trainId].push(p);
    });

    const series: any[] = [];

    // ── 计划运行线 (浅色虚线, 按时刻表绘制) ──
    if (plannedPoints.length > 0) {
      const byTrainPlanned: Record<string, TrainPositionPoint[]> = {};
      plannedPoints.forEach((p) => {
        if (!byTrainPlanned[p.trainId]) byTrainPlanned[p.trainId] = [];
        byTrainPlanned[p.trainId].push(p);
      });
      Object.entries(byTrainPlanned).forEach(([tid, pts]) => {
        pts.sort((a, b) => a.timeSeconds - b.timeSeconds);
        const color = TRAIN_COLORS[(parseInt(tid.slice(1)) - 1) % TRAIN_COLORS.length];
        series.push({
          type: 'line',
          name: tid + ' 计划',
          data: pts.map((p) => [p.timeSeconds, p.positionKm]),
          symbol: 'none',
          lineStyle: { color, type: 'dashed', width: 1, opacity: 0.35 },
          emphasis: { focus: 'series', lineStyle: { opacity: 0.7, width: 1.5 } },
          silent: true,
        });
      });
    }

    // ── 实绩运行线 (实线) ──
    Object.entries(byTrain).map(([tid, pts], idx) => {
      pts.sort((a, b) => a.timeSeconds - b.timeSeconds);
      const data = pts.map((p) => [p.timeSeconds, p.positionKm]);
      const color = TRAIN_COLORS[idx % TRAIN_COLORS.length];
      const last = pts[pts.length - 1];

      // 第一个系列上添加站点 markLine（水平线 + 站名）
      const markLine = idx === 0 ? {
        silent: true,
        symbol: 'none',
        animation: false,
        lineStyle: { color: 'rgba(97,112,136,0.25)', type: 'solid', width: 1 },
        label: {
          show: true,
          position: 'insideStartTop' as const,
          color: '#94a3b8',
          fontSize: 10,
          fontWeight: 'bold' as const,
          fontFamily: '-apple-system,sans-serif',
          distance: [6, 4],
          formatter: (p: any) => p.name,
        },
        data: sortedStations.map(s => ({ yAxis: s.km, name: s.name })),
      } : undefined;

      return {
        type: 'line',
        name: tid,
        data,
        symbol: 'none',
        lineStyle: { color, width: 2 },
        emphasis: { lineStyle: { width: 3 }, focus: 'series' },
        markLine,
        markPoint: pts.length > 0 ? {
          silent: true,
          symbol: 'none',
          data: [{
            name: tid,
            coord: [last.timeSeconds, last.positionKm],
            value: tid,
            symbolOffset: [5, 0],
            label: {
              show: true,
              color,
              fontSize: 10,
              fontWeight: 'bold',
              fontFamily: 'JetBrains Mono,monospace',
              position: 'right',
              distance: 2,
            },
          }],
        } : undefined,
      };
    });

    // Current-time dashed vertical line
    series.push({
      type: 'line',
      name: 'NOW',
      data: [[simTime, 0], [simTime, maxKm]],
      lineStyle: { color: 'rgba(252,92,101,0.45)', type: 'dashed', width: 1 },
      symbol: 'none',
      silent: true,
    });

    return {
      backgroundColor: 'transparent',
      grid: { left: 28, right: 24, top: 12, bottom: 28 },
      xAxis: {
        type: 'value',
        min: 0,
        max: maxTime,
        axisLine: { show: true, lineStyle: { color: '#1c2a3e' } },
        axisTick: { show: false },
        axisLabel: {
          color: '#617088',
          fontSize: 9,
          fontFamily: '-apple-system,sans-serif',
          formatter: (v: number) => fmtTime(v),
        },
        splitLine: { show: false },
      },
      yAxis: {
        type: 'value',
        min: 0,
        max: maxKm,
        inverse: true,
        axisLine: { show: true, lineStyle: { color: '#1c2a3e' } },
        axisTick: { show: false },
        axisLabel: {
          color: '#617088',
          fontSize: 9,
          fontFamily: 'JetBrains Mono,monospace',
          formatter: (v: number) => `${v.toFixed(1)} km`,
        },
        splitLine: {
          show: true,
          lineStyle: { color: '#152433', type: 'solid', width: 0.5 },
        },
      },
      tooltip: {
        trigger: 'item',
        backgroundColor: 'rgba(13,21,32,0.95)',
        borderColor: '#1c2a3e',
        padding: [8, 12],
        textStyle: { color: '#e2e8f0', fontSize: 11 },
        formatter: (params: any) => {
          if (params.seriesName === 'NOW') return `<b>当前时刻</b><br/>${fmtTime(simTime)}`;
          if (params.seriesName && params.value) {
            return `<b style="color:${params.color}">${params.seriesName}</b><br/>模拟时间: ${fmtTime(params.value[0])}<br/>里程位置: ${Number(params.value[1]).toFixed(2)} km`;
          }
          return '';
        },
      },
      series,
      animationDuration: 600,
      animationEasing: 'cubicInOut' as const,
    };
  }, [history, sortedStations, simTime]);

  if (history.length === 0) {
    return <div className="d-empty-panel">启动仿真后显示运行图</div>;
  }

  return (
    <div className="d-diagram">
      <ReactEChartsCore
        echarts={echarts}
        option={option}
        style={{ width: '100%', height: '100%' }}
        notMerge={false}
        lazyUpdate={true}
        opts={{ renderer: 'canvas' }}
      />
    </div>
  );
}

/* ================================================================
   MapView
   ================================================================ */

function MapView({ stations, trains, tileMode }: { stations: StationGeo[]; trains: TrainState[]; tileMode: TileMode }) {
  const containerRef = useRef<HTMLDivElement>(null);
  const mapRef = useRef<L.Map | null>(null);
  const tileLayerRef = useRef<L.TileLayer | null>(null);
  const stationMrkRef = useRef<L.CircleMarker[]>([]);
  const trainMrkRef = useRef<L.CircleMarker[]>([]);
  const carMrkRef = useRef<L.CircleMarker[]>([]);
  const lineRef = useRef<L.Polyline | null>(null);
  const glowRef = useRef<L.Polyline | null>(null);
  const fitDone = useRef(false);

  useEffect(() => {
    if (!containerRef.current || mapRef.current) return;
    const map = L.map(containerRef.current, { center: [39.88, 116.31], zoom: 12, zoomControl: true, attributionControl: true });
    map.attributionControl.setPrefix('');
    const tile = TILE_LAYERS[tileMode];
    tileLayerRef.current = L.tileLayer(tile.url, { maxZoom: 19, attribution: tile.attribution }).addTo(map);
    mapRef.current = map;
    const fix = () => { map.invalidateSize(); };
    setTimeout(fix, 100); setTimeout(fix, 400);
    window.addEventListener('resize', fix);
    const ro = new ResizeObserver(() => fix());
    if (containerRef.current) ro.observe(containerRef.current);
    return () => { window.removeEventListener('resize', fix); ro.disconnect(); tileLayerRef.current?.remove(); map.remove(); mapRef.current = null; };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    const map = mapRef.current; if (!map) return;
    if (tileLayerRef.current) map.removeLayer(tileLayerRef.current);
    const t = TILE_LAYERS[tileMode];
    tileLayerRef.current = L.tileLayer(t.url, { maxZoom: 19, attribution: t.attribution }).addTo(map);
  }, [tileMode]);

  useEffect(() => {
    const map = mapRef.current; if (!map || stations.length === 0) return;
    stationMrkRef.current.forEach((m) => m.remove()); stationMrkRef.current = [];
    lineRef.current?.remove(); glowRef.current?.remove();
    const sorted = [...stations].sort((a, b) => a.id - b.id);
    const coords: [number, number][] = sorted.map((s) => [s.latitude, s.longitude]);
    glowRef.current = L.polyline(coords, { color: '#00a8e8', weight: 8, opacity: 0.15, lineCap: 'round', lineJoin: 'round' }).addTo(map);
    lineRef.current = L.polyline(coords, { color: '#00a8e8', weight: 3, opacity: 0.9, lineCap: 'round', lineJoin: 'round' }).addTo(map);
    sorted.forEach((s) => {
      const m = L.circleMarker([s.latitude, s.longitude], {
        radius: (s.id === 1 || s.id === sorted.length) ? 9 : 7,
        fillColor: '#060b11', fillOpacity: 1, color: '#00a8e8', weight: 2.5,
      }).addTo(map);
      m.bindTooltip(`<div style="text-align:center;font-weight:700;font-size:12px;color:#e2e8f0;">${s.name}</div><div style="font-size:10px;color:#617088;margin-top:2px;">${s.km.toFixed(1)} km</div>`,{direction:'top',offset:[0,-10],className:'d-tooltip'});
      m.on('mouseover',()=>m.setStyle({fillColor:'#00a8e8'})); m.on('mouseout',()=>m.setStyle({fillColor:'#060b11'}));
      stationMrkRef.current.push(m);
    });
    // Auto-fit map bounds to cover all stations (delay ensures container has size)
    if (!fitDone.current) {
      const doFit = () => {
        const bounds = L.latLngBounds(sorted.map(s => [s.latitude, s.longitude]));
        map.fitBounds(bounds, { padding: [30, 30] });
        fitDone.current = true;
      };
      setTimeout(doFit, 300);
    }
  }, [stations]);

  useEffect(() => {
    const map = mapRef.current; if (!map || stations.length === 0) return;
    trainMrkRef.current.forEach((m) => m.remove()); trainMrkRef.current = [];
    carMrkRef.current.forEach((m) => m.remove()); carMrkRef.current = [];
    const sorted = [...stations].sort((a, b) => a.id - b.id);
    trains.filter((t) => t.status !== 'FINISHED').forEach((t) => {
      const hp = interpolatePos(t.positionMeters, sorted); if (!hp) return;
      const bg = STATUS_COLOR[t.status];
      const isMoving = t.speed > 0;

      // Head car: large circleMarker with tooltip
      const hm = L.circleMarker([hp.lat, hp.lng], {
        radius: 9,
        fillColor: bg,
        fillOpacity: 0.9,
        color: '#fff',
        weight: 2,
      }).addTo(map);
      hm.bindTooltip(
        `<div style="font-weight:700;font-size:12px;color:#e2e8f0;">${t.trainNumber||t.trainName} <span style="font-size:9px;color:${t.direction==='DOWN'?'#f7b731':'#06d6a0'}">${t.direction==='DOWN'?'↓':'↑'}</span></div>
         <div style="font-size:10px;color:#94a3b8;">${isMoving ? Math.round(t.speed)+' km/h' : STATUS_LABEL[t.status]} · ${t.routePattern==='SHORT_S'?'南段':t.routePattern==='SHORT_N'?'北段':'全程'}</div>
         <div style="font-size:9px;color:#617088;">${fmtNum(t.positionMeters)} m · ${t.carCount??6}节${t.turnbackCount>0?' · 折返'+t.turnbackCount+'次':''}</div>`,
        { direction: 'top', offset: [0, -12], className: 'd-tooltip' }
      );
      trainMrkRef.current.push(hm);

      // All cars (including head car's position for all 6 cars)
      if (t.cars && t.cars.length > 1) {
        t.cars.slice(1).forEach((car: any) => {
          const cp = interpolatePos(car.positionMeters, sorted); if (!cp) return;
          carMrkRef.current.push(L.circleMarker([cp.lat, cp.lng], {
            radius: 5.5,
            fillColor: bg,
            fillOpacity: 0.65,
            color: 'rgba(255,255,255,0.4)',
            weight: 1,
          }).addTo(map));
        });
      }
    });
  }, [trains, stations]);

  return <div ref={containerRef} className="d-map-inner" />;
}

/* ================================================================
   Main Dispatch
   ================================================================ */

export default function Dispatch() {
  const [snapshot, setSnapshot] = useState<SimulationSnapshot | null>(null);
  const [stations, setStations] = useState<StationGeo[]>([]);
  const [isRunning, setIsRunning] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [tileMode, setTileMode] = useState<TileMode>('dark');
  const [speedIndex, setSpeedIndex] = useState(0);
  const [rightTab, setRightTab] = useState<'trains'|'arrivals'|'commands'|'flow'|'deviation'>('trains');
  const autoRef = useRef<ReturnType<typeof setInterval> | null>(null);

  useEffect(() => {
    getLineMap().then((d) => { if (Array.isArray(d) && d.length > 0) setStations(d); else setError('failed load line'); })
      .catch((e) => setError('Line load: ' + (e?.message || 'network')));
  }, []);

  useEffect(() => () => { if (autoRef.current) clearInterval(autoRef.current); }, []);

  const refresh = useCallback(async () => {
    try { setError(''); setSnapshot(await getSnapshot()); } catch (e: any) { setError(e.message); }
  }, []);

  const start = useCallback(async () => {
    setLoading(true); setError('');
    try { await startSimulation(3600); await refresh(); setIsRunning(true); }
    catch (e: any) { setError(e.message); }
    finally { setLoading(false); }
  }, [refresh]);

  useEffect(() => {
    if (!isRunning) return;
    if (autoRef.current) clearInterval(autoRef.current);
    autoRef.current = setInterval(async () => {
      try {
        await stepSimulation(SPEED_OPTIONS[speedIndex].steps);
        setSnapshot(await getSnapshot());
      } catch {}
    }, 500);
    return () => { if (autoRef.current) clearInterval(autoRef.current); };
  }, [isRunning, speedIndex]);

  const stop = useCallback(() => {
    if (autoRef.current) { clearInterval(autoRef.current); autoRef.current = null; }
    setIsRunning(false);
  }, []);

  const step = useCallback(async () => {
    setLoading(true);
    try { await stepSimulation(1); await refresh(); } catch (e: any) { setError(e.message); }
    finally { setLoading(false); }
  }, [refresh]);

  const doStrategy = useCallback(async (trainId: string, type: string, val: number = 0) => {
    try { await applyStrategy(trainId, type, val); await refresh(); } catch (e: any) { setError(e.message); }
  }, [refresh]);

  const trains = snapshot?.trains ?? [];
  const headways = snapshot?.headways ?? [];
  const commands = snapshot?.commands ?? [];
  const arrivals = snapshot?.stationArrivals ?? [];
  const delayEvents = snapshot?.delayEvents ?? [];
  const flow = snapshot?.passengerFlow ?? null;
  const dispInfo = snapshot?.dispatchInfo ?? null;
  const history = snapshot?.positionHistory ?? [];
  const plannedHistory = snapshot?.plannedDiagramPoints ?? [];
  const planDeviations = snapshot?.planDeviations ?? [];
  const energy = snapshot?.totalEnergyKwh ?? 0;

  return (
    <>
      <style>{STYLES}</style>
      <div className="d-root">
        {/* ════ Top Bar ════ */}
        <header className="d-topbar">
          <div className="d-tb-left">
            <Link to="/" className="d-tb-back">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round"><polyline points="15 18 9 12 15 6"/></svg>
            </Link>
            <div className="d-tb-brand">
              <div className="d-tb-logo">
                <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round"><circle cx="12" cy="12" r="3"/><path d="M12 1v4M12 19v4M1 12h4M19 12h4"/></svg>
              </div>
              <div><span className="d-tb-title">总控调度中心</span><span className="d-tb-sub">DISPATCH &middot; LINE 9</span></div>
            </div>
          </div>
          <div className="d-tb-center">
            <div className="d-tb-clock"><span className="d-tb-clock-lbl">SIM TIME</span><span className="d-tb-clock-val">{snapshot ? fmtTime(snapshot.simulationTime) : '00:00:00'}</span></div>
          </div>
          <div className="d-tb-right">
            {error && <span className="d-tb-err">{error}</span>}
            <div className="d-speed-group">{SPEED_OPTIONS.map((o,i)=><button key={o.label} className={`d-speed-btn${i===speedIndex?' active':''}`} onClick={()=>setSpeedIndex(i)} disabled={loading&&!isRunning}>{o.label}</button>)}</div>
            <div className="d-map-mode">{(Object.keys(TILE_LAYERS) as TileMode[]).map(m=><button key={m} className={`d-mode-btn${tileMode===m?' active':''}`} onClick={()=>setTileMode(m)}>{TILE_LAYERS[m].label}</button>)}</div>
            <button className="d-btn d-btn-start" onClick={start} disabled={loading||isRunning}><svg width="12" height="12" viewBox="0 0 24 24" fill="currentColor"><polygon points="8,5 19,12 8,19"/></svg>启动</button>
            <button className="d-btn d-btn-step" onClick={step} disabled={loading||isRunning}>推进</button>
            {isRunning&&<button className="d-btn d-btn-stop" onClick={stop}><svg width="12" height="12" viewBox="0 0 24 24" fill="currentColor"><rect x="6" y="6" width="12" height="12" rx="1.5"/></svg>停止</button>}
          </div>
        </header>

        {/* ════ Body ════ */}
        <div className="d-body">
          {/* Flow + Dispatch info banner */}
          {flow && (
            <div className="d-info-bar">
              <span className="d-info-chip" style={{'--c':'#06d6a0'} as React.CSSProperties}>{flow.period}</span>
              <span className="d-info-chip" style={{'--c':'#fc5c65'} as React.CSSProperties}>最大断面 {flow.peakSectionFlow.toFixed(0)} p/h</span>
              <span className="d-info-chip" style={{'--c':'#f7b731'} as React.CSSProperties}>推间 {flow.demandHeadway.toFixed(0)}s</span>
              <span className="d-info-chip" style={{'--c':dispInfo?.fleetSufficient?'#06d6a0':'#fc5c65'} as React.CSSProperties}>上线 {dispInfo?.onlineTrains}/{dispInfo?.maxAvailableTrains}列</span>
              <span className="d-info-chip" style={{'--c':dispInfo?.dispatchMode==='NORMAL'?'#06d6a0':'#fc5c65'} as React.CSSProperties}>{dispInfo?.dispatchMode==='NORMAL'?'正常运营':dispInfo?.dispatchMode==='COMPRESS'?'压缩间隔':dispInfo?.dispatchMode==='STRETCH'?'扩大间隔':'紧急'}</span>
              {delayEvents.length>0 && <span className="d-info-chip" style={{'--c':'#fc5c65'} as React.CSSProperties}>⚠ 晚点 {delayEvents.length}</span>}
            </div>
          )}
          {/* Left Column */}
          <div className="d-left">
            {/* Map */}
            <div className="d-map-panel">
              <div className="d-panel-head">
                <span className="d-panel-title"><span className="d-panel-dot" style={{background:'#00a8e8'}}/>实时运行图</span>
                <span className="d-panel-badge">{stations.length}站 &middot; {snapshot?.activeTrains??0}/{snapshot?.totalTrains??0}车</span>
              </div>
              <div className="d-map-body"><MapView stations={stations} trains={trains} tileMode={tileMode}/></div>
              <div className="d-map-stats">
                {[{v:snapshot?.activeTrains??'--',l:'在线'},{v:headways.length>0?Math.min(...headways.map(h=>h.timeSeconds)).toFixed(0)+'s':'--',l:'最小时距'},{v:trains.filter(t=>t.status!=='FINISHED').length>0?Math.min(...trains.filter(t=>t.status!=='FINISHED').map(t=>t.speed)).toFixed(0)+'~'+Math.max(...trains.filter(t=>t.status!=='FINISHED').map(t=>t.speed)).toFixed(0):'--',l:'速度 km/h'},{v:energy.toFixed(1)+' kWh',l:'牵引用电'}].map((s,i)=><div className="d-mstat" key={i}><span className="d-mstat-val">{s.v}</span><span className="d-mstat-lbl">{s.l}</span></div>)}
              </div>
            </div>
            {/* Train Diagram */}
            <div className="d-diagram-panel">
              <div className="d-panel-head">
                <span className="d-panel-title"><span className="d-panel-dot" style={{background:'#f7b731'}}/>运行图 (Train Diagram)</span>
                <span className="d-panel-badge">{history.length} 采样点</span>
              </div>
              <TrainDiagram history={history} stations={stations} simTime={snapshot?.simulationTime ?? 0} plannedPoints={plannedHistory}/>
            </div>
          </div>

          {/* Right Column */}
          <div className="d-right">
            {/* Tab bar */}
            <div className="d-tab-bar">
              {[
                { id: 'trains', label: '列车状态', dot: '#06d6a0' },
                { id: 'arrivals', label: '到站时刻', dot: '#45aaf2' },
                { id: 'commands', label: '调度指令', dot: '#fc5c65' },
                { id: 'flow', label: '断面客流', dot: '#f7b731' },
                { id: 'deviation', label: '偏差', dot: '#9b59b6' },
              ].map((tab) => (
                <button
                  key={tab.id}
                  className={`d-tab${rightTab === tab.id ? ' active' : ''}`}
                  onClick={() => setRightTab(tab.id as typeof rightTab)}
                >
                  <span className="d-tab-dot" style={{ background: tab.dot }}/>
                  {tab.label}
                </button>
              ))}
            </div>

            {/* Trains Tab */}
            {rightTab === 'trains' && (
              <div className="d-right-scroll">
                <div className="d-panel">
                  <div className="d-tbl-scroll">
                    <table className="d-tbl">
                      <thead><tr><th>车次</th><th>方向</th><th>位置</th><th>速度</th><th>状态</th><th>交路</th><th>折返</th><th>策略</th></tr></thead>
                      <tbody>
                        {trains.length===0?<tr><td colSpan={8} className="d-empty">点击「启动」</td></tr>:trains.map(t=><tr key={t.trainId}><td><span className="d-train-id">{t.trainNumber||t.trainName}</span></td><td><span style={{color:t.direction==='DOWN'?'#f7b731':'#06d6a0',fontWeight:600,fontSize:10}}>{t.direction==='DOWN'?'↓下行':'↑上行'}</span></td><td className="d-mono">{fmtNum(t.positionMeters)}</td><td className="d-mono">{t.speed>0?t.speed.toFixed(0)+' km/h':'—'}</td><td><span className="d-status-dot" style={{background:STATUS_COLOR[t.status]}}/>{STATUS_LABEL[t.status]}{t.status==='DWELLING'?<span style={{fontSize:9,color:'#617088',marginLeft:4}}>⌛{t.actualDwellSeconds.toFixed(0)}/{t.plannedDwellSeconds.toFixed(0)}s</span>:t.status==='TURNING_BACK'?<span style={{fontSize:9,color:'#617088',marginLeft:4}}>⌛{t.actualDwellSeconds.toFixed(0)}/{t.plannedDwellSeconds.toFixed(0)}s</span>:t.status==='DEPOT_WAITING'&&t.plannedDepartureFromDepot>0?<span style={{fontSize:9,color:'#617088',marginLeft:4}}>⌛{(t.plannedDepartureFromDepot-snapshot!.simulationTime).toFixed(0)}s</span>:null}</td><td style={{fontSize:10,color:'#94a3b8'}}>{t.routePattern==='SHORT_S'?'南段小交路':t.routePattern==='SHORT_N'?'北段小交路':'全程'}{t.skipNextStation?<span style={{color:'#fc5c65',marginLeft:4}}>甩站中</span>:null}{t.operationLevel!=='NORMAL'&&t.operationLevel?<span style={{color:'#ff9f43',marginLeft:4}}>{t.operationLevel==='EXPRESS'?'快车':t.operationLevel==='ENERGY_SAVE'?'节能':'慢行'}</span>:null}</td><td className="d-mono" style={{color:t.turnbackCount>0?'#f7b731':'#374151'}}>{t.turnbackCount>0?t.turnbackCount:'—'}</td><td style={{display:'flex',gap:2}}>{t.status!=='FINISHED'&&t.status!=='DEPOT_WAITING'&&<><button className="d-act-btn" onClick={()=>doStrategy(t.trainId,'SKIP_STATION')} title="甩站">⊘</button><button className="d-act-btn" onClick={()=>doStrategy(t.trainId,'CHANGE_LEVEL',1)} title="快车">⚡</button><button className="d-act-btn" onClick={()=>doStrategy(t.trainId,'CHANGE_LEVEL',0)} title="节能">♻</button><button className="d-act-btn" onClick={()=>doStrategy(t.trainId,'RESUME_NORMAL')} title="恢复">↺</button></>}</td></tr>)}</tbody>
                    </table>
                  </div>
                </div>
                {/* Headways */}
                <div className="d-panel" style={{ marginTop: 8 }}>
                  <div className="d-panel-head"><span className="d-panel-title"><span className="d-panel-dot" style={{background:'#f7b731'}}/>车头时距</span><span className="d-panel-badge">{headways.length}</span></div>
                  <div className="d-tbl-scroll d-tbl-scroll-sm">
                    <table className="d-tbl">
                      <thead><tr><th>后车</th><th>前车</th><th>间距</th><th>安全距</th><th>时距</th><th>状态</th></tr></thead>
                      <tbody>
                        {headways.length===0?<tr><td colSpan={6} className="d-empty">暂无</td></tr>:headways.map((h,i)=><tr key={i}><td><span className="d-train-id">{h.fromTrainId}</span></td><td><span className="d-train-id">{h.toTrainId}</span></td><td className="d-mono">{fmtNum(h.distanceMeters)}</td><td className="d-mono">{h.safetyDistanceMeters>0?fmtNum(h.safetyDistanceMeters):'—'}</td><td className="d-mono">{h.timeSeconds.toFixed(0)}</td><td><span className="d-h-badge" style={{'--c':h.status==='SAFE'?'#06d6a0':h.status==='CAUTION'?'#f7b731':h.status==='WARNING'?'#ff9f43':'#fc5c65','--bg':(h.status==='SAFE'?'#06d6a0':h.status==='CAUTION'?'#f7b731':h.status==='WARNING'?'#ff9f43':'#fc5c65')+'18'} as React.CSSProperties}>{h.status==='SAFE'?'安全':h.status==='CAUTION'?'关注':h.status==='WARNING'?'警告':'危险'}</span></td></tr>)}
                      </tbody>
                    </table>
                  </div>
                </div>
              </div>
            )}

            {/* Arrivals Tab */}
            {rightTab === 'arrivals' && (
              <div className="d-right-scroll">
                <div className="d-panel">
                  <div className="d-tbl-scroll">
                    <table className="d-tbl">
                      <thead><tr><th>车次</th><th>车站</th><th>到站</th><th>发车</th><th>停站</th></tr></thead>
                      <tbody>
                        {arrivals.length===0?<tr><td colSpan={5} className="d-empty">暂无到站记录</td></tr>:arrivals.slice().reverse().map((a,i)=><tr key={i}><td><span className="d-train-id">{a.trainId}</span></td><td style={{fontWeight:600,color:'#e2e8f0'}}>{a.stationName}</td><td className="d-mono">{fmtTime(a.arrivalTimeSeconds)}</td><td className="d-mono">{a.departureTimeSeconds>0?fmtTime(a.departureTimeSeconds):'—'}</td><td className="d-mono">{a.dwellSeconds>0?a.dwellSeconds.toFixed(0)+'s':'—'}</td></tr>)}
                      </tbody>
                    </table>
                  </div>
                </div>
              </div>
            )}

            {/* Commands Tab */}
            {rightTab === 'commands' && (
              <div className="d-right-scroll d-right-scroll-flex">
                <div className="d-panel d-panel-flex">
                  <div className="d-cmd-wrap">
                    {commands.length===0 && delayEvents.length===0 ? <div className="d-empty">暂无调度指令</div> : (
                      <>
                      {commands.map((c,i)=><div className="d-cmd" key={'c'+i}><span className="d-cmd-tag" style={{'--c':c.commandType==='DEPART'?'#06d6a0':c.commandType==='HOLD'?'#f7b731':c.commandType==='SLOW'?'#ff9f43':c.commandType==='ARRIVE'?'#45aaf2':c.commandType==='SPEED_UP'?'#06d6a0':c.commandType==='EMERGENCY_BRAKE'?'#fc5c65':c.commandType==='TURN_BACK'?'#ff9f43':c.commandType==='SKIP_STATION'?'#00a8e8':c.commandType==='CHANGE_LEVEL'?'#9b59b6':c.commandType==='SHORT_TURN'?'#fc5c65':c.commandType==='RESUME_NORMAL'?'#06d6a0':'#9b59b6','--bg':(c.commandType==='DEPART'?'#06d6a0':c.commandType==='HOLD'?'#f7b731':c.commandType==='SLOW'?'#ff9f43':c.commandType==='ARRIVE'?'#45aaf2':c.commandType==='SPEED_UP'?'#06d6a0':c.commandType==='EMERGENCY_BRAKE'?'#fc5c65':c.commandType==='TURN_BACK'?'#ff9f43':c.commandType==='SKIP_STATION'?'#00a8e8':c.commandType==='CHANGE_LEVEL'?'#9b59b6':c.commandType==='SHORT_TURN'?'#fc5c65':c.commandType==='RESUME_NORMAL'?'#06d6a0':'#9b59b6')+'14'} as React.CSSProperties}>{c.commandType==='DEPART'?'发车':c.commandType==='HOLD'?'扣车':c.commandType==='SLOW'?'减速':c.commandType==='ARRIVE'?'到站':c.commandType==='SPEED_UP'?'赶点':c.commandType==='EMERGENCY_BRAKE'?'⚡急制':c.commandType==='TURN_BACK'?'⟲折返':c.commandType==='SKIP_STATION'?'⊘甩站':c.commandType==='CHANGE_LEVEL'?'⚡变等':c.commandType==='SHORT_TURN'?'↩短线折':c.commandType==='RESUME_NORMAL'?'↺恢复':'终到'}</span><span className="d-cmd-train">{c.trainId}</span><span className="d-cmd-reason">{c.reason}</span></div>)}
                      {delayEvents.slice().reverse().slice(0, 10).map((e,i)=><div className="d-cmd d-cmd-delay" key={'e'+i}><span className="d-cmd-tag" style={{'--c':e.eventType==='PRIMARY_DELAY'?'#f7b731':e.eventType==='PROPAGATED'?'#fc5c65':'#06d6a0','--bg':(e.eventType==='PRIMARY_DELAY'?'#f7b731':e.eventType==='PROPAGATED'?'#fc5c65':'#06d6a0')+'14'} as React.CSSProperties}>{e.eventType==='PRIMARY_DELAY'?'初始晚点':e.eventType==='PROPAGATED'?'传播晚点':'已恢复'}</span><span className="d-cmd-train">{e.trainId}</span><span className="d-cmd-reason">+{e.delaySeconds.toFixed(0)}s {e.cause}{e.affectedTrainId?' → '+e.affectedTrainId:''} @{fmtTime(e.timeSeconds)}</span></div>)}
                      </>
                    )}
                  </div>
                </div>
              </div>
            )}

            {/* Flow Tab — 断面客流 */}
            {rightTab === 'flow' && flow && (
              <div className="d-right-scroll">
                <div className="d-panel" style={{ marginBottom: 8 }}>
                  <div className="d-panel-head">
                    <span className="d-panel-title"><span className="d-panel-dot" style={{background:'#f7b731'}}/>最大断面客流</span>
                    <span className="d-panel-badge">峰值 {flow.peakSectionFlow.toFixed(0)} p/h</span>
                  </div>
                  <div style={{padding: '10px 12px'}}>
                    <div style={{display:'flex',alignItems:'center',gap:8,fontSize:11,color:'#cbd5e1'}}>
                      最大压力断面位于站点 <b style={{color:'#fc5c65'}}>#{flow.peakSectionStationId}</b>
                      <span style={{color:'#617088',fontSize:10}}>满载率目标 {(flow.targetLoadFactor*100).toFixed(0)}%</span>
                    </div>
                  </div>
                </div>

                <div className="d-panel">
                  <div className="d-panel-head">
                    <span className="d-panel-title"><span className="d-panel-dot" style={{background:'#00a8e8'}}/>断面客流累加模型</span>
                  </div>
                  <div className="d-tbl-scroll" style={{maxHeight:400}}>
                    <table className="d-tbl">
                      <thead><tr><th>区间</th><th>上车</th><th>下车</th><th>断面客流</th><th>满载率</th></tr></thead>
                      <tbody>
                        {(flow.sectionFlows??[]).map((sf,i)=>{
                          const fromName = stations.find(s=>s.id===sf.fromStationId)?.name ?? `站${sf.fromStationId}`;
                          const toName = stations.find(s=>s.id===sf.toStationId)?.name ?? `站${sf.toStationId}`;
                          const lf = sf.loadFactor;
                          const barColor = lf>1.0?'#fc5c65':lf>0.8?'#f7b731':lf>0.5?'#45aaf2':'#06d6a0';
                          const isPeak = sf.fromStationId === flow.peakSectionStationId;
                          return <tr key={i} style={isPeak?{background:'rgba(252,92,101,0.06)'}:undefined}>
                            <td style={{fontWeight:600,color:'#e2e8f0'}}>
                              {fromName} → {toName}
                              {isPeak && <span style={{fontSize:9,color:'#fc5c65',marginLeft:4}}>PEAK</span>}
                            </td>
                            <td className="d-mono" style={{color:'#06d6a0'}}>+{sf.boarding.toFixed(0)}</td>
                            <td className="d-mono" style={{color:'#fc5c65'}}>-{sf.alighting.toFixed(0)}</td>
                            <td className="d-mono" style={{fontWeight:700}}>
                              <span style={{background:`linear-gradient(90deg,${barColor}22 0%,${barColor}66 ${Math.min(lf*100,100)}%,transparent ${Math.min(lf*100,100)}%)`,padding:'2px 6px',borderRadius:3}}>
                                {sf.load.toFixed(0)}
                              </span>
                            </td>
                            <td className="d-mono" style={{color:barColor,fontWeight:600}}>{(lf*100).toFixed(0)}%</td>
                          </tr>;
                        })}
                      </tbody>
                    </table>
                  </div>
                </div>
              </div>
            )}
            {/* Deviation Tab — 计划/实绩偏差分析 */}
            {rightTab === 'deviation' && (
              <div className="d-right-scroll">
                <div className="d-panel" style={{ marginBottom: 8 }}>
                  <div className="d-panel-head">
                    <span className="d-panel-title"><span className="d-panel-dot" style={{background:'#9b59b6'}}/>计划-实绩偏差</span>
                    <span className="d-panel-badge">
                      {planDeviations.filter(d=>Math.abs(d.arrivalDeviation)>30).length} 晚点
                    </span>
                  </div>
                  <div style={{padding: '10px 12px'}}>
                    <div style={{display:'flex',alignItems:'center',gap:12,fontSize:11,color:'#cbd5e1'}}>
                      <span>Δ = 实绩 - 计划</span>
                      <span style={{color:'#617088',fontSize:10}}>正值=晚点 / 负值=早点 / |偏差|&gt;60s=显著晚点</span>
                    </div>
                  </div>
                </div>

                <div className="d-panel">
                  <div className="d-panel-head">
                    <span className="d-panel-title"><span className="d-panel-dot" style={{background:'#00a8e8'}}/>到站/发车偏差明细</span>
                  </div>
                  <div className="d-tbl-scroll" style={{maxHeight:420}}>
                    <table className="d-tbl">
                      <thead><tr><th>车次</th><th>站台</th><th>计划到</th><th>实际到</th><th>Δ到</th><th>计划发</th><th>实际发</th><th>Δ发</th></tr></thead>
                      <tbody>
                        {planDeviations.length===0?<tr><td colSpan={8} className="d-empty">暂无偏差数据</td></tr>:
                        planDeviations
                          .sort((a,b)=>Math.abs(b.arrivalDeviation)-Math.abs(a.arrivalDeviation))
                          .slice(0,30)
                          .map((d,i)=>{
                            const arrColor = Math.abs(d.arrivalDeviation)>60?'#fc5c65':d.arrivalDeviation>0?'#f7b731':'#06d6a0';
                            const depColor = Math.abs(d.departureDeviation)>60?'#fc5c65':d.departureDeviation>0?'#f7b731':'#06d6a0';
                            return <tr key={i}>
                              <td><span className="d-train-id">{d.trainId}</span></td>
                              <td style={{fontWeight:600}}>{d.stationName}</td>
                              <td className="d-mono" style={{color:'#64748b'}}>{fmtTime(d.plannedArrivalSeconds)}</td>
                              <td className="d-mono">{fmtTime(d.arrivalTimeSeconds)}</td>
                              <td className="d-mono" style={{color:arrColor,fontWeight:600}}>{d.arrivalDeviation>0?'+':''}{d.arrivalDeviation.toFixed(0)}s</td>
                              <td className="d-mono" style={{color:'#64748b'}}>{fmtTime(d.plannedDepartureSeconds)}</td>
                              <td className="d-mono">{fmtTime(d.departureTimeSeconds)}</td>
                              <td className="d-mono" style={{color:depColor,fontWeight:600}}>{d.departureDeviation>0?'+':''}{d.departureDeviation.toFixed(0)}s</td>
                            </tr>;
                          })
                        }
                      </tbody>
                    </table>
                  </div>
                </div>

                {/* 汇总统计 */}
                {planDeviations.length>0 && (()=>{
                  const lateArr = planDeviations.filter(d=>d.arrivalDeviation>60);
                  const earlyArr = planDeviations.filter(d=>d.arrivalDeviation<-15);
                  const onTime = planDeviations.filter(d=>Math.abs(d.arrivalDeviation)<=60);
                  const maxDelay = planDeviations.reduce((m,d)=>Math.max(m,d.arrivalDeviation),0);
                  const avgDelay = planDeviations.reduce((s,d)=>s+Math.max(0,d.arrivalDeviation),0)/planDeviations.length;
                  return <div className="d-panel" style={{marginTop:8}}>
                    <div className="d-panel-head">
                      <span className="d-panel-title"><span className="d-panel-dot" style={{background:'#06d6a0'}}/>汇总统计</span>
                    </div>
                    <div style={{display:'grid',gridTemplateColumns:'1fr 1fr 1fr',gap:8,padding:'8px 12px'}}>
                      <div style={{textAlign:'center'}}><div className="d-mono" style={{fontSize:18,color:'#06d6a0'}}>{onTime.length}</div><div style={{fontSize:10,color:'#617088'}}>正点 (|Δ|≤60s)</div></div>
                      <div style={{textAlign:'center'}}><div className="d-mono" style={{fontSize:18,color:'#f7b731'}}>{avgDelay.toFixed(0)}s</div><div style={{fontSize:10,color:'#617088'}}>平均晚点</div></div>
                      <div style={{textAlign:'center'}}><div className="d-mono" style={{fontSize:18,color:'#fc5c65'}}>{maxDelay.toFixed(0)}s</div><div style={{fontSize:10,color:'#617088'}}>最大晚点</div></div>
                    </div>
                    <div style={{display:'grid',gridTemplateColumns:'1fr 1fr',gap:4,padding:'0 12px 8px'}}>
                      <span style={{fontSize:10,color:'#617088'}}>显著晚点 (&gt;60s): {lateArr.length}</span>
                      <span style={{fontSize:10,color:'#617088'}}>早点 (&lt;-15s): {earlyArr.length}</span>
                    </div>
                  </div>;
                })()}
              </div>
            )}
          </div>
        </div>
      </div>
    </>
  );
}

/* ================================================================
   Styles
   ================================================================ */

const STYLES = `@import url('https://fonts.googleapis.com/css2?family=JetBrains+Mono:wght@400;500;600;700&display=swap');

.d-root{height:100%;min-height:100%;display:flex;flex-direction:column;background:#060b11;overflow:hidden}
.d-topbar{display:flex;align-items:center;justify-content:space-between;padding:0 16px;height:48px;flex-shrink:0;gap:10px;background:rgba(13,21,32,0.92);backdrop-filter:blur(12px);-webkit-backdrop-filter:blur(12px);border-bottom:1px solid #152433;z-index:20}
.d-tb-left{display:flex;align-items:center;gap:8px;min-width:0}
.d-tb-back{display:flex;align-items:center;justify-content:center;width:28px;height:28px;border-radius:5px;color:#617088;transition:all .15s;flex-shrink:0}
.d-tb-back:hover{background:#182537;color:#94a3b8}
.d-tb-brand{display:flex;align-items:center;gap:7px}
.d-tb-logo{width:28px;height:28px;border-radius:6px;background:linear-gradient(135deg,#00a8e8,#0077b6);display:flex;align-items:center;justify-content:center;color:#fff;box-shadow:0 0 14px rgba(0,168,232,0.25)}
.d-tb-title{font-size:13px;font-weight:700;color:#e2e8f0;letter-spacing:-0.2px;white-space:nowrap}
.d-tb-sub{display:block;font-size:8px;color:#617088;letter-spacing:1.5px;font-weight:600;text-transform:uppercase}
.d-tb-center{flex:1;display:flex;justify-content:center;min-width:0}
.d-tb-clock{text-align:center}
.d-tb-clock-lbl{display:block;font-size:8px;color:#617088;letter-spacing:3px;font-weight:700}
.d-tb-clock-val{font-family:'JetBrains Mono',monospace;font-size:22px;font-weight:700;color:#e2e8f0;letter-spacing:3px;line-height:1.15}
.d-tb-right{display:flex;align-items:center;gap:6px;flex-shrink:0}
.d-tb-err{font-size:10px;color:#fc5c65;max-width:150px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.d-speed-group{display:flex;border-radius:4px;overflow:hidden;border:1px solid #1c2a3e}
.d-speed-btn{padding:3px 8px;font-size:10px;font-weight:600;font-family:'JetBrains Mono',monospace;background:transparent;color:#617088;border:none;cursor:pointer;transition:all .12s;border-right:1px solid #1c2a3e}
.d-speed-btn:last-child{border-right:none}
.d-speed-btn:hover:not(:disabled){background:#182537;color:#94a3b8}
.d-speed-btn.active{background:#00a8e8;color:#060b11}
.d-speed-btn:disabled{opacity:0.35;cursor:not-allowed}
.d-map-mode{display:flex;border-radius:4px;overflow:hidden;border:1px solid #1c2a3e}
.d-mode-btn{padding:3px 7px;font-size:9px;font-weight:600;background:transparent;color:#617088;border:none;cursor:pointer;transition:all .12s;border-right:1px solid #1c2a3e;text-transform:uppercase;letter-spacing:.5px}
.d-mode-btn:last-child{border-right:none}
.d-mode-btn:hover{background:#182537;color:#94a3b8}
.d-mode-btn.active{background:#1c2a3e;color:#00a8e8}
.d-btn{display:flex;align-items:center;gap:4px;padding:4px 12px;border:none;border-radius:4px;font-size:11px;font-weight:600;cursor:pointer;transition:all .12s;white-space:nowrap}
.d-btn:disabled{opacity:0.35;cursor:not-allowed}
.d-btn-start{background:#06d6a0;color:#060b11}
.d-btn-start:hover:not(:disabled){background:#05c492}
.d-btn-step{background:#1c2a3e;color:#94a3b8}
.d-btn-step:hover:not(:disabled){background:#253448;color:#cbd5e1}
.d-btn-stop{background:#fc5c65;color:#fff}
.d-btn-stop:hover{background:#e55058}

/* ── Body ── */
.d-body{display:grid;grid-template-columns:minmax(0,1fr) 400px;grid-template-rows:auto minmax(0,1fr);flex:1;padding:6px 12px 8px;overflow:hidden;min-height:0;gap:6px 10px;align-items:stretch}
.d-info-bar{grid-column:1/-1;grid-row:1;display:flex;align-items:center;gap:6px;padding:4px 0;width:100%;flex-shrink:0;flex-wrap:wrap}
.d-info-chip{display:inline-flex;align-items:center;padding:2px 8px;border-radius:3px;font-size:10px;font-weight:600;color:var(--c);background:color-mix(in srgb,var(--c) 12%,transparent);border:1px solid color-mix(in srgb,var(--c) 25%,transparent);white-space:nowrap}
@media(max-width:1200px){.d-body{grid-template-columns:minmax(0,1fr);grid-template-rows:auto auto auto;overflow:auto}}

/* ── Left ── */
.d-left{grid-column:1;grid-row:2;min-width:0;min-height:0;display:flex;flex-direction:column;gap:8px}
@media(max-width:1200px){.d-left{grid-column:1;min-height:60vh}}
.d-map-panel{flex:1;min-height:200px;display:flex;flex-direction:column;background:#0d1520;border-radius:8px;overflow:hidden;border:1px solid #1c2a3e}
.d-map-body{flex:1;min-height:150px;position:relative}
.d-map-inner{width:100%;height:100%;position:absolute;top:0;left:0}
.d-map-stats{display:flex;border-top:1px solid #1c2a3e;background:#0a1019;flex-shrink:0}
.d-mstat{flex:1;text-align:center;padding:7px 4px;border-right:1px solid #1c2a3e}
.d-mstat:last-child{border-right:none}
.d-mstat-val{display:block;font-family:'JetBrains Mono',monospace;font-size:12px;font-weight:700;color:#e2e8f0}
.d-mstat-lbl{font-size:9px;color:#617088;margin-top:1px}

/* ── Train Diagram ── */
.d-diagram-panel{height:180px;flex-shrink:0;display:flex;flex-direction:column;background:#0d1520;border-radius:8px;overflow:hidden;border:1px solid #1c2a3e}
.d-diagram{flex:1;width:100%}
.d-empty-panel{flex:1;display:flex;align-items:center;justify-content:center;color:#374151;font-size:12px}

/* ── Panel Head ── */
.d-panel-head{display:flex;align-items:center;justify-content:space-between;padding:7px 12px;background:#0a1019;border-bottom:1px solid #1c2a3e;flex-shrink:0}
.d-panel-title{font-size:11px;font-weight:600;color:#e2e8f0;display:flex;align-items:center;gap:6px;letter-spacing:-0.1px}
.d-panel-dot{width:6px;height:6px;border-radius:50%;flex-shrink:0}
.d-panel-badge{font-size:10px;color:#617088;font-weight:500}

/* ── Right ── */
.d-right{grid-column:2;grid-row:2;min-width:0;min-height:0;display:flex;flex-direction:column;gap:8px;overflow:hidden}
@media(max-width:1200px){.d-right{grid-column:1;width:100%;flex:initial}}

/* ── Tab Bar ── */
.d-tab-bar{display:flex;gap:2px;margin-bottom:8px;flex-shrink:0}
.d-tab{display:flex;align-items:center;gap:5px;padding:6px 12px;font-size:11px;font-weight:600;border:none;border-radius:5px;cursor:pointer;transition:all .15s;background:transparent;color:#617088}
.d-tab:hover{background:#182537;color:#94a3b8}
.d-tab.active{background:#0d1520;color:#e2e8f0;border:1px solid #1c2a3e}
.d-tab-dot{width:5px;height:5px;border-radius:50%}

/* ── Right scroll area ── */
.d-right-scroll{flex:1;overflow-y:auto;min-height:0}
.d-right-scroll-flex{display:flex;flex-direction:column}
.d-right-scroll-flex .d-panel-flex{flex:1;min-height:0}

/* ── Tables ── */
.d-panel{background:#0d1520;border-radius:8px;overflow:hidden;border:1px solid #1c2a3e;display:flex;flex-direction:column}
.d-panel-flex{flex:1;min-height:0}
.d-tbl-scroll{overflow:auto;max-height:240px;min-height:0}
.d-tbl-scroll-sm{max-height:120px}
.d-tbl{width:100%;border-collapse:collapse;font-size:11px}
.d-tbl thead{position:sticky;top:0;z-index:2}
.d-tbl th{background:#0a1019;color:#617088;font-weight:600;padding:6px 8px;text-align:left;border-bottom:1px solid #1c2a3e;font-size:9px;text-transform:uppercase;letter-spacing:.5px}
.d-tbl td{padding:6px 8px;border-bottom:1px solid #152433;color:#cbd5e1}
.d-tbl tbody tr:hover{background:rgba(255,255,255,0.02)}
.d-tbl tbody tr:last-child td{border-bottom:none}
.d-mono{font-family:'JetBrains Mono',monospace;font-size:10px;font-weight:500}
.d-train-id{font-weight:700;color:#e2e8f0;font-family:'JetBrains Mono',monospace}
.d-status-dot{display:inline-block;width:5px;height:5px;border-radius:50%;margin-right:4px;vertical-align:middle}
.d-empty{text-align:center;color:#374151;padding:24px 8px!important;font-size:11px}
.d-h-badge{display:inline-block;padding:1px 6px;border-radius:3px;font-size:9px;font-weight:600;color:var(--c);background:var(--bg)}

/* ── Commands ── */
.d-cmd-wrap{overflow-y:auto;flex:1;min-height:0}
.d-cmd{display:flex;align-items:center;gap:7px;padding:6px 12px;border-bottom:1px solid #152433;font-size:11px}
.d-cmd:last-child{border-bottom:none}
.d-cmd-tag{padding:1px 6px;border-radius:3px;font-size:9px;font-weight:700;color:var(--c);background:var(--bg);white-space:nowrap}
.d-cmd-train{font-weight:700;color:#e2e8f0;font-family:'JetBrains Mono',monospace;font-size:10px;min-width:24px}
.d-cmd-reason{color:#94a3b8;flex:1;font-size:10px}

/* ── Strategy action buttons ── */
.d-act-btn{display:inline-flex;align-items:center;justify-content:center;width:20px;height:18px;border:1px solid #1c2a3e;border-radius:3px;background:transparent;color:#617088;cursor:pointer;font-size:10px;padding:0;transition:all .12s;line-height:1}
.d-act-btn:hover{background:#182537;color:#00a8e8;border-color:#00a8e8}

/* ── Leaflet overrides ── */
.leaflet-container{background:#060b11!important}
.leaflet-control-zoom a{background:#0d1520!important;color:#94a3b8!important;border-color:#1c2a3e!important}
.leaflet-control-zoom a:hover{background:#182537!important;color:#e2e8f0!important}
.leaflet-control-attribution{background:rgba(13,21,32,0.8)!important;color:#617088!important;font-size:8px!important;padding:2px 5px!important}
.leaflet-control-attribution a{color:#00a8e8!important}

.d-tooltip{font-size:11px!important;padding:5px 8px!important;border-radius:5px!important;border:none!important;background:rgba(13,21,32,0.95)!important;color:#e2e8f0!important;box-shadow:0 2px 12px rgba(0,0,0,0.4)!important}
.d-tooltip::before{border-top-color:rgba(13,21,32,0.95)!important}`;
