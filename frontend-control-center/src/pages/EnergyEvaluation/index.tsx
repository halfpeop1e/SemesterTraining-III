import { useState } from 'react';
import { Card, Button, Tabs, Row, Col, Statistic, Table, Tag, Empty, Space, Spin, Alert, Result, message } from 'antd';
import {
  ThunderboltOutlined, CheckCircleOutlined, ExperimentOutlined, ReloadOutlined,
} from '@ant-design/icons';
import type { EvaluationReport, SimulationLog } from '../../types';
import { generateReport } from '../../api/evaluation';
import { getSimulationLogs } from '../../api/dispatch';

/* ---- Helpers ---- */
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

const fmt = (v: number, d = 1) => v.toFixed(d);

/* ---- Metric Card ---- */
function Metric({
  label,
  value,
  unit,
  color,
}: {
  label: string;
  value: number;
  unit: string;
  color: string;
}) {
  return (
    <Card variant="borderless" className="rounded-xl!">
      <Statistic
        title={
          <span className="text-xs text-slate-400 font-medium">{label}</span>
        }
        value={fmt(value)}
        suffix={
          <span className="text-sm font-normal ml-0.5 text-slate-400">
            {unit}
          </span>
        }
        valueStyle={{ fontSize: 26, fontWeight: 700, color }}
      />
    </Card>
  );
}

/* ---- Page ---- */
export default function EnergyEvaluation() {
  const [loading, setLoading] = useState(false);
  const [report, setReport] = useState<EvaluationReport | null>(null);
  const [error, setError] = useState<string | null>(null);

  const handleRun = async () => {
    setLoading(true);
    setError(null);
    try {
      // 从仿真服务获取真实日志
      let simulationLogs: SimulationLog[] = [];
      try {
        simulationLogs = await getSimulationLogs();
      } catch {
        // 日志获取失败，使用空数组
      }
      if (simulationLogs.length === 0) {
        message.warning("未获取到仿真日志，请先在调度页面启动仿真");
      }

      const result = await generateReport(
        {
          scenarioName: "北京9号线仿真场景",
          simulationLogs,
          tractionEfficiency: 0.85,
          regenEfficiency: 0.65,
          powerSupplyThreshold: 2000,
        },
        {
          scenarioName: "北京9号线仿真场景",
          simulationLogs,
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

  const energy = (
    <div className="space-y-4">
      {report ? (
        <>
          <Row gutter={[12, 12]}>
            {[
              ["总牵引能耗", s?.["总牵引能耗(kWh)"] ?? 0, "kWh", "#38bdf8"],
              ["再生制动回收", s?.["总再生能量(kWh)"] ?? 0, "kWh", "#22c55e"],
              ["净能耗", s?.["总净能耗(kWh)"] ?? 0, "kWh", "#f59e0b"],
              ["峰值功率", s?.["峰值功率(kW)"] ?? 0, "kW", "#ef4444"],
            ].map(([l, v, u, c]) => (
              <Col xs={12} sm={6} key={l as string}>
                <Metric
                  label={l as string}
                  value={v as number}
                  unit={u as string}
                  color={c as string}
                />
              </Col>
            ))}
          </Row>

          <Card title="供电风险评估" variant="borderless" className="rounded-xl!">
            <div className="flex items-center gap-5">
              <div
                className="w-[72px] h-[72px] rounded-full flex items-center justify-center text-sm font-bold"
                style={{
                  background: `rgba(${report.powerRiskLevel === "safe" ? "34,197,94" : report.powerRiskLevel === "warning" ? "245,158,11" : "239,68,68"},0.06)`,
                  color: riskLevelColor[report.powerRiskLevel],
                  fontFamily: "'Orbitron', monospace",
                }}
              >
                {riskLevelLabel[report.powerRiskLevel]}
              </div>
              <div className="space-y-1.5 text-sm">
                <div className="flex gap-6 text-slate-400">
                  <span>
                    供电阈值{" "}
                    <b className="text-slate-200">
                      {report.powerSupplyThreshold > 0
                        ? `${report.powerSupplyThreshold} kW`
                        : "未设置"}
                    </b>
                  </span>
                </div>
                <div className="flex gap-6 text-slate-400">
                  <span>
                    峰值功率{" "}
                    <b className="text-slate-200">
                      {report.peakPowerResult?.maxPeakKw?.toFixed(0) ?? 0} kW
                    </b>
                  </span>
                  <span>
                    峰值时刻{" "}
                    <b className="text-slate-200">
                      {(
                        (report.peakPowerResult?.timeOfPeak ?? 0) / 1000
                      ).toFixed(1)}{" "}
                      s
                    </b>
                  </span>
                </div>
              </div>
            </div>
          </Card>
        </>
      ) : (
        <Card variant="borderless" className="rounded-xl!">
          <Empty description="点击「运行评估」开始分析" />
        </Card>
      )}
    </div>
  );

  const metrics = (
    <div className="space-y-4">
      {report ? (
        <>
          <Card title="停站误差分析" variant="borderless" className="rounded-xl!">
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
            <Card title="准点率评估" variant="borderless" className="rounded-xl!">
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
                      valueStyle={{
                        fontSize: 26,
                        fontWeight: 700,
                        color: c as string,
                      }}
                    />
                  </Col>
                ))}
              </Row>
            </Card>
          )}

          {report.comfort && (
            <Card title="舒适性评估" variant="borderless" className="rounded-xl!">
              <Row gutter={[16, 16]}>
                {[
                  [
                    "最大加速度",
                    +report.comfort.maxAcceleration.toFixed(2),
                    "m/s²",
                    "#38bdf8",
                  ],
                  [
                    "最大减速度",
                    +report.comfort.maxDeceleration.toFixed(2),
                    "m/s²",
                    "#f59e0b",
                  ],
                  [
                    "评分",
                    +report.comfort.comfortScore.toFixed(0),
                    "分",
                    "#22c55e",
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
                      valueStyle={{
                        fontSize: 26,
                        fontWeight: 700,
                        color: c as string,
                      }}
                    />
                  </Col>
                ))}
              </Row>
            </Card>
          )}

          <Card title="安全事件日志" variant="borderless" className="rounded-xl!">
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
                        TC{v.toString().padStart(2, "0")}
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
                <CheckCircleOutlined /> 无安全事件，系统运行正常
              </div>
            )}
          </Card>
        </>
      ) : (
        <Card variant="borderless" className="rounded-xl!">
          <Empty description="点击「运行评估」开始分析" />
        </Card>
      )}
    </div>
  );

  const reportTab = (
    <div className="space-y-4">
      {report ? (
        <Card variant="borderless" className="rounded-xl!">
          <div className="space-y-6">
            <h4 className="text-[15px] font-semibold text-slate-200 m-0">
              评估概要
            </h4>
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
              {[
                ["场景", report.scenarioName],
                [
                  "仿真时长",
                  `${(report.simulationDuration / 1000).toFixed(1)} s`,
                ],
                ["车辆数", `${report.energyRecords?.length ?? 0}`],
                ["站点数", `${report.stopErrors?.length ?? 0} 站`],
                ["供电风险", riskLevelLabel[report.powerRiskLevel]],
                [
                  "准点率",
                  report.punctuality
                    ? `${(report.punctuality.punctualityRate * 100).toFixed(1)}%`
                    : "-",
                ],
                [
                  "舒适性",
                  report.comfort
                    ? `${report.comfort.comfortScore.toFixed(0)} 分`
                    : "-",
                ],
                ["安全事件", `${report.safetyEvents?.length ?? 0} 起`],
              ].map(([k, v]) => (
                <div
                  key={k}
                  className="flex items-center justify-between p-3.5 rounded-xl text-sm"
                  style={{
                    background: "rgba(15,23,42,0.5)",
                    border: "1px solid rgba(148,163,184,0.06)",
                  }}
                >
                  <span className="text-slate-500">{k}</span>
                  <span className="text-slate-200 font-semibold tabular-nums">
                    {v}
                  </span>
                </div>
              ))}
            </div>
            {report.energyRecords?.length ? (
              <>
                <h4 className="text-[15px] font-semibold text-slate-200 m-0">
                  各列车能耗明细
                </h4>
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
                          TC{v.toString().padStart(2, "0")}
                        </span>
                      ),
                    },
                    {
                      title: "牵引能耗",
                      dataIndex: "totalTractionEnergyKwh",
                      render: (v: number) => (
                        <span className="text-blue-400 font-mono text-xs font-semibold">
                          {v.toFixed(2)} kWh
                        </span>
                      ),
                    },
                    {
                      title: "再生制动",
                      dataIndex: "totalRegenEnergyKwh",
                      render: (v: number) => (
                        <span className="text-emerald-400 font-mono text-xs font-semibold">
                          {v.toFixed(2)} kWh
                        </span>
                      ),
                    },
                    {
                      title: "净能耗",
                      dataIndex: "netEnergyKwh",
                      render: (v: number) => (
                        <span className="text-amber-400 font-mono text-xs font-semibold">
                          {v.toFixed(2)} kWh
                        </span>
                      ),
                    },
                  ]}
                />
              </>
            ) : null}
          </div>
        </Card>
      ) : (
        <Card variant="borderless" className="rounded-xl!">
          <Empty description="点击「运行评估」开始分析" />
        </Card>
      )}
    </div>
  );

  if (loading && !report) {
    return (
      <div className="h-full flex items-center justify-center" style={{ minHeight: 400 }}>
        <Spin tip="正在运行评估分析..." size="large" />
      </div>
    );
  }

  if (error && !report) {
    return (
      <div className="p-6">
        <Result
          status="warning"
          title="评估服务不可用"
          subTitle={error}
          extra={[
            <Button key="retry" type="primary" icon={<ReloadOutlined />} onClick={handleRun}>
              重试
            </Button>,
          ]}
        />
      </div>
    );
  }

  return (
    <div className="p-6 space-y-5">
      <div className="flex items-start justify-between gap-4 flex-wrap">
        <div>
          <h2 className="text-[17px] font-semibold text-slate-100 m-0">
            供电能源评估
          </h2>
          <p className="text-sm text-slate-500 mt-1 m-0">
            牵引能耗 · 再生制动 · 峰值功率 · 停站误差 · 准点率 · 舒适性 ·
            安全事件
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

      {error && report && (
        <Alert
          type="warning"
          message="部分评估数据可能过期"
          description={error}
          showIcon
          closable
          className="!rounded-xl"
        />
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
