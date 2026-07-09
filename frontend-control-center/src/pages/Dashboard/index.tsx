import { useState, useEffect, useCallback, useRef } from 'react';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import { getSnapshot, getLineMap } from '../../api/dispatch';
import type { SimulationSnapshot, StationGeo, TrainState, StationArrival } from '../../types/dispatch';

delete (L.Icon.Default.prototype as any)._getIconUrl;
L.Icon.Default.mergeOptions({
  iconRetinaUrl: 'https://unpkg.com/leaflet@1.9.4/dist/images/marker-icon-2x.png',
  iconUrl: 'https://unpkg.com/leaflet@1.9.4/dist/images/marker-icon.png',
  shadowUrl: 'https://unpkg.com/leaflet@1.9.4/dist/images/marker-shadow.png',
});

const STATUS_COLOR: Record<string, string> = {
  DEPOT_WAITING: '#617088', DEPARTING: '#45aaf2', ACCELERATING: '#00a8e8',
  CRUISING: '#06d6a0', BRAKING: '#f7b731', DWELLING: '#9b59b6', TURNING_BACK: '#ff9f43', FINISHED: '#fc5c65',
};
const STATUS_LABEL: Record<string, string> = {
  DEPOT_WAITING: '待发', DEPARTING: '起动', ACCELERATING: '加速', CRUISING: '巡航',
  BRAKING: '制动', DWELLING: '站停', TURNING_BACK: '折返', FINISHED: '终到',
};

const fmtTime = (s: number) =>
  [Math.floor(s / 3600), Math.floor((s % 3600) / 60), Math.floor(s % 60)]
    .map((n) => String(n).padStart(2, '0')).join(':');

const fmtNum = (n: number) => Math.round(n).toLocaleString();

export default function Dashboard() {
  const [snapshot, setSnapshot] = useState<SimulationSnapshot | null>(null);
  const [stations, setStations] = useState<StationGeo[]>([]);
  const [selectedStation, setSelectedStation] = useState<StationGeo | null>(null);
  const [selectedTrain, setSelectedTrain] = useState<TrainState | null>(null);
  const mapRef = useRef<L.Map | null>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const markerRefs = useRef<L.CircleMarker[]>([]);
  const trainMarkerRefs = useRef<L.CircleMarker[]>([]);
  const polyRef = useRef<L.Polyline | null>(null);

  // Poll snapshot every 1s
  useEffect(() => {
    getLineMap().then(setStations).catch(() => {});
    const timer = setInterval(async () => {
      try { const s = await getSnapshot(); if (s) setSnapshot(s); } catch {}
    }, 1000);
    return () => clearInterval(timer);
  }, []);

  // Init map
  useEffect(() => {
    if (!containerRef.current || mapRef.current) return;
    const map = L.map(containerRef.current, { center: [39.88, 116.31], zoom: 12, zoomControl: true, attributionControl: false });
    L.tileLayer('https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png', { maxZoom: 19 }).addTo(map);
    mapRef.current = map;
    const fix = () => map.invalidateSize();
    setTimeout(fix, 200); setTimeout(fix, 600);
    window.addEventListener('resize', fix);
    return () => { window.removeEventListener('resize', fix); map.remove(); mapRef.current = null; };
  }, []);

  // Station markers + polyline
  useEffect(() => {
    const map = mapRef.current; if (!map || stations.length === 0) return;
    markerRefs.current.forEach(m => m.remove()); markerRefs.current = [];
    polyRef.current?.remove();
    const sorted = [...stations].sort((a, b) => a.id - b.id);
    polyRef.current = L.polyline(sorted.map(s => [s.latitude, s.longitude] as [number, number]), { color: '#00a8e8', weight: 2.5, opacity: 0.8 }).addTo(map);
    sorted.forEach(s => {
      const m = L.circleMarker([s.latitude, s.longitude], {
        radius: s.id === 1 || s.id === sorted.length ? 8 : 6,
        fillColor: '#060b11', fillOpacity: 1, color: '#00a8e8', weight: 2,
      }).addTo(map);
      m.on('click', () => setSelectedStation(s));
      m.bindTooltip(`<b>${s.name}</b><br/>${s.km.toFixed(1)} km`, { direction: 'top', className: 'dash-tooltip' });
      markerRefs.current.push(m);
    });
    const bounds = L.latLngBounds(sorted.map(s => [s.latitude, s.longitude] as [number, number]));
    map.fitBounds(bounds, { padding: [20, 20] });
  }, [stations]);

  // Train markers on map
  useEffect(() => {
    const map = mapRef.current; if (!map || stations.length === 0) return;
    trainMarkerRefs.current.forEach(m => m.remove()); trainMarkerRefs.current = [];
    const sorted = [...stations].sort((a, b) => a.id - b.id);
    const trains = snapshot?.trains ?? [];
    trains.filter(t => t.status !== 'FINISHED' && t.status !== 'DEPOT_WAITING').forEach(t => {
      // Simple interpolation
      const posKm = t.positionMeters / 1000;
      let lat = sorted[0].latitude, lng = sorted[0].longitude;
      for (let i = 1; i < sorted.length; i++) {
        if (posKm <= sorted[i].km) {
          const r = (posKm - sorted[i-1].km) / (sorted[i].km - sorted[i-1].km);
          lat = sorted[i-1].latitude + (sorted[i].latitude - sorted[i-1].latitude) * r;
          lng = sorted[i-1].longitude + (sorted[i].longitude - sorted[i-1].longitude) * r;
          break;
        }
      }
      const bg = STATUS_COLOR[t.status] || '#06d6a0';
      const m = L.circleMarker([lat, lng], { radius: 6, fillColor: bg, fillOpacity: 0.9, color: '#fff', weight: 1.5 }).addTo(map);
      m.on('click', () => setSelectedTrain(t));
      m.bindTooltip(`<b>${t.trainNumber||t.trainName}</b> ${t.direction==='DOWN'?'↓':'↑'}<br/>${Math.round(t.speed)} km/h`, { direction: 'top', className: 'dash-tooltip' });
      trainMarkerRefs.current.push(m);
    });
  }, [snapshot, stations]);

  const trains = snapshot?.trains ?? [];
  const arrivals = snapshot?.stationArrivals ?? [];
  const delayEvents = snapshot?.delayEvents ?? [];
  const flow = snapshot?.passengerFlow ?? null;
  const activeTrains = trains.filter(t => t.status !== 'FINISHED').length;
  const dwellTrains = trains.filter(t => t.status === 'DWELLING' || t.status === 'TURNING_BACK').length;
  const runningTrains = trains.filter(t => !['FINISHED', 'DEPOT_WAITING', 'DWELLING', 'TURNING_BACK'].includes(t.status)).length;
  const avgSpeed = runningTrains > 0 ? trains.filter(t => !['FINISHED', 'DEPOT_WAITING', 'DWELLING', 'TURNING_BACK'].includes(t.status)).reduce((s, t) => s + t.speed, 0) / runningTrains : 0;
  const devs = snapshot?.planDeviations ?? [];
  const onTimeCount = devs.filter(d => Math.abs(d.arrivalDeviation) <= 60).length;
  const onTimeRate = devs.length > 0 ? (onTimeCount / devs.length * 100) : 100;

  // Station arrivals for selected station
  const stationArrivals = selectedStation
    ? arrivals.filter(a => a.stationName === selectedStation.name).slice(-20)
    : [];
  const stationTrains = selectedStation
    ? trains.filter(t => {
        // Approximate: check if train is near this station
        if (!t.nextStationKm) return false;
        const dist = Math.abs(t.positionMeters / 1000 - selectedStation.km);
        return dist < 1.5; // within 1.5km
      })
    : [];

  return (
    <>
      <style>{DASH_STYLES}</style>
      <div className="dash-root">
        {/* Top KPI Row */}
        <div className="dash-kpi-row">
          <div className="dash-kpi">
            <div className="dash-kpi-icon" style={{background:'linear-gradient(135deg,#00a8e8,#0077b6)'}}>🚄</div>
            <div className="dash-kpi-body">
              <div className="dash-kpi-val">{activeTrains}<span className="dash-kpi-unit">/{snapshot?.totalTrains??0}</span></div>
              <div className="dash-kpi-lbl">在线列车</div>
            </div>
          </div>
          <div className="dash-kpi">
            <div className="dash-kpi-icon" style={{background:'linear-gradient(135deg,#06d6a0,#059669)'}}>⚡</div>
            <div className="dash-kpi-body">
              <div className="dash-kpi-val">{runningTrains}</div>
              <div className="dash-kpi-lbl">运行中 · {avgSpeed.toFixed(0)} km/h 均速</div>
            </div>
          </div>
          <div className="dash-kpi">
            <div className="dash-kpi-icon" style={{background:'linear-gradient(135deg,#9b59b6,#6c3a8a)'}}>⌚</div>
            <div className="dash-kpi-body">
              <div className="dash-kpi-val">{onTimeRate.toFixed(0)}<span className="dash-kpi-unit">%</span></div>
              <div className="dash-kpi-lbl">正点率 · {onTimeCount}/{devs.length}班次</div>
            </div>
          </div>
          <div className="dash-kpi">
            <div className="dash-kpi-icon" style={{background:'linear-gradient(135deg,#f7b731,#d4a017)'}}>⏱</div>
            <div className="dash-kpi-body">
              <div className="dash-kpi-val">{snapshot ? fmtTime(snapshot.simulationTime) : '--:--:--'}</div>
              <div className="dash-kpi-lbl">仿真时钟 · {snapshot?.dispatchInfo?.dispatchMode==='NORMAL'?'正常运营':snapshot?.dispatchInfo?.dispatchMode||'--'}</div>
            </div>
          </div>
          <div className="dash-kpi">
            <div className="dash-kpi-icon" style={{background:'linear-gradient(135deg,#fc5c65,#e55058)'}}>⚠</div>
            <div className="dash-kpi-body">
              <div className="dash-kpi-val" style={{color: delayEvents.length>0?'#fc5c65':'#06d6a0'}}>{delayEvents.length}</div>
              <div className="dash-kpi-lbl">告警事件</div>
            </div>
          </div>
          <div className="dash-kpi">
            <div className="dash-kpi-icon" style={{background:'linear-gradient(135deg,#45aaf2,#2d8ccc)'}}>🔋</div>
            <div className="dash-kpi-body">
              <div className="dash-kpi-val">{(snapshot?.totalEnergyKwh??0).toFixed(1)}<span className="dash-kpi-unit">kWh</span></div>
              <div className="dash-kpi-lbl">牵引用电</div>
            </div>
          </div>
        </div>

        {/* Main Content */}
        <div className="dash-main">
          {/* Map + Station Detail */}
          <div className="dash-left">
            <div className="dash-panel dash-map-panel">
              <div className="dash-panel-head">
                <span>📍 线路运行态势</span>
                <span className="dash-panel-sub">{stations.length}站 · {flow?.period||'--'}</span>
              </div>
              <div ref={containerRef} className="dash-map-inner" />
            </div>

            {/* Station Detail */}
            {selectedStation && (
              <div className="dash-panel dash-station-panel">
                <div className="dash-panel-head">
                  <span>🚉 {selectedStation.name} <span style={{color:'#617088',fontSize:10}}>{selectedStation.code}</span></span>
                  <button className="dash-close-btn" onClick={() => setSelectedStation(null)}>✕</button>
                </div>
                <div className="dash-station-body">
                  <div className="dash-station-info">
                    <span>里程 {selectedStation.km.toFixed(2)} km</span>
                    <span>附近 {stationTrains.length} 列车</span>
                    <span>到站记录 {stationArrivals.length}</span>
                  </div>
                  <div className="dash-station-trains">
                    <div className="dash-section-title">附近列车</div>
                    {stationTrains.length === 0 ? <div className="dash-empty">暂无列车在附近</div> :
                      stationTrains.slice(0, 6).map(t => (
                        <div key={t.trainId} className="dash-train-mini" onClick={() => setSelectedTrain(t)}>
                          <span className="dash-train-mini-dot" style={{background:STATUS_COLOR[t.status]}}/>
                          <span className="dash-train-mini-id">{t.trainNumber||t.trainName}</span>
                          <span style={{fontSize:9,color:t.direction==='DOWN'?'#f7b731':'#06d6a0'}}>{t.direction==='DOWN'?'↓':'↑'}</span>
                          <span style={{fontSize:10,color:'#94a3b8',flex:1}}>{STATUS_LABEL[t.status]} · {Math.round(t.speed)} km/h</span>
                          <span style={{fontSize:9,color:'#617088'}}>{fmtNum(t.positionMeters)} m</span>
                        </div>
                      ))
                    }
                  </div>
                  <div className="dash-station-arrivals">
                    <div className="dash-section-title">最近到站记录</div>
                    {stationArrivals.length === 0 ? <div className="dash-empty">暂无记录</div> :
                      stationArrivals.slice(-8).reverse().map((a, i) => (
                        <div key={i} className="dash-arrival-row">
                          <span className="dash-train-mini-id">{a.trainId}</span>
                          <span style={{fontSize:10,color:'#94a3b8'}}>到 {fmtTime(a.arrivalTimeSeconds)}</span>
                          <span style={{fontSize:10,color:'#617088'}}>停 {a.dwellSeconds.toFixed(0)}s</span>
                          {a.arrivalDeviation !== undefined && (
                            <span style={{fontSize:10,color:Math.abs(a.arrivalDeviation)>60?'#fc5c65':a.arrivalDeviation>0?'#f7b731':'#06d6a0'}}>
                              {a.arrivalDeviation>0?'+':''}{a.arrivalDeviation.toFixed(0)}s
                            </span>
                          )}
                        </div>
                      ))
                    }
                  </div>
                </div>
              </div>
            )}
          </div>

          {/* Right Side: Train Fleet + Alerts */}
          <div className="dash-right">
            {/* Train Detail */}
            {selectedTrain ? (
              <div className="dash-panel dash-train-detail">
                <div className="dash-panel-head">
                  <span>🚄 {selectedTrain.trainNumber || selectedTrain.trainName}</span>
                  <button className="dash-close-btn" onClick={() => setSelectedTrain(null)}>✕</button>
                </div>
                <div className="dash-train-detail-body">
                  <div className="dash-detail-grid">
                    <div className="dash-detail-item">
                      <div className="dash-detail-lbl">车次号</div>
                      <div className="dash-detail-val">{selectedTrain.trainNumber||selectedTrain.trainName}</div>
                    </div>
                    <div className="dash-detail-item">
                      <div className="dash-detail-lbl">方向</div>
                      <div className="dash-detail-val" style={{color:selectedTrain.direction==='DOWN'?'#f7b731':'#06d6a0'}}>
                        {selectedTrain.direction==='DOWN'?'↓下行(国图→郭公庄)':'↑上行(郭公庄→国图)'}
                      </div>
                    </div>
                    <div className="dash-detail-item">
                      <div className="dash-detail-lbl">状态</div>
                      <div className="dash-detail-val">
                        <span className="dash-status-dot" style={{background:STATUS_COLOR[selectedTrain.status]}}/>
                        {STATUS_LABEL[selectedTrain.status]}
                      </div>
                    </div>
                    <div className="dash-detail-item">
                      <div className="dash-detail-lbl">速度</div>
                      <div className="dash-detail-val">{selectedTrain.speed.toFixed(0)} km/h</div>
                    </div>
                    <div className="dash-detail-item">
                      <div className="dash-detail-lbl">位置</div>
                      <div className="dash-detail-val">{fmtNum(selectedTrain.positionMeters)} m</div>
                    </div>
                    <div className="dash-detail-item">
                      <div className="dash-detail-lbl">交路</div>
                      <div className="dash-detail-val">{selectedTrain.routePattern==='SHORT_S'?'南段小交路':selectedTrain.routePattern==='SHORT_N'?'北段小交路':'全程'}</div>
                    </div>
                    <div className="dash-detail-item">
                      <div className="dash-detail-lbl">运行等级</div>
                      <div className="dash-detail-val">{selectedTrain.operationLevel==='EXPRESS'?'快车':selectedTrain.operationLevel==='ENERGY_SAVE'?'节能':selectedTrain.operationLevel==='SLOW'?'慢行':'正常'}</div>
                    </div>
                    <div className="dash-detail-item">
                      <div className="dash-detail-lbl">折返次数</div>
                      <div className="dash-detail-val">{selectedTrain.turnbackCount||0}</div>
                    </div>
                    <div className="dash-detail-item">
                      <div className="dash-detail-lbl">晚点</div>
                      <div className="dash-detail-val" style={{color:selectedTrain.delaySeconds>60?'#fc5c65':selectedTrain.delaySeconds>0?'#f7b731':'#06d6a0'}}>
                        {selectedTrain.delaySeconds>0?'+'+selectedTrain.delaySeconds.toFixed(0)+'s':'正点'}
                      </div>
                    </div>
                    <div className="dash-detail-item">
                      <div className="dash-detail-lbl">MA授权</div>
                      <div className="dash-detail-val">{selectedTrain.movementAuthority>0?fmtNum(selectedTrain.movementAuthority)+' m':'—'}</div>
                    </div>
                    <div className="dash-detail-item">
                      <div className="dash-detail-lbl">紧急制动</div>
                      <div className="dash-detail-val" style={{color:selectedTrain.emergencyBraking?'#fc5c65':'#617088'}}>
                        {selectedTrain.emergencyBraking?'⚠ 激活':'正常'}
                      </div>
                    </div>
                    <div className="dash-detail-item">
                      <div className="dash-detail-lbl">甩站标志</div>
                      <div className="dash-detail-val" style={{color:selectedTrain.skipNextStation?'#fc5c65':'#617088'}}>
                        {selectedTrain.skipNextStation?'⊘ 甩站中':'否'}
                      </div>
                    </div>
                  </div>
                  {/* Progress bar */}
                  {selectedTrain.status !== 'DEPOT_WAITING' && selectedTrain.status !== 'FINISHED' && (
                    <div className="dash-progress-section">
                      <div className="dash-section-title">区间进度</div>
                      <div className="dash-progress-bar">
                        <div className="dash-progress-fill" style={{
                          width: selectedTrain.sectionDistance>0
                            ? `${Math.min(100, (selectedTrain.sectionProgress/selectedTrain.sectionDistance)*100)}%`
                            : '0%'
                        }}/>
                      </div>
                      <div style={{display:'flex',justifyContent:'space-between',fontSize:9,color:'#617088',marginTop:4}}>
                        <span>{selectedTrain.sectionProgress.toFixed(0)} m</span>
                        <span>{selectedTrain.sectionDistance.toFixed(0)} m</span>
                      </div>
                    </div>
                  )}
                </div>
              </div>
            ) : (
              <div className="dash-panel dash-train-detail">
                <div className="dash-panel-head">
                  <span>🚄 选择列车或站点查看详情</span>
                </div>
                <div className="dash-empty" style={{padding:32}}>点击地图上的列车标记或站点标记查看详细信息</div>
              </div>
            )}

            {/* Fleet Summary */}
            <div className="dash-panel dash-fleet-panel">
              <div className="dash-panel-head">
                <span>🚂 车队状态</span>
                <span className="dash-panel-sub">{activeTrains}在线 · {dwellTrains}站停 · {runningTrains}运行</span>
              </div>
              <div className="dash-fleet-grid">
                {trains.slice(0, 12).map(t => (
                  <div key={t.trainId} className={`dash-fleet-card ${selectedTrain?.trainId===t.trainId?'active':''}`}
                    onClick={() => setSelectedTrain(t)}
                  >
                    <div className="dash-fleet-card-top">
                      <span className="dash-fleet-id">{t.trainNumber||t.trainName}</span>
                      <span style={{fontSize:8,color:t.direction==='DOWN'?'#f7b731':'#06d6a0'}}>{t.direction==='DOWN'?'↓':'↑'}</span>
                    </div>
                    <div className="dash-fleet-status">
                      <span className="dash-status-dot-small" style={{background:STATUS_COLOR[t.status]}}/>
                      {STATUS_LABEL[t.status]}
                    </div>
                    <div className="dash-fleet-speed">
                      {t.status==='DEPOT_WAITING' ? '待发' :
                       t.status==='FINISHED' ? '终到' :
                       `${Math.round(t.speed)} km/h`}
                    </div>
                    <div className="dash-fleet-bar-bg">
                      <div className="dash-fleet-bar-fill" style={{
                        width: t.status==='DEPOT_WAITING'?'0%':t.status==='FINISHED'?'100%':
                          `${Math.min(100, (t.positionMeters/16050)*100)}%`,
                        background: STATUS_COLOR[t.status]
                      }}/>
                    </div>
                  </div>
                ))}
              </div>
            </div>

            {/* Alert Feed */}
            <div className="dash-panel dash-alert-panel">
              <div className="dash-panel-head">
                <span>⚠ 事件日志</span>
                <span className="dash-panel-sub">{delayEvents.length} 条</span>
              </div>
              <div className="dash-alert-list">
                {delayEvents.length === 0 ? <div className="dash-empty" style={{padding:16}}>暂无事件</div> :
                  delayEvents.slice(-15).reverse().map((e, i) => (
                    <div key={i} className="dash-alert-row" style={{
                      borderLeftColor: e.eventType==='PRIMARY_DELAY'?'#f7b731':e.eventType==='PROPAGATED'?'#fc5c65':'#06d6a0'
                    }}>
                      <span className="dash-alert-time">{fmtTime(e.timeSeconds)}</span>
                      <span className="dash-alert-train">{e.trainId}</span>
                      <span className="dash-alert-msg">{e.cause}</span>
                      <span style={{fontSize:9,fontWeight:700,color:e.eventType==='RECOVERED'?'#06d6a0':'#f7b731'}}>
                        {e.eventType==='PRIMARY_DELAY'?'初始':e.eventType==='PROPAGATED'?'传播':'恢复'}
                      </span>
                    </div>
                  ))
                }
              </div>
            </div>
          </div>
        </div>
      </div>
    </>
  );
}

const DASH_STYLES = `
.dash-root{height:100%;display:flex;flex-direction:column;background:#060b11;overflow:hidden;color:#e2e8f0;font-family:-apple-system,sans-serif}
.dash-kpi-row{display:flex;gap:8px;padding:10px 16px;flex-shrink:0;overflow-x:auto}
.dash-kpi{display:flex;align-items:center;gap:10px;padding:10px 16px;background:#0d1520;border-radius:8px;border:1px solid #1c2a3e;min-width:160px;flex:1}
.dash-kpi-icon{width:36px;height:36px;border-radius:8px;display:flex;align-items:center;justify-content:center;font-size:16px;flex-shrink:0}
.dash-kpi-body{min-width:0}
.dash-kpi-val{font-family:'JetBrains Mono',monospace;font-size:18px;font-weight:700;line-height:1.1}
.dash-kpi-unit{font-size:11px;color:#617088;font-weight:500;margin-left:2px}
.dash-kpi-lbl{font-size:10px;color:#617088;margin-top:1px;white-space:nowrap}

.dash-main{display:grid;grid-template-columns:1fr 420px;flex:1;min-height:0;padding:0 16px 10px;gap:8px;overflow:hidden}

.dash-left{display:flex;flex-direction:column;gap:8px;min-width:0;min-height:0}
.dash-right{display:flex;flex-direction:column;gap:8px;min-width:0;overflow-y:auto}

.dash-panel{background:#0d1520;border-radius:8px;overflow:hidden;border:1px solid #1c2a3e;display:flex;flex-direction:column}
.dash-panel-head{display:flex;align-items:center;justify-content:space-between;padding:8px 12px;background:#0a1019;border-bottom:1px solid #1c2a3e;font-size:11px;font-weight:600;flex-shrink:0}
.dash-panel-sub{font-size:10px;color:#617088;font-weight:500}
.dash-close-btn{width:20px;height:20px;border:none;background:transparent;color:#617088;cursor:pointer;font-size:12px;border-radius:4px;display:flex;align-items:center;justify-content:center}
.dash-close-btn:hover{background:#182537;color:#e2e8f0}

.dash-map-panel{flex:1;min-height:250px}
.dash-map-inner{flex:1;width:100%}

.dash-station-panel{flex-shrink:0;max-height:280px}
.dash-station-body{overflow-y:auto;padding:8px 12px;font-size:10px}
.dash-station-info{display:flex;gap:12px;color:#94a3b8;margin-bottom:8px}
.dash-section-title{font-size:10px;font-weight:700;color:#617088;text-transform:uppercase;letter-spacing:.5px;margin:8px 0 4px}
.dash-station-trains,.dash-station-arrivals{margin-top:4px}

.dash-train-mini{display:flex;align-items:center;gap:6px;padding:4px 6px;cursor:pointer;border-radius:4px}
.dash-train-mini:hover{background:rgba(255,255,255,0.03)}
.dash-train-mini-dot{width:5px;height:5px;border-radius:50%;flex-shrink:0}
.dash-train-mini-id{font-family:'JetBrains Mono',monospace;font-size:9px;font-weight:700;color:#e2e8f0;min-width:28px}
.dash-arrival-row{display:flex;align-items:center;gap:8px;padding:3px 6px;font-size:9px;border-bottom:1px solid #152433}
.dash-arrival-row:last-child{border-bottom:none}

.dash-train-detail{max-height:340px}
.dash-train-detail-body{overflow-y:auto;padding:12px}
.dash-detail-grid{display:grid;grid-template-columns:1fr 1fr;gap:6px}
.dash-detail-item{padding:6px 8px;background:#0a1019;border-radius:5px;border:1px solid #152433}
.dash-detail-lbl{font-size:9px;color:#617088;text-transform:uppercase;letter-spacing:.5px}
.dash-detail-val{font-size:11px;font-weight:600;margin-top:2px;font-family:'JetBrains Mono',monospace}
.dash-status-dot{display:inline-block;width:6px;height:6px;border-radius:50%;margin-right:4px;vertical-align:middle}
.dash-progress-section{margin-top:10px}
.dash-progress-bar{height:4px;background:#1c2a3e;border-radius:2px;overflow:hidden;margin-top:4px}
.dash-progress-fill{height:100%;background:#00a8e8;border-radius:2px;transition:width 0.5s}

.dash-fleet-panel{flex-shrink:0}
.dash-fleet-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(110px,1fr));gap:6px;padding:8px;overflow-y:auto;max-height:260px}
.dash-fleet-card{padding:8px;background:#0a1019;border-radius:6px;border:1px solid #1c2a3e;cursor:pointer;transition:all .12s}
.dash-fleet-card:hover{border-color:#00a8e8;background:rgba(0,168,232,0.04)}
.dash-fleet-card.active{border-color:#00a8e8;background:rgba(0,168,232,0.08)}
.dash-fleet-card-top{display:flex;justify-content:space-between;align-items:center;margin-bottom:3px}
.dash-fleet-id{font-family:'JetBrains Mono',monospace;font-size:10px;font-weight:700}
.dash-fleet-status{font-size:9px;color:#94a3b8;display:flex;align-items:center;gap:3px}
.dash-status-dot-small{width:4px;height:4px;border-radius:50%;flex-shrink:0}
.dash-fleet-speed{font-size:12px;font-weight:700;font-family:'JetBrains Mono',monospace;margin:2px 0}
.dash-fleet-bar-bg{height:3px;background:#1c2a3e;border-radius:2px;overflow:hidden;margin-top:4px}
.dash-fleet-bar-fill{height:100%;border-radius:2px;transition:width 0.5s}

.dash-alert-panel{flex:1;min-height:120px}
.dash-alert-list{overflow-y:auto;flex:1}
.dash-alert-row{display:flex;align-items:center;gap:7px;padding:5px 10px;font-size:9px;border-bottom:1px solid #152433;border-left:2px solid #1c2a3e}
.dash-alert-row:last-child{border-bottom:none}
.dash-alert-time{font-family:'JetBrains Mono',monospace;color:#617088;min-width:48px}
.dash-alert-train{font-family:'JetBrains Mono',monospace;font-weight:700;color:#e2e8f0;min-width:20px}
.dash-alert-msg{color:#94a3b8;flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}

.dash-empty{text-align:center;color:#374151;padding:12px;font-size:11px}
.dash-tooltip{font-size:10px!important;padding:4px 6px!important;border-radius:4px!important;border:none!important;background:rgba(13,21,32,0.95)!important;color:#e2e8f0!important}
.dash-tooltip::before{border-top-color:rgba(13,21,32,0.95)!important}
.leaflet-container{background:#060b11!important}
.leaflet-control-zoom a{background:#0d1520!important;color:#94a3b8!important;border-color:#1c2a3e!important}
`;
