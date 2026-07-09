import { useState, useEffect, useRef, useCallback } from "react";
import {
  Card,
  Button,
  Tabs,
  Row,
  Col,
  Statistic,
  Table,
  Tag,
  Empty,
  Progress,
} from "antd";
import {
  CheckCircleOutlined,
  ExperimentOutlined,
  SyncOutlined,
} from "@ant-design/icons";
import {
  BarChart,
  Bar,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip as ReTooltip,
  Legend,
  PieChart,
  Pie,
  Cell,
  ResponsiveContainer,
} from "recharts";
import type { EvaluationReport, SimulationLog } from "../../types";
import type { EnergyOptimizationInfo } from "../../types/dispatch";
import { generateReport } from "../../api/evaluation";
import { getSnapshot } from "../../api/dispatch";
import { getSimulationLogs } from "../../api/dispatch";

const riskLevelLabel: Record<string, string> = {
  safe: "安全",
  warning: "警告",
  danger: "危险",
};
const riskLevelColor: Record<string, string> = {
  safe: "#22c55e",
  warning: "#f59e0b",
  danger: "#ef4444",
};
const stopStatusLabel: Record<string, string> = {
  in_window: "窗内",
  over: "冲标",
  under: "欠标",
};
const stopStatusColor: Record<string, string> = {
  in_window: "green",
  over: "red",
  under: "orange",
};
const eventTypeLabel: Record<string, string> = {
  over_speed: "超速",
  eb_triggered: "紧急制动",
  brake_insufficient: "制动不足",
  degraded_mode: "降级模式",
};
const eventTypeColor: Record<string, string> = {
  over_speed: "red",
  eb_triggered: "red",
  brake_insufficient: "orange",
  degraded_mode: "orange",
};
const CHART_COLORS = {
  traction: "#38bdf8",
  regen: "#22c55e",
  net: "#f59e0b",
  brake: "#ef4444",
  coast: "#94a3b8",
  aux: "#f97316",
  cruise: "#06b6d4",
};
const fmt = (v: number | undefined | null, d = 1) => (v ?? 0).toFixed(d);
const fmtTime = (s: number | undefined | null) => {
  const t = s ?? 0;
  const m = Math.floor(t / 60),
    sec = Math.floor(t % 60);
  return `${m}:${String(sec).padStart(2, "0")}`;
};

function computeOperationRatio(logs: SimulationLog[]) {
  let traction = 0,
    brake = 0,
    coast = 0;
  for (const log of logs) {
    if (log.tractiveBrakeCmd === "traction") traction++;
    else if (log.tractiveBrakeCmd === "brake") brake++;
    else coast++;
  }
  const total = traction + brake + coast || 1;
  return [
    {
      name: "牵引",
      value: +((traction / total) * 100).toFixed(1),
      color: CHART_COLORS.traction,
    },
    {
      name: "制动",
      value: +((brake / total) * 100).toFixed(1),
      color: CHART_COLORS.brake,
    },
    {
      name: "惰行/停站",
      value: +((coast / total) * 100).toFixed(1),
      color: CHART_COLORS.coast,
    },
  ];
}

function LiveGauge({
  label,
  value,
  unit,
  color,
  max,
  sub,
}: {
  label: string;
  value: number;
  unit: string;
  color: string;
  max?: number;
  sub?: string;
}) {
  const pct = max && max > 0 ? Math.min(100, (value / max) * 100) : undefined;
  return (
    <div className="p-3 rounded-xl text-center bg-[rgba(15,23,42,0.5)] border border-[rgba(148,163,184,0.06)]">
      <div className="text-[10px] text-slate-500 mb-0.5 truncate">{label}</div>
      <div
        className="text-lg font-bold tabular-nums"
        style={{ color, fontFamily: "'Orbitron', monospace" }}
      >
        {fmt(value, value < 10 ? 2 : 1)}
        <span className="text-xs font-normal ml-0.5 text-slate-400">
          {unit}
        </span>
      </div>
      {pct !== undefined && (
        <Progress
          percent={+pct.toFixed(1)}
          showInfo={false}
          strokeColor={color}
          railColor="rgba(148,163,184,0.1)"
          size="small"
        />
      )}
      {sub && <div className="text-[10px] text-slate-500 mt-0.5">{sub}</div>}
    </div>
  );
}

function PieTooltip({
  active,
  payload,
}: {
  active?: boolean;
  payload?: Array<{ name: string; value: number; payload: { color: string } }>;
}) {
  if (!active || !payload?.length) return null;
  const d = payload[0];
  return (
    <div className="rounded-lg px-3 py-2 text-xs bg-[rgba(15,23,42,0.95)] border border-[rgba(148,163,184,0.15)]">
      <span style={{ color: d.payload.color }}>{d.name}</span>
      <span className="text-slate-200 font-mono ml-2">{d.value}%</span>
    </div>
  );
}

function ChartTooltip({
  active,
  payload,
  label,
}: {
  active?: boolean;
  payload?: Array<{ color: string; name: string; value: number }>;
  label?: string;
}) {
  if (!active || !payload?.length) return null;
  return (
    <div className="rounded-lg px-3 py-2 text-xs bg-[rgba(15,23,42,0.95)] border border-[rgba(148,163,184,0.15)]">
      <p className="text-slate-400 mb-1">{label}</p>
      {payload.map((p, i) => (
        <p key={i} style={{ color: p.color }}>
          {p.name}:{" "}
          <span className="font-mono">
            {typeof p.value === "number" ? p.value.toFixed(1) : p.value}
          </span>
        </p>
      ))}
    </div>
  );
}

export default function EnergyEvaluation() {
  const [loading, setLoading] = useState(false);
  const [report, setReport] = useState<EvaluationReport | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [rawLogs, setRawLogs] = useState<SimulationLog[]>([]);
  const [liveEnergy, setLiveEnergy] = useState<EnergyOptimizationInfo | null>(
    null,
  );
  const [isSimRunning, setIsSimRunning] = useState(false);
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const pollSnapshot = useCallback(async () => {
    try {
      const snap = await getSnapshot();
      if (snap && snap.activeTrains > 0) {
        setIsSimRunning(true);
        if (snap.energyOptimization) setLiveEnergy(snap.energyOptimization);
      } else setIsSimRunning(false);
    } catch {
      setIsSimRunning(false);
    }
  }, []);

  useEffect(() => {
    pollRef.current = setInterval(pollSnapshot, 2000);
    pollSnapshot();
    return () => {
      if (pollRef.current) clearInterval(pollRef.current);
    };
  }, [pollSnapshot]);

  const handleRun = async () => {
    setLoading(true);
    setError(null);
    try {
      let logs = await getSimulationLogs().catch(() => [] as SimulationLog[]);
      if (logs.length > 5000) logs = logs.slice(-5000);
      setRawLogs(logs);
      const result = await generateReport(
        {
          scenarioName: "北京9号线仿真场景",
          simulationLogs: logs,
          tractionEfficiency: 0.85,
          regenEfficiency: 0.65,
          powerSupplyThreshold: 2000,
        },
        {
          scenarioName: "北京9号线仿真场景",
          simulationLogs: logs,
          stationPositions: {
            1: 313,
            2: 1660,
            3: 2448,
            4: 3429,
            5: 5014,
            6: 6339,
            7: 8118,
            8: 9429,
            9: 10598,
            10: 11996,
            11: 13906,
            12: 14954,
            13: 16048,
          },
          stationNames: {
            1: "GGZ",
            2: "FSP",
            3: "KYL",
            4: "FTN",
            5: "FTD",
            6: "QLZ",
            7: "LLQ",
            8: "LLE",
            9: "BWR",
            10: "JBG",
            11: "BDZ",
            12: "BQS",
            13: "GTG",
          },
          plannedArrivals: {
            1: 120,
            2: 280,
            3: 450,
            4: 620,
            5: 800,
            6: 980,
            7: 1160,
            8: 1400,
            9: 1580,
            10: 1800,
            11: 2020,
            12: 2180,
            13: 2400,
          },
          stationDirections: Object.fromEntries(
            Array.from({ length: 13 }, (_, i) => [i + 1, "down"]),
          ),
          speedLimits: {},
          stopWindowTolerance: 0.5,
          punctualityTolerance: 30,
        },
      );
      setReport(result);
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : "评估请求失败");
    } finally {
      setLoading(false);
    }
  };

  const s = report?.summary;
  const pieData =
    rawLogs.length > 0
      ? computeOperationRatio(rawLogs)
      : computeOperationRatio([]);
  const barData = (report?.energyRecords ?? []).map((r) => ({
    name: `TC${String(r.trainId).padStart(2, "0")}`,
    牵引能耗: +r.totalTractionEnergyKwh.toFixed(1),
    再生制动: +r.totalRegenEnergyKwh.toFixed(1),
    净能耗: +r.netEnergyKwh.toFixed(2),
  }));

  const liveE = liveEnergy;
  const G = LiveGauge;

  const liveDashboard = (
    <Card
      variant="borderless"
      className="rounded-xl!"
      title={
        <div className="flex items-center gap-2">
          <SyncOutlined
            spin={isSimRunning}
            style={{ color: isSimRunning ? "#22c55e" : "#64748b" }}
          />
          <span>实时能耗仪表</span>
          {isSimRunning && liveE && (
            <Tag color="green" className="ml-2">
              运行中 {fmtTime(liveE.simulationTimeSeconds)}
            </Tag>
          )}
          {!isSimRunning && <Tag color="default">仿真未启动</Tag>}
        </div>
      }
    >
      {liveE ? (
        <div className="space-y-3">
          {/* 8项实时指标 */}
          <Row gutter={[8, 8]}>
            <Col xs={12} sm={6} md={3}>
              <G
                label="累计牵引"
                value={liveE.totalTractionEnergyKwh}
                unit="kWh"
                color={CHART_COLORS.traction}
              />
            </Col>
            <Col xs={12} sm={6} md={3}>
              <G
                label="再生制动"
                value={liveE.totalRegenEnergyKwh}
                unit="kWh"
                color={CHART_COLORS.regen}
              />
            </Col>
            <Col xs={12} sm={6} md={3}>
              <G
                label="净能耗"
                value={liveE.netEnergyKwh}
                unit="kWh"
                color={CHART_COLORS.net}
              />
            </Col>
            <Col xs={12} sm={6} md={3}>
              <G
                label="峰值功率"
                value={liveE.currentPeakKw}
                unit="kW"
                color={CHART_COLORS.brake}
                max={liveE.powerSupplyThresholdKw}
                sub={`阈值${liveE.powerSupplyThresholdKw.toFixed(0)}kW`}
              />
            </Col>
            <Col xs={12} sm={6} md={3}>
              <G
                label="辅助能耗"
                value={liveE.auxiliaryEnergyKwh}
                unit="kWh"
                color={CHART_COLORS.aux}
              />
            </Col>
            <Col xs={12} sm={6} md={3}>
              <G
                label="巡航能耗"
                value={liveE.cruisingEnergyKwh}
                unit="kWh"
                color={CHART_COLORS.cruise}
              />
            </Col>
            <Col xs={12} sm={6} md={3}>
              <G
                label="牵引列车"
                value={liveE.tractionCount}
                unit={`/${liveE.maxTractionCount}`}
                color="#e2e8f0"
              />
            </Col>
            <Col xs={12} sm={6} md={3}>
              <G
                label="可回收能"
                value={liveE.totalRecoverableEnergyKw}
                unit="kW"
                color="#a78bfa"
              />
            </Col>
          </Row>
          {/* 风险 + 策略 */}
          <Row gutter={[8, 8]}>
            <Col xs={24} sm={8}>
              <div className="p-3 rounded-xl flex items-center gap-3 bg-[rgba(15,23,42,0.5)] border border-[rgba(148,163,184,0.06)]">
                <div
                  className="w-12 h-12 rounded-full flex items-center justify-center text-xs font-bold shrink-0"
                  style={{
                    background: `rgba(${liveE.peakRiskLevel === "safe" ? "34,197,94" : liveE.peakRiskLevel === "warning" ? "245,158,11" : "239,68,68"},0.08)`,
                    color: riskLevelColor[liveE.peakRiskLevel],
                    fontFamily: "'Orbitron', monospace",
                  }}
                >
                  {riskLevelLabel[liveE.peakRiskLevel]}
                </div>
                <div className="text-xs text-slate-400 space-y-0.5">
                  <div>供电风险</div>
                  <div className="text-slate-200 font-semibold">
                    {riskLevelLabel[liveE.peakRiskLevel]}
                  </div>
                </div>
              </div>
            </Col>
            <Col xs={12} sm={8}>
              <div className="p-3 rounded-xl bg-[rgba(15,23,42,0.5)] border border-[rgba(148,163,184,0.06)]">
                <div className="text-xs text-slate-500">再生协同</div>
                <div
                  className="text-lg font-bold text-emerald-400"
                  style={{ fontFamily: "'Orbitron', monospace" }}
                >
                  {liveE.regenCoordinationCount}
                </div>
              </div>
            </Col>
            <Col xs={12} sm={8}>
              <div className="p-3 rounded-xl bg-[rgba(15,23,42,0.5)] border border-[rgba(148,163,184,0.06)]">
                <div className="text-xs text-slate-500">惰行窗口</div>
                <div
                  className="text-lg font-bold text-blue-400"
                  style={{ fontFamily: "'Orbitron', monospace" }}
                >
                  {liveE.coastingOpportunityCount}
                </div>
              </div>
            </Col>
          </Row>
          {liveE.recommendations?.length > 0 && (
            <div className="p-3 rounded-xl bg-[rgba(245,158,11,0.06)] border border-[rgba(245,158,11,0.12)]">
              <div className="text-xs text-amber-400 font-medium mb-1">
                策略建议
              </div>
              {liveE.recommendations.map((r, i) => (
                <div
                  key={i}
                  className="text-xs text-slate-400 flex items-center gap-1.5"
                >
                  <span className="w-1 h-1 rounded-full bg-amber-400 shrink-0" />
                  {r}
                </div>
              ))}
            </div>
          )}
        </div>
      ) : (
        <Empty
          description={
            isSimRunning
              ? "正在获取实时数据..."
              : "请先启动仿真，仪表将自动显示实时能耗数据"
          }
        />
      )}
    </Card>
  );

  const energy = (
    <div className="space-y-4">
      {liveDashboard}
      {report ? (
        <>
          <Row gutter={[12, 12]}>
            {[
              [
                "总牵引能耗",
                s?.["总牵引能耗(kWh)"] ?? 0,
                "kWh",
                CHART_COLORS.traction,
              ],
              [
                "再生制动回收",
                s?.["总再生能量(kWh)"] ?? 0,
                "kWh",
                CHART_COLORS.regen,
              ],
              ["净能耗", s?.["总净能耗(kWh)"] ?? 0, "kWh", CHART_COLORS.net],
              ["峰值功率", s?.["峰值功率(kW)"] ?? 0, "kW", CHART_COLORS.brake],
            ].map(([l, v, u, c]) => (
              <Col xs={12} sm={6} key={l as string}>
                <Card variant="borderless" className="rounded-xl!">
                  <Statistic
                    title={
                      <span className="text-xs text-slate-400 font-medium">
                        {l}
                      </span>
                    }
                    value={fmt(v as number)}
                    suffix={
                      <span className="text-sm font-normal ml-0.5 text-slate-400">
                        {u}
                      </span>
                    }
                    styles={{
                      content: {
                        fontSize: 26,
                        fontWeight: 700,
                        color: c as string,
                      },
                    }}
                  />
                </Card>
              </Col>
            ))}
          </Row>
          <Row gutter={[12, 12]}>
            <Col xs={24} lg={12}>
              <Card
                title="工况占比"
                variant="borderless"
                className="rounded-xl!"
              >
                <ResponsiveContainer width="100%" height={220}>
                  <PieChart>
                    <Pie
                      data={pieData}
                      cx="50%"
                      cy="50%"
                      innerRadius={45}
                      outerRadius={80}
                      paddingAngle={3}
                      dataKey="value"
                      stroke="rgba(15,23,42,0.8)"
                      strokeWidth={2}
                    >
                      {pieData.map((e, i) => (
                        <Cell key={i} fill={e.color} />
                      ))}
                    </Pie>
                    <ReTooltip content={<PieTooltip />} />
                  </PieChart>
                </ResponsiveContainer>
                <div className="flex justify-center gap-4">
                  {pieData.map((d) => (
                    <div
                      key={d.name}
                      className="flex items-center gap-1 text-xs text-slate-400"
                    >
                      <span
                        className="w-2 h-2 rounded-sm"
                        style={{ backgroundColor: d.color }}
                      />
                      {d.name} {d.value}%
                    </div>
                  ))}
                </div>
              </Card>
            </Col>
            <Col xs={24} lg={12}>
              <Card
                title="能耗对比"
                variant="borderless"
                className="rounded-xl!"
              >
                <ResponsiveContainer width="100%" height={220}>
                  <BarChart
                    data={barData}
                    margin={{ top: 5, right: 5, left: -15, bottom: 5 }}
                  >
                    <CartesianGrid
                      strokeDasharray="3 3"
                      stroke="rgba(148,163,184,0.08)"
                    />
                    <XAxis
                      dataKey="name"
                      tick={{ fill: "#94a3b8", fontSize: 11 }}
                      axisLine={false}
                      tickLine={false}
                    />
                    <YAxis
                      tick={{ fill: "#64748b", fontSize: 10 }}
                      axisLine={false}
                      tickLine={false}
                      unit="kWh"
                    />
                    <ReTooltip content={<ChartTooltip />} />
                    <Legend wrapperStyle={{ fontSize: 11 }} />
                    <Bar
                      dataKey="牵引能耗"
                      fill={CHART_COLORS.traction}
                      radius={[3, 3, 0, 0]}
                    />
                    <Bar
                      dataKey="再生制动"
                      fill={CHART_COLORS.regen}
                      radius={[3, 3, 0, 0]}
                    />
                  </BarChart>
                </ResponsiveContainer>
              </Card>
            </Col>
          </Row>
        </>
      ) : (
        <Card variant="borderless" className="rounded-xl!">
          <Empty description="点击「运行评估」生成详细报告和图表" />
        </Card>
      )}
    </div>
  );

  const metrics = (
    <div className="space-y-4">
      {report ? (
        <>
          <Card
            title="停站误差分析"
            variant="borderless"
            className="rounded-xl!"
          >
            <Table
              dataSource={(report.stopErrors || []).map((e, i) => ({
                ...e,
                key: i,
              }))}
              pagination={false}
              size="small"
              columns={[
                {
                  title: "站名",
                  dataIndex: "stationName",
                  width: 80,
                  render: (t: string) => (
                    <span className="text-slate-200 font-semibold">{t}</span>
                  ),
                },
                {
                  title: "目标",
                  dataIndex: "targetPosition",
                  render: (v: number) => (
                    <span className="text-slate-400 font-mono text-xs">
                      {fmt(v)} m
                    </span>
                  ),
                },
                {
                  title: "实际",
                  dataIndex: "actualPosition",
                  render: (v: number) => (
                    <span className="text-slate-400 font-mono text-xs">
                      {v.toFixed(2)} m
                    </span>
                  ),
                },
                {
                  title: "误差",
                  dataIndex: "error",
                  render: (v: number) => (
                    <span className="text-blue-400 font-mono text-xs font-semibold">
                      {v > 0 ? "+" : ""}
                      {v.toFixed(2)} m
                    </span>
                  ),
                },
                {
                  title: "状态",
                  dataIndex: "status",
                  width: 80,
                  render: (s: string) => (
                    <Tag color={stopStatusColor[s]}>
                      {stopStatusLabel[s] || s}
                    </Tag>
                  ),
                },
              ]}
            />
          </Card>
          {report.punctuality && (
            <Card title="准点率" variant="borderless" className="rounded-xl!">
              <Row gutter={[16, 16]}>
                {[
                  [
                    "准点率",
                    +(report.punctuality.punctualityRate * 100).toFixed(1),
                    "%",
                    report.punctuality.punctualityRate >= 0.9
                      ? "#22c55e"
                      : "#f59e0b",
                  ],
                  [
                    "平均延误",
                    +report.punctuality.avgDelay.toFixed(1),
                    "s",
                    "#e2e8f0",
                  ],
                  [
                    "最大延误",
                    +report.punctuality.maxDelay.toFixed(1),
                    "s",
                    "#e2e8f0",
                  ],
                ].map(([l, v, u, c]) => (
                  <Col span={8} key={l as string}>
                    <Statistic
                      title={
                        <span className="text-xs text-slate-400 font-medium">
                          {l}
                        </span>
                      }
                      value={v as number}
                      suffix={
                        <span className="text-sm font-normal ml-0.5 text-slate-400">
                          {u}
                        </span>
                      }
                      styles={{
                        content: {
                          fontSize: 26,
                          fontWeight: 700,
                          color: c as string,
                        },
                      }}
                    />
                  </Col>
                ))}
              </Row>
            </Card>
          )}
          {report.comfort && (
            <Card title="舒适性" variant="borderless" className="rounded-xl!">
              <Row gutter={[16, 16]}>
                {[
                  [
                    "最大加速度",
                    +report.comfort.maxAcceleration.toFixed(2),
                    "m/s²",
                    CHART_COLORS.traction,
                  ],
                  [
                    "最大减速度",
                    +report.comfort.maxDeceleration.toFixed(2),
                    "m/s²",
                    CHART_COLORS.brake,
                  ],
                  [
                    "评分",
                    +report.comfort.comfortScore.toFixed(0),
                    "分",
                    CHART_COLORS.regen,
                  ],
                ].map(([l, v, u, c]) => (
                  <Col span={8} key={l as string}>
                    <Statistic
                      title={
                        <span className="text-xs text-slate-400 font-medium">
                          {l}
                        </span>
                      }
                      value={v as number}
                      suffix={
                        <span className="text-sm font-normal ml-0.5 text-slate-400">
                          {u}
                        </span>
                      }
                      styles={{
                        content: {
                          fontSize: 26,
                          fontWeight: 700,
                          color: c as string,
                        },
                      }}
                    />
                  </Col>
                ))}
              </Row>
            </Card>
          )}
          <Card title="安全事件" variant="borderless" className="rounded-xl!">
            {(report.safetyEvents || []).length > 0 ? (
              <Table
                dataSource={(report.safetyEvents || []).map((e, i) => ({
                  ...e,
                  key: i,
                }))}
                pagination={false}
                size="small"
                columns={[
                  {
                    title: "时间",
                    dataIndex: "timestamp",
                    width: 80,
                    render: (v: number) => (
                      <span className="text-slate-400 font-mono text-xs">
                        {(v / 1000).toFixed(1)}s
                      </span>
                    ),
                  },
                  {
                    title: "列车",
                    dataIndex: "trainId",
                    width: 70,
                    render: (v: number) => (
                      <span className="text-slate-200">
                        TC{String(v).padStart(2, "0")}
                      </span>
                    ),
                  },
                  {
                    title: "类型",
                    dataIndex: "eventType",
                    width: 90,
                    render: (t: string) => (
                      <Tag color={eventTypeColor[t]}>
                        {eventTypeLabel[t] || t}
                      </Tag>
                    ),
                  },
                  {
                    title: "描述",
                    dataIndex: "description",
                    render: (t: string) => (
                      <span className="text-slate-400 text-xs">{t}</span>
                    ),
                  },
                ]}
              />
            ) : (
              <div className="flex items-center justify-center gap-2 py-8 text-emerald-400">
                <CheckCircleOutlined /> 无安全事件
              </div>
            )}
          </Card>
        </>
      ) : (
        <Card variant="borderless" className="rounded-xl!">
          <Empty description="点击「运行评估」查看详细指标" />
        </Card>
      )}
    </div>
  );

  const reportTab = (
    <div className="space-y-4">
      {report ? (
        <>
          <Card variant="borderless" className="rounded-xl!">
            <div className="space-y-6">
              <h4 className="text-[15px] font-semibold text-slate-200 m-0">
                评估概要
              </h4>
              <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
                <div
                  key="scenario"
                  className="flex items-center justify-between p-3.5 rounded-xl text-sm bg-[rgba(15,23,42,0.5)] border border-[rgba(148,163,184,0.06)]"
                >
                  <span className="text-slate-500">场景</span>
                  <span className="text-slate-200 font-semibold tabular-nums">
                    {report.scenarioName}
                  </span>
                </div>
                <div
                  key="duration"
                  className="flex items-center justify-between p-3.5 rounded-xl text-sm bg-[rgba(15,23,42,0.5)] border border-[rgba(148,163,184,0.06)]"
                >
                  <span className="text-slate-500">仿真时长</span>
                  <span className="text-slate-200 font-semibold tabular-nums">
                    {(report.simulationDuration / 1000).toFixed(1)} s
                  </span>
                </div>
                <div
                  key="trains"
                  className="flex items-center justify-between p-3.5 rounded-xl text-sm bg-[rgba(15,23,42,0.5)] border border-[rgba(148,163,184,0.06)]"
                >
                  <span className="text-slate-500">车辆数</span>
                  <span className="text-slate-200 font-semibold tabular-nums">
                    {report.energyRecords?.length ?? 0}
                  </span>
                </div>
                <div
                  key="stations"
                  className="flex items-center justify-between p-3.5 rounded-xl text-sm bg-[rgba(15,23,42,0.5)] border border-[rgba(148,163,184,0.06)]"
                >
                  <span className="text-slate-500">站点数</span>
                  <span className="text-slate-200 font-semibold tabular-nums">
                    {report.stopErrors?.length ?? 0} 站
                  </span>
                </div>
                <div
                  key="power-risk"
                  className="flex items-center justify-between p-3.5 rounded-xl text-sm bg-[rgba(15,23,42,0.5)] border border-[rgba(148,163,184,0.06)]"
                >
                  <span className="text-slate-500">供电风险</span>
                  <span className="text-slate-200 font-semibold tabular-nums">
                    {riskLevelLabel[report.powerRiskLevel]}
                  </span>
                </div>
                <div
                  key="punctuality"
                  className="flex items-center justify-between p-3.5 rounded-xl text-sm bg-[rgba(15,23,42,0.5)] border border-[rgba(148,163,184,0.06)]"
                >
                  <span className="text-slate-500">准点率</span>
                  <span className="text-slate-200 font-semibold tabular-nums">
                    {report.punctuality
                      ? fmt(report.punctuality.punctualityRate * 100, 1) + "%"
                      : "-"}
                  </span>
                </div>
                <div
                  key="comfort"
                  className="flex items-center justify-between p-3.5 rounded-xl text-sm bg-[rgba(15,23,42,0.5)] border border-[rgba(148,163,184,0.06)]"
                >
                  <span className="text-slate-500">舒适性</span>
                  <span className="text-slate-200 font-semibold tabular-nums">
                    {report.comfort
                      ? report.comfort.comfortScore.toFixed(0) + " 分"
                      : "-"}
                  </span>
                </div>
                <div
                  key="safety"
                  className="flex items-center justify-between p-3.5 rounded-xl text-sm bg-[rgba(15,23,42,0.5)] border border-[rgba(148,163,184,0.06)]"
                >
                  <span className="text-slate-500">安全事件</span>
                  <span className="text-slate-200 font-semibold tabular-nums">
                    {report.safetyEvents?.length ?? 0} 起
                  </span>
                </div>
              </div>
              {report.summary && (
                <>
                  <h4 className="text-[15px] font-semibold text-slate-200 m-0">
                    汇总指标
                  </h4>
                  <Row gutter={[12, 12]}>
                    {Object.entries(report.summary).map(([k, v]) => (
                      <Col xs={12} sm={8} md={6} key={k}>
                        <div className="p-3.5 rounded-xl text-sm bg-[rgba(15,23,42,0.5)] border border-[rgba(148,163,184,0.06)]">
                          <div className="text-slate-500 text-xs mb-1">{k}</div>
                          <div className="text-slate-200 font-semibold text-lg tabular-nums">
                            {fmt(v, 2)}
                          </div>
                        </div>
                      </Col>
                    ))}
                  </Row>
                </>
              )}
            </div>
          </Card>
          {barData.length > 0 && (
            <Card
              title="能耗明细（图表）"
              variant="borderless"
              className="rounded-xl!"
            >
              <ResponsiveContainer width="100%" height={300}>
                <BarChart
                  data={barData}
                  margin={{ top: 5, right: 10, left: -10, bottom: 5 }}
                >
                  <CartesianGrid
                    strokeDasharray="3 3"
                    stroke="rgba(148,163,184,0.08)"
                  />
                  <XAxis
                    dataKey="name"
                    tick={{ fill: "#94a3b8", fontSize: 11 }}
                    axisLine={false}
                    tickLine={false}
                  />
                  <YAxis
                    tick={{ fill: "#64748b", fontSize: 10 }}
                    axisLine={false}
                    tickLine={false}
                    unit="kWh"
                  />
                  <ReTooltip content={<ChartTooltip />} />
                  <Legend wrapperStyle={{ fontSize: 11 }} />
                  <Bar
                    dataKey="牵引能耗"
                    fill={CHART_COLORS.traction}
                    radius={[3, 3, 0, 0]}
                  />
                  <Bar
                    dataKey="再生制动"
                    fill={CHART_COLORS.regen}
                    radius={[3, 3, 0, 0]}
                  />
                  <Bar
                    dataKey="净能耗"
                    fill={CHART_COLORS.net}
                    radius={[3, 3, 0, 0]}
                  />
                </BarChart>
              </ResponsiveContainer>
            </Card>
          )}
          {report.energyRecords?.length ? (
            <Card
              title="能耗明细（数据表）"
              variant="borderless"
              className="rounded-xl!"
            >
              <Table
                dataSource={report.energyRecords.map((r, i) => ({
                  ...r,
                  key: i,
                }))}
                pagination={false}
                size="small"
                columns={[
                  {
                    title: "列车",
                    dataIndex: "trainId",
                    render: (v: number) => (
                      <span className="text-slate-200 font-medium">
                        TC{String(v).padStart(2, "0")}
                      </span>
                    ),
                  },
                  {
                    title: "牵引",
                    dataIndex: "totalTractionEnergyKwh",
                    render: (v: number) => (
                      <span className="text-blue-400 font-mono text-xs font-semibold">
                        {fmt(v, 2)} kWh
                      </span>
                    ),
                  },
                  {
                    title: "再生",
                    dataIndex: "totalRegenEnergyKwh",
                    render: (v: number) => (
                      <span className="text-emerald-400 font-mono text-xs font-semibold">
                        {fmt(v, 2)} kWh
                      </span>
                    ),
                  },
                  {
                    title: "净能耗",
                    dataIndex: "netEnergyKwh",
                    render: (v: number) => (
                      <span className="text-amber-400 font-mono text-xs font-semibold">
                        {fmt(v, 2)} kWh
                      </span>
                    ),
                  },
                  {
                    title: "峰值功率",
                    dataIndex: "maxTractionPowerKw",
                    render: (v: number) => (
                      <span className="text-red-400 font-mono text-xs">
                        {v.toFixed(0)} kW
                      </span>
                    ),
                  },
                  {
                    title: "平均功率",
                    dataIndex: "avgTractionPowerKw",
                    render: (v: number) => (
                      <span className="text-slate-400 font-mono text-xs">
                        {fmt(v)} kW
                      </span>
                    ),
                  },
                ]}
              />
            </Card>
          ) : null}
        </>
      ) : (
        <Card variant="borderless" className="rounded-xl!">
          <Empty description="点击「运行评估」查看综合报告" />
        </Card>
      )}
    </div>
  );

  return (
    <div className="p-6 space-y-5">
      <div className="flex items-start justify-between gap-4 flex-wrap">
        <div>
          <h2 className="text-[17px] font-semibold text-slate-100 m-0">
            供电能源评估
          </h2>
          <p className="text-sm text-slate-500 mt-1 m-0">
            TB/T 1407.2 物理模型 · 实时仪表 · 牵引·再生·辅助·巡航能耗 · 坡道阻力
            · 电机效率曲线
          </p>
        </div>
        <Button
          type="primary"
          loading={loading}
          onClick={handleRun}
          icon={<ExperimentOutlined />}
          className="h-9! px-5! rounded-lg!"
        >
          {loading ? "评估中..." : "运行评估"}
        </Button>
      </div>
      {error && (
        <div className="text-red-400 text-sm p-3.5 rounded-xl flex items-center gap-2 bg-[rgba(239,68,68,0.06)] border border-[rgba(239,68,68,0.12)]">
          <span className="w-1.5 h-1.5 rounded-full bg-red-400 shrink-0" />
          {error}
        </div>
      )}
      <Tabs
        defaultActiveKey="energy"
        tabBarStyle={{
          marginBottom: 0,
          borderBottom: "1px solid rgba(148,163,184,0.06)",
        }}
        items={[
          { key: "energy", label: "能耗看板", children: energy },
          { key: "metrics", label: "评估指标", children: metrics },
          { key: "report", label: "综合报告", children: reportTab },
        ]}
      />
    </div>
  );
}
