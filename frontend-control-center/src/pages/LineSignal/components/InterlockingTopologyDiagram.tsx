import { useMemo, type MouseEvent } from 'react';
import type { LineProfile, MovingAuthority, SignalAspect, TrainState } from '../../../types/signal';
import type { SelectedEntity } from './TrackDiagram';
import {
  C,
  PLATFORM_CODES,
  STATION_NAMES,
  STATION_SPECS,
  specOf,
  type StationDrawSpec,
} from '../data/teacherDiagramLayout';
import { buildStationTopologies } from '../data/realTopology';

interface Props {
  lineProfile: LineProfile;
  trains: TrainState[];
  maMap: Record<string, MovingAuthority>;
  builtRouteIds: Set<number>;
  activeStationId: string | null;
  selectedEntity: SelectedEntity | null;
  onSelect: (entity: SelectedEntity | null) => void;
  onOpenStation: (stationId: string) => void;
}

type Stn = { id: string; name: string; positionM: number; platformIds: number[] };

/** 默认红；GREEN / 已办进路关联 → 绿；黄单独 */
function signalColor(aspect: SignalAspect | null | undefined, routeOpen: boolean): string {
  if (routeOpen) return C.green;
  if (aspect === 'GREEN' || aspect === 'GREEN_DARK') return C.green;
  if (aspect === 'YELLOW' || aspect === 'YELLOW_DARK' || aspect === 'RED_YELLOW') return C.yellow;
  return C.red;
}

function openSignalSet(line: LineProfile, built: Set<number>): Set<number> {
  const ids = new Set<number>();
  for (const r of line.routes || []) {
    if (!built.has(Number(r.id))) continue;
    if (r.startSignalId != null) ids.add(Number(r.startSignalId));
    if (r.endSignalId != null) ids.add(Number(r.endSignalId));
  }
  return ids;
}

function rail(x1: number, x2: number, y: number, key: string) {
  return (
    <line
      key={key}
      x1={x1}
      y1={y}
      x2={x2}
      y2={y}
      stroke={C.rail}
      strokeWidth={3.2}
      strokeLinecap="butt"
    />
  );
}

function diamond(cx: number, y1: number, y2: number, key: string) {
  const h = Math.min(58, Math.abs(y2 - y1) * 0.48);
  return (
    <path
      key={key}
      d={`M ${cx - h} ${y1} L ${cx + h} ${y2} M ${cx - h} ${y2} L ${cx + h} ${y1}`}
      fill="none"
      stroke={C.railBright}
      strokeWidth={3}
      strokeLinecap="round"
    />
  );
}

function platform(id: string, label: string, x: number, y: number, w = 52) {
  return (
    <g key={`pf-${id}`}>
      <rect x={x - w / 2} y={y} width={w} height={20} fill={C.platform} stroke={C.platformEdge} strokeWidth={1} />
      <rect x={x - w / 2} y={y} width={w} height={4} fill={C.green} />
      <text
        x={x}
        y={y + 15}
        textAnchor="middle"
        fill="#111"
        fontSize={12}
        fontWeight={800}
        fontFamily="Consolas, monospace"
      >
        {label}
      </text>
    </g>
  );
}

function lamp(
  id: number | string,
  x: number,
  railY: number,
  above: boolean,
  color: string,
  selected: boolean,
  onClick?: (e: MouseEvent) => void,
  label?: string,
) {
  const ly = railY + (above ? -26 : 26);
  const r = selected ? 8 : 7;
  return (
    <g
      key={`sig-${id}`}
      className={onClick ? 'il-hit' : undefined}
      onClick={onClick}
    >
      <line x1={x} y1={railY} x2={x} y2={ly} stroke="#DDD" strokeWidth={1.4} />
      <circle cx={x} cy={ly} r={r + 1.5} fill="#000" stroke="#EEE" strokeWidth={1} />
      <circle cx={x} cy={ly} r={r} fill={color} />
      {label && (
        <text
          x={x + 10}
          y={ly + 4}
          fill={color}
          fontSize={11}
          fontWeight={700}
          fontFamily="Consolas, monospace"
        >
          {label}
        </text>
      )}
    </g>
  );
}

function controlRow(x: number, y: number, central: boolean) {
  const items = [
    { t: '中央控', on: central, c: C.controlOn, dx: -48 },
    { t: '站控', on: !central, c: C.controlOn, dx: -16 },
    { t: '紧急站控', on: false, c: C.controlOff, dx: 16 },
    { t: '联锁控', on: true, c: C.interlock, dx: 48 },
  ];
  return (
    <g>
      {items.map((it) => (
        <g key={it.t}>
          <circle cx={x + it.dx} cy={y} r={6} fill={it.on ? it.c : C.controlOff} stroke="#EEE" strokeWidth={0.7} />
          <text x={x + it.dx} y={y + 15} textAnchor="middle" fill={C.muted} fontSize={8}>
            {it.t}
          </text>
        </g>
      ))}
    </g>
  );
}

/**
 * 站1 终端区（老师图左上红框）：拉开间距、放大车挡/库线/信号，避免挤在主轨上。
 */
function terminalWest(x: number, yTop: number, yBot: number) {
  const ym = (yTop + yBot) / 2;
  const left = x - 220;
  const mid = x - 125;
  const wing = Math.min(72, (yBot - yTop) * 0.36);
  return (
    <g>
      <path
        d={`M ${x - 75} ${yTop} L ${mid} ${ym - wing} L ${left} ${ym - wing}`}
        fill="none"
        stroke={C.rail}
        strokeWidth={3.2}
        strokeLinecap="round"
        strokeLinejoin="round"
      />
      <path
        d={`M ${x - 75} ${yBot} L ${mid} ${ym + wing} L ${left} ${ym + wing}`}
        fill="none"
        stroke={C.rail}
        strokeWidth={3.2}
        strokeLinecap="round"
        strokeLinejoin="round"
      />

      <line x1={x - 90} y1={ym} x2={x + 35} y2={ym} stroke={C.rail} strokeWidth={3.2} />
      <path
        d={`M ${x - 58} ${yTop} L ${x - 22} ${ym} M ${x - 58} ${yBot} L ${x - 22} ${ym}`}
        fill="none"
        stroke={C.railBright}
        strokeWidth={2.8}
        strokeLinecap="round"
      />

      <g>
        <line x1={left} y1={ym - wing - 14} x2={left} y2={ym - wing + 14} stroke={C.bumper} strokeWidth={4} />
        <circle cx={left} cy={ym - wing} r={8} fill={C.bumper} stroke="#FFF" strokeWidth={1.2} />
        <text x={left - 18} y={ym - wing - 18} fill={C.bumper} fontSize={11} fontWeight={700}>
          车挡
        </text>
      </g>
      <g>
        <line x1={left} y1={ym + wing - 14} x2={left} y2={ym + wing + 14} stroke={C.bumper} strokeWidth={4} />
        <circle cx={left} cy={ym + wing} r={8} fill={C.bumper} stroke="#FFF" strokeWidth={1.2} />
      </g>

      <text x={mid - 20} y={ym - wing - 14} fill={C.label} fontSize={12} fontWeight={700}>
        库线
      </text>

      {platform('la', 'LA', mid - 10, ym - 14, 36)}
      {platform('aa-term', 'AA', x - 8, ym - 42, 40)}

      {lamp('tw-x01', mid + 10, ym - wing, true, C.red, false, undefined, 'X01')}
      {lamp('tw-x03', mid + 10, ym + wing, false, C.red, false, undefined, 'X03')}
      {lamp('tw-b', x - 100, yTop, true, C.green, false, undefined, 'B')}
      {lamp('tw-c', x - 100, yBot, false, C.red, false, undefined, 'C')}
      {lamp('tw-d', x - 42, ym, true, C.red, false, undefined, 'D')}
    </g>
  );
}

function terminalEast(x: number, yTop: number, yBot: number) {
  const ym = (yTop + yBot) / 2;
  const right = x + 210;
  const mid = x + 120;
  const wing = Math.min(72, (yBot - yTop) * 0.36);
  return (
    <g>
      <path
        d={`M ${x + 75} ${yTop} L ${mid} ${ym - wing} L ${right} ${ym - wing}`}
        fill="none"
        stroke={C.rail}
        strokeWidth={3.2}
        strokeLinecap="round"
        strokeLinejoin="round"
      />
      <path
        d={`M ${x + 75} ${yBot} L ${mid} ${ym + wing} L ${right} ${ym + wing}`}
        fill="none"
        stroke={C.rail}
        strokeWidth={3.2}
        strokeLinecap="round"
        strokeLinejoin="round"
      />
      <circle cx={right} cy={ym - wing} r={8} fill={C.bumper} stroke="#FFF" strokeWidth={1.2} />
      <circle cx={right} cy={ym + wing} r={8} fill={C.bumper} stroke="#FFF" strokeWidth={1.2} />
      <line x1={x - 10} y1={ym} x2={x + 75} y2={ym} stroke={C.rail} strokeWidth={3.2} />
      <path
        d={`M ${x + 38} ${yTop} L ${x + 62} ${ym} M ${x + 38} ${yBot} L ${x + 62} ${ym}`}
        fill="none"
        stroke={C.railBright}
        strokeWidth={2.8}
      />
      <path
        d={`M ${right + 12} ${ym - 12} L ${right + 32} ${ym + 12} M ${right + 12} ${ym + 12} L ${right + 32} ${ym - 12}`}
        stroke={C.rail}
        strokeWidth={3}
      />
    </g>
  );
}

/** 按站里程取邻近信号（不依赖已删的 lineTopology） */
function nearSignals(line: LineProfile, centerM: number, radius: number) {
  const mileage = line.segmentMileage || {};
  return (line.signals || [])
    .map((sig) => {
      const range = mileage[String(sig.segId)];
      const m = range ? range[0] + (sig.offsetCm || 0) / 100 : NaN;
      return { sig, m };
    })
    .filter((x) => Number.isFinite(x.m) && Math.abs(x.m - centerM) <= radius)
    .sort((a, b) => a.m - b.m);
}

export default function InterlockingTopologyDiagram({
  lineProfile,
  trains,
  maMap,
  builtRouteIds,
  activeStationId,
  selectedEntity,
  onSelect,
  onOpenStation,
}: Props) {
  const openSigs = useMemo(
    () => openSignalSet(lineProfile, builtRouteIds),
    [lineProfile, builtRouteIds],
  );

  const stations: Stn[] = useMemo(() => {
    const list = (lineProfile.stations || []).map((s) => {
      const pfs = (lineProfile.platforms || []).filter((p) => s.platformIds?.includes(p.id));
      let pos = s.positionM || 0;
      if (!pos && pfs[0]?.chainage) {
        const m = String(pfs[0].chainage).match(/^[Kk](\d+)\+(\d+(?:\.\d+)?)$/);
        if (m) pos = Number(m[1]) * 1000 + Number(m[2]);
      }
      return {
        id: String(s.id),
        name: s.name,
        positionM: pos,
        platformIds: s.platformIds || [],
      };
    });
    return list.sort((a, b) => a.positionM - b.positionM);
  }, [lineProfile]);

  const stationTopologies = useMemo(() => buildStationTopologies(lineProfile), [lineProfile]);

  const W = 1720;
  const H = 1100;
  const rows = [stations.slice(0, 7), stations.slice(7)];
  // 纵向再拉开：上下行轨距约 200px
  const layouts = [
    { yTop: 200, yBot: 400, head: 40, y0: 20, y1: 1700, foot: 470 },
    { yTop: 680, yBot: 880, head: 520, y0: 20, y1: 1700, foot: 960 },
  ];

  const xEven = (i: number, n: number, y0: number, y1: number, leftExtra = 0) => {
    const padL = 110 + leftExtra;
    const padR = 90;
    if (n <= 1) return (y0 + y1) / 2;
    return y0 + padL + (i / (n - 1)) * (y1 - y0 - padL - padR);
  };

  return (
    <div className="il-canvas">
      <svg viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" role="img" aria-label="教师风格全线联锁站场图">
        <rect width={W} height={H} fill={C.bg} />

        {rows.map((row, ri) => {
          const L = layouts[ri];
          const { yTop, yBot, head, y0, y1, foot } = L;
          const ym = (yTop + yBot) / 2;
          // 上排给站1终端区留出左侧空间
          const leftExtra = ri === 0 ? 100 : 0;
          const xs = row.map((_, i) => xEven(i, row.length, y0, y1, leftExtra));

          return (
            <g key={`row-${ri}`}>
              <text x={y0 + 8} y={head - 12} fill={C.muted} fontSize={12}>
                {row[0] ? (row[0].positionM / 1000).toFixed(3) : '—'}km
              </text>
              <text x={y1} y={head - 12} textAnchor="end" fill={C.muted} fontSize={12}>
                {row[row.length - 1] ? (row[row.length - 1].positionM / 1000).toFixed(3) : '—'}km
              </text>

              {rail(y0, y1, yTop, `rt-${ri}`)}
              {rail(y0, y1, yBot, `rb-${ri}`)}
              <text x={y0 + 8} y={yTop - 8} fill={C.text} fontSize={12} fontWeight={600}>
                下行
              </text>
              <text x={y0 + 8} y={yBot + 18} fill={C.text} fontSize={12} fontWeight={600}>
                上行
              </text>

              {row.map((st, i) => {
                const sp = specOf(st.id);
                if (!sp?.xoAfter || i >= row.length - 1) return null;
                return diamond((xs[i] + xs[i + 1]) / 2, yTop, yBot, `xo-${st.id}`);
              })}

              {row.map((st, i) => {
                const x = xs[i];
                const sp: StationDrawSpec =
                  specOf(st.id) ||
                  STATION_SPECS.find((s) => s.code === st.name) || {
                    id: st.id,
                    code: st.name,
                    order: i + 1 + ri * 7,
                  };
                const codes = PLATFORM_CODES[st.id] || ([`${st.name}A`, `${st.name}B`] as [string, string]);
                const realName = STATION_NAMES[st.id] || st.name;
                const realTopology = stationTopologies.find((item) => String(item.station.id) === st.id);
                // 终端站少叠数据信号，右侧只留少量；普通站也控制数量防挤
                const sigLimit = sp.terminalWest ? 4 : 8;
                const sigs = nearSignals(lineProfile, st.positionM, 420).slice(0, sigLimit);
                const halfGap =
                  i < row.length - 1
                    ? Math.abs(xs[i + 1] - x) / 2
                    : i > 0
                      ? Math.abs(x - xs[i - 1]) / 2
                      : 90;

                return (
                  <g
                    key={st.id}
                    className="il-hit"
                    onClick={(e) => {
                      e.stopPropagation();
                      onOpenStation(st.id);
                    }}
                  >
                    <line x1={x} y1={head} x2={x} y2={foot} stroke="#2A2A2A" strokeDasharray="2 3" strokeWidth={0.8} />

                    {activeStationId === st.id && (
                      <rect
                        x={x - 92}
                        y={head - 13}
                        width={184}
                        height={89}
                        rx={4}
                        fill="rgba(232, 197, 71, 0.07)"
                        stroke="rgba(232, 197, 71, 0.65)"
                        strokeWidth={1.2}
                      />
                    )}

                    <text x={x} y={head + 8} textAnchor="middle" fill={C.station} fontSize={22} fontWeight={800}>
                      站{sp.order} · {realName}
                    </text>
                    <text x={x} y={head + 24} textAnchor="middle" fill={C.muted} fontSize={11}>
                      {st.name} · K{(st.positionM / 1000).toFixed(3)}
                    </text>
                    {controlRow(x, head + 40, i === 0 && ri === 0)}
                    <text x={x} y={head + 71} textAnchor="middle" fill="#687386" fontSize={9}>
                      Seg {realTopology?.segments.length || 0} · 信号 {realTopology?.signals.length || 0} · 道岔 {realTopology?.switches.length || 0}
                    </text>

                    {platform(`${st.id}a`, codes[0], x - 26, ym - 28, 48)}
                    {platform(`${st.id}b`, codes[1], x + 26, ym + 8, 48)}

                    <text x={x - 58} y={yTop - 8} fill={C.label} fontSize={11} fontFamily="Consolas, monospace">
                      {100 + sp.order * 5}
                    </text>
                    <text x={x + 42} y={yBot + 16} fill={C.label} fontSize={11} fontFamily="Consolas, monospace">
                      {sp.order}G
                    </text>

                    {/* 终端站：信号只放右侧，左侧留给库线几何 */}
                    {sigs.map((entry, si) => {
                      const side = sp.terminalWest ? 1 : si % 2 === 0 ? -1 : 1;
                      const slot = sp.terminalWest ? si : Math.floor(si / 2);
                      const slots = sp.terminalWest
                        ? Math.max(1, sigs.length)
                        : Math.max(1, Math.ceil(sigs.length / 2));
                      const t = (slot + 1) / (slots + 1);
                      const base = sp.terminalWest ? 55 : 36;
                      const sx = x + side * (base + t * Math.max(28, halfGap - 50));
                      const onDown = si % 2 === 0;
                      const open = openSigs.has(entry.sig.id);
                      const color = signalColor(entry.sig.aspect, open);
                      return lamp(
                        entry.sig.id,
                        sx,
                        onDown ? yTop : yBot,
                        onDown,
                        color,
                        selectedEntity?.type === 'signal' && selectedEntity.id === entry.sig.id,
                        (e) => {
                          e.stopPropagation();
                          onSelect({ type: 'signal', id: entry.sig.id });
                        },
                        si < 3 ? entry.sig.name?.slice(0, 4) : undefined,
                      );
                    })}

                    {sp.terminalWest && terminalWest(x, yTop, yBot)}
                    {sp.terminalEast && terminalEast(x, yTop, yBot)}
                  </g>
                );
              })}
            </g>
          );
        })}

        {trains.map((tr, ti) => {
          if (!Number.isFinite(tr.positionM) || stations.length === 0) return null;
          let best = stations[0];
          let bestD = Infinity;
          for (const s of stations) {
            const d = Math.abs(s.positionM - tr.positionM);
            if (d < bestD) {
              bestD = d;
              best = s;
            }
          }
          const idx = stations.findIndex((s) => s.id === best.id);
          const ri = idx < 7 ? 0 : 1;
          const localI = idx < 7 ? idx : idx - 7;
          const row = rows[ri];
          const L = layouts[ri];
          if (!row[localI]) return null;
          const leftExtra = ri === 0 ? 100 : 0;
          const x = xEven(localI, row.length, L.y0, L.y1, leftExtra);
          const y = tr.direction === 'DOWN' ? L.yBot : L.yTop;
          const ma = maMap[tr.trainId];
          return (
            <g
              key={tr.trainId}
              className="il-hit"
              onClick={(e) => {
                e.stopPropagation();
                onSelect({ type: 'train', id: tr.trainId });
              }}
            >
              <rect x={x - 26 + ti * 2} y={y - 12} width={52} height={24} fill={C.train} stroke="#FFF" strokeWidth={1.2} />
              <text x={x + ti * 2} y={y + 5} textAnchor="middle" fill="#000" fontSize={11} fontWeight={800}>
                {tr.trainId}
              </text>
              {ma && (
                <text x={x + ti * 2} y={y + 26} textAnchor="middle" fill={C.label} fontSize={9}>
                  {ma.maxSpeedKmh.toFixed(0)}km/h
                </text>
              )}
            </g>
          );
        })}

        <g transform="translate(24, 1060)">
          <circle cx={7} cy={0} r={6} fill={C.red} />
          <text x={18} y={4} fill={C.text} fontSize={12}>
            红灯（防护）
          </text>
          <circle cx={140} cy={0} r={6} fill={C.green} />
          <text x={152} y={4} fill={C.text} fontSize={12}>
            绿灯（开放）
          </text>
          <line x1={260} y1={0} x2={320} y2={0} stroke={C.rail} strokeWidth={3.2} />
          <text x={328} y={4} fill={C.text} fontSize={12}>
            股道
          </text>
          <rect x={390} y={-10} width={42} height={18} fill={C.platform} />
          <rect x={390} y={-10} width={42} height={4} fill={C.green} />
          <text x={440} y={4} fill={C.text} fontSize={12}>
            站台
          </text>
          <circle cx={510} cy={0} r={7} fill={C.bumper} />
          <text x={524} y={4} fill={C.text} fontSize={12}>
            车挡
          </text>
        </g>
      </svg>
    </div>
  );
}
