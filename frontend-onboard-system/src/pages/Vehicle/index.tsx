// 郭逸晨车载模块（成员三）—— Vehicle 页面
//
// 阶段 1B：真实调用后端 POST /api/vehicle/simulation/run，拿到完整仿真结果后
// 按时间顺序播放 states，让用户看到列车从起点 0m 向目标停车点 1200m 运行的过程。
// 页面展示的所有运行数据均来自后端响应，不使用前端写死数组。
//
// 阶段 1.5（本轮）：车载可视化增强，纯前端呈现层改造。把原来的"静态灰底进度条 +
// 圆点 + 红线"改成"列车固定、轨道向列车滚动逼近"的车载视角。本轮只改渲染和展示用的
// 派生计算（相对偏移、剩余距离），不改 handleStart 的接口调用逻辑，也不改播放推进
// 逻辑（仍是 window.setInterval 按帧推进 frameIndex）。所有数值（位置/速度/加速度/
// 停站误差）仍直接读取后端 states 与 stopResult 的原始字段，不在前端重新计算或写死。
//
// 阶段 1.5 视觉返工（第三轮，2D 俯视导航图风格）：前两轮的"车载侧视插画"风格被
// 反馈仍不理想，具体两个问题：
// 1) 目标停车点标记与列车图形使用了不一致的对齐基准（列车用 translateX(-18%) 这种
//    拍脑袋的偏移，标记用 translateX(-50%)），导致停站后目标点视觉上没有精确落在
//    列车正中心，即使两者的底层坐标数值是对的。
// 2) 整体视觉语言是"地铁侧视剖面插画"，而不是用户想要的"高德地图导航俯视图"。
// 本轮重做为 2D 俯视地图风格：浅色地图底 + 街区色块、蓝色路线带（代表选中路线，
// 参照导航 App 的高亮路线条）、白色车道虚线随行驶滚动、POI 图钉式地标（起点/
// 目标停车点/限速标）、列车改为俯视胶囊车身 + 方向箭头 + 呼吸光圈（类似导航 App
// 的"当前位置"定位点）。列车与所有地标统一使用同一套坐标系：水平用
// left: calc(30% + offsetPx)，垂直用同一条路线中心线 + transform 居中/图钉尖端对齐，
// 保证停站误差趋近于 0 时目标点图钉尖端与列车几何中心精确重合。仍然只改渲染，
// 不改 landmarks 的 (地标position - 当前position) 偏移计算公式，不改任何后端数值。

import { useEffect, useRef, useState } from 'react';
import { runVehicleSimulation } from '../../api/vehicle';
import type { SimulationResult, TrainState } from '../../types/vehicle';
import './vehicle.css';

// 播放速度：每隔多少毫秒推进一个 states 帧。仿真 dt=0.5s，这里用较快的
// 播放间隔让用户能在几秒内看完整个运行过程，同时仍能看清楚阶段变化。
const PLAYBACK_INTERVAL_MS = 60;

// ---- 阶段 1.5 可视化常量（纯展示用，不参与任何数值计算） ----

// 轨道滚动视图的缩放比例：每 1 米在屏幕上占多少像素。仅影响视觉呈现的疏密程度，
// 不影响 position/velocity/stopError 等真实数值。
const TRACK_SCALE_PX_PER_METER = 2.2;

// 说明：本轮改为 2D 俯视导航图风格（垂直向下的正交视角），地图底纹（scene-blocks）
// 与路线/地标使用同一套滚动速度（不再做侧视插画那种"近快远慢"的视差效果），
// 因为正交俯视摄像机下，同一地平面上的所有元素应当以相同速度滚动，这样才符合
// "俯视导航图"的物理直觉。

// 限速标在轨道上的展示位置（米）。后端 LineProfile 只提供一个全局限速数值，没有
// 提供"限速标"这种路侧标志的专属坐标字段，因此这里选取起点附近一个位置作为限速标
// 的视觉锚点，仅用于让限速标出现在轨道滚动视图中，不影响任何仿真数值计算。
const SPEED_LIMIT_SIGN_POSITION_M = 60;

// 限速数值的兜底显示值。与后端 service/DemoScenarioProvider.java 中内置的演示限速
// 20 m/s 保持一致，仅在页面暂时没有真实数据（例如尚未播放出任何一帧）时使用。
const DEMO_SPEED_LIMIT_FALLBACK_MS = 20;

// 后端 phase 枚举 -> 中文展示文案与样式 class 的映射。
// 兼容大写（TRACTION/COAST/BRAKING/STOPPED）和小写（traction/coast/braking/stopped）
// 两种可能的序列化形式，按大小写不敏感方式匹配。
const PHASE_LABELS: Record<string, { label: string; className: string }> = {
  TRACTION: { label: '牵引', className: 'phase-traction' },
  COAST: { label: '惰行', className: 'phase-coast' },
  BRAKING: { label: '制动', className: 'phase-braking' },
  STOPPED: { label: '停车', className: 'phase-stopped' },
};

function describePhase(phase: string) {
  const key = phase?.toUpperCase();
  return PHASE_LABELS[key] ?? { label: phase, className: '' };
}

// 把后端返回的真实停站误差（result.stopResult.stopError）格式化为带方向的文案。
// stopError 定义为 actualStopPosition - targetStopPosition：
//   >= 0 表示冲过目标停车点（多走了这么多米）；
//   <  0 表示未到目标停车点（少走了这么多米，toFixed 后自带负号）。
// 数值直接来自后端原始字段，只做 toFixed(2) 格式化，不做任何重新计算或四舍五入到
// 失真的处理（与页面下方停站结果表使用的精度一致）。
function formatStopOffset(stopError: number): string {
  if (stopError >= 0) {
    return `冲过 +${stopError.toFixed(2)}m`;
  }
  return `未到 ${Math.abs(stopError).toFixed(2)}m`;
}

// 把剩余距离格式化为不带负零的字符串。remainingDistance 在数学上应当随播放单调
// 递减到 0，但浮点运算可能残留极小的负值（例如 -0.000001），toFixed(1) 后会显示
// 成 "-0.0" 这种视觉上的伪影。这里只对"绝对值小于半个显示精度"的情况做展示层的
// 归零处理，不改变 remainingDistance 本身的计算方式，也不影响其他任何读取
// remainingDistance 真实数值的地方。
function formatRemainingDistance(remainingDistance: number): string {
  const rounded = Math.abs(remainingDistance) < 0.05 ? 0 : remainingDistance;
  return rounded.toFixed(1);
}

type PageStatus = 'idle' | 'loading' | 'playing' | 'finished' | 'error';

type TrackLandmarkType = 'start' | 'limit' | 'target';

interface TrackLandmark {
  key: string;
  type: TrackLandmarkType;
  /** 该地标在线路上的真实位置（米），全部来自后端数据或与后端演示配置一致的常量。 */
  position: number;
}

function Vehicle() {
  const [status, setStatus] = useState<PageStatus>('idle');
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [result, setResult] = useState<SimulationResult | null>(null);
  const [frameIndex, setFrameIndex] = useState(0);

  const timerRef = useRef<number | null>(null);

  const clearTimer = () => {
    if (timerRef.current !== null) {
      window.clearInterval(timerRef.current);
      timerRef.current = null;
    }
  };

  // 组件卸载时清理定时器，避免内存泄漏。
  useEffect(() => {
    return () => clearTimer();
  }, []);

  const handleStart = async () => {
    clearTimer();
    setStatus('loading');
    setErrorMessage(null);
    setResult(null);
    setFrameIndex(0);

    try {
      const simulationResult = await runVehicleSimulation();

      if (!simulationResult.states || simulationResult.states.length === 0) {
        throw new Error('后端返回的仿真结果不包含任何 states 数据');
      }

      setResult(simulationResult);
      setStatus('playing');
      setFrameIndex(0);

      const totalFrames = simulationResult.states.length;
      let currentIndex = 0;

      timerRef.current = window.setInterval(() => {
        currentIndex += 1;
        if (currentIndex >= totalFrames) {
          currentIndex = totalFrames - 1;
          setFrameIndex(currentIndex);
          clearTimer();
          setStatus('finished');
          return;
        }
        setFrameIndex(currentIndex);
      }, PLAYBACK_INTERVAL_MS);
    } catch (err) {
      clearTimer();
      setStatus('error');
      setErrorMessage(err instanceof Error ? err.message : String(err));
    }
  };

  const handleRetry = () => {
    setStatus('idle');
    setErrorMessage(null);
  };

  const currentState: TrainState | null =
    result && result.states.length > 0 ? result.states[frameIndex] : null;

  // 目标停车点：来自后端 stopResult；播放前尚无 result 时，用与后端演示线路一致的
  // 1200m 作为轨道视图的兜底展示位置（不用于任何停站判定计算）。
  const targetStopPosition = result?.stopResult?.targetStopPosition ?? 1200;

  // 轨道滚动视图使用的"当前位置"：有真实帧数据时用当前帧的真实 position；
  // 尚未开始仿真（idle）时用 0，让轨道视图呈现列车停在起点的静置状态。
  const displayPosition = currentState ? currentState.position : 0;

  // 剩余距离 = 目标停车点 - 当前 position，随播放推进自然递减；播放结束后
  // currentState 固定为最后一帧，remainingDistance 自然反映最终值，不额外特殊处理。
  const remainingDistance = targetStopPosition - displayPosition;

  // 限速值：当前 SimulationResult/SimulationSummary/StopResult 类型（types/vehicle.ts）
  // 均未暴露独立的 speedLimit 字段，后端也未在响应中返回线路限速本身，因此页面拿不到
  // “真实限速值”。按任务要求的兜底方案：直接显示与后端 service/DemoScenarioProvider.java
  // 内置演示限速一致的常量 20（见上方 DEMO_SPEED_LIMIT_FALLBACK_MS 注释），不用
  // summary.maxVelocity 等其他字段冒充限速（两者数值上偶然相等，但语义不同，不应混用）。
  const speedLimitValue = DEMO_SPEED_LIMIT_FALLBACK_MS;

  const stopError = result?.stopResult?.stopError ?? 0;

  const landmarks: TrackLandmark[] = [
    { key: 'start', type: 'start', position: 0 },
    { key: 'speed-limit', type: 'limit', position: SPEED_LIMIT_SIGN_POSITION_M },
    { key: 'target', type: 'target', position: targetStopPosition },
  ];

  const landmarkOffsetPx = (landmarkPosition: number) =>
    (landmarkPosition - displayPosition) * TRACK_SCALE_PX_PER_METER;

  const isBusy = status === 'loading' || status === 'playing';

  return (
    <div className="vehicle-page">
      <p className="vehicle-intro">
        车辆运行仿真工作台：点击“开始仿真”后，前端将调用后端车辆仿真接口
        （POST /api/vehicle/simulation/run），并按时间顺序播放返回的运行状态序列。
      </p>

      <div className="vehicle-controls">
        <button
          className="vehicle-start-btn"
          onClick={handleStart}
          disabled={isBusy}
        >
          {status === 'loading' ? '仿真计算中...' : status === 'playing' ? '播放中...' : '开始仿真'}
        </button>
        {status === 'playing' && currentState && (
          <span className="vehicle-status-text">
            正在播放第 {frameIndex + 1} / {result?.states.length} 帧（t = {currentState.time.toFixed(1)}s）
          </span>
        )}
        {status === 'finished' && <span className="vehicle-status-text">仿真播放完成</span>}
      </div>

      {status === 'error' && (
        <div className="vehicle-error">
          <span>仿真调用失败：{errorMessage}</span>
          <button onClick={handleRetry}>重试</button>
        </div>
      )}

      {/* 车载视角 2D 俯视导航图：整张地图（街区色块 + 路线带 + 车道虚线 + 地标）
          随 displayPosition 增大整体向左滚动，列车图标固定在视口水平约 30% 处，
          用垂直向下的正交视角呈现"沿路线导航"的效果，类似高德/百度地图导航。
          列车与所有地标（起点/限速标/目标停车点）严格使用同一套对齐规则：
          水平方向 left: calc(30% + offsetPx)，图形自身用 translateX(-50%) +
          translateY(-50%) 把"几何中心"精确对齐到该坐标点，不使用任何拍脑袋的
          百分比偏移，因此停站误差趋近于 0 时，目标点图钉尖端与列车中心点会精确
          重合（而不是像上一版那样因为对齐基准不一致产生视觉偏差）。 */}
      <div className="vehicle-scene-wrapper">
        <div className="scene-hud">
          <span className="hud-dot" />
          <span>车载运行监控 · ATO 仿真导航视图</span>
        </div>

        <div className="vehicle-scene">
          {/* 地图底纹：浅色街区色块 + 网格线，模拟导航 App 的地图底图，纯装饰，
              与路线带使用同一套滚动速度（俯视正交视角下不做远近视差）。 */}
          <div
            className="scene-map-base"
            style={{ backgroundPositionX: `${-(displayPosition * TRACK_SCALE_PX_PER_METER)}px` }}
          />

          {/* 路线带：模拟导航 App 中"选中路线"的高亮色带，车道虚线随行驶滚动。 */}
          <div className="scene-route-band">
            <div
              className="scene-route-lane-dashes"
              style={{ backgroundPositionX: `${-(displayPosition * TRACK_SCALE_PX_PER_METER)}px` }}
            />
          </div>

          {/* 目标停车点的站台区：路线带上一段高亮区域，代表站台范围。定位方式与
              landmarks 完全一致：calc(30% + offsetPx) + translateX(-50%)。 */}
          <div
            className="scene-platform-zone"
            style={{ left: `calc(30% + ${landmarkOffsetPx(targetStopPosition)}px)` }}
          />

          {landmarks.map((landmark) => (
            <div
              key={landmark.key}
              className={`scene-landmark scene-landmark-${landmark.type}`}
              style={{ left: `calc(30% + ${landmarkOffsetPx(landmark.position)}px)` }}
            >
              {landmark.type === 'start' && (
                <>
                  <div className="map-pin map-pin-start">
                    <span className="map-pin-dot" />
                  </div>
                  <div className="map-pin-tag map-pin-tag-start">起点 0m</div>
                </>
              )}
              {landmark.type === 'limit' && (
                <div className="speed-limit-sign">
                  <span className="speed-limit-value">{Math.round(speedLimitValue)}</span>
                </div>
              )}
              {landmark.type === 'target' && (
                <>
                  <div className="map-pin map-pin-target">
                    <span className="map-pin-dot" />
                  </div>
                  <div className="map-pin-tag map-pin-tag-target">
                    目标停车点 {targetStopPosition.toFixed(0)}m
                  </div>
                  {status === 'finished' && result && (
                    <div className={`stop-offset-badge ${stopError >= 0 ? 'is-overshoot' : 'is-undershoot'}`}>
                      <span className="stop-offset-arrow">{stopError >= 0 ? '▸' : '◂'}</span>
                      {formatStopOffset(stopError)}
                    </div>
                  )}
                </>
              )}
            </div>
          ))}

          {/* 列车图标：俯视胶囊造型，几何中心固定对齐视口水平 30% 处（与地标同一套
              对齐规则），车头方向箭头朝右，呼吸光圈模拟导航 App"当前位置"定位点。
              纯 CSS/DOM 实现，不引入任何图片或图标库。 */}
          <div className="scene-train">
            <span className="train-pulse-ring" />
            <div className="train-capsule">
              <span className="train-segment train-segment-tail" />
              <span className="train-segment train-segment-mid" />
              <span className="train-segment train-segment-head">
                <span className="train-heading-arrow" />
              </span>
            </div>
          </div>
        </div>

        <div className="scene-info-row">
          <div className="scene-info-primary">
            <span className="scene-remaining-label">距目标停车点</span>
            <span className="scene-remaining-value">{formatRemainingDistance(remainingDistance)}</span>
            <span className="scene-remaining-unit">m</span>
          </div>
          {currentState && (
            <span className="scene-time">
              t = {currentState.time.toFixed(1)}s ・ 加速度 {currentState.acceleration.toFixed(2)} m/s²
            </span>
          )}
          {!currentState && status === 'idle' && (
            <span className="scene-time">尚未开始仿真，路线为初始静置状态</span>
          )}
        </div>
      </div>

      {currentState && (
        <div className="vehicle-cards">
          <div className="vehicle-card">
            <div className="label">当前速度</div>
            <div className="value">{currentState.velocity.toFixed(2)} m/s</div>
          </div>
          <div className="vehicle-card">
            <div className="label">当前位置</div>
            <div className="value">{currentState.position.toFixed(1)} m</div>
          </div>
          <div className="vehicle-card">
            <div className="label">运行阶段</div>
            <div className={`value ${describePhase(currentState.phase).className}`}>
              {describePhase(currentState.phase).label}
            </div>
          </div>
        </div>
      )}

      {status === 'finished' && result && (
        <>
          <div className="vehicle-result">
            <h3>自动停站结果</h3>
            <table>
              <tbody>
                <tr>
                  <td>目标停车点</td>
                  <td>{result.stopResult.targetStopPosition.toFixed(1)} m</td>
                </tr>
                <tr>
                  <td>实际停车点</td>
                  <td>{result.stopResult.actualStopPosition.toFixed(1)} m</td>
                </tr>
                <tr>
                  <td>停站误差</td>
                  <td>{result.stopResult.stopError.toFixed(2)} m</td>
                </tr>
                <tr>
                  <td>是否成功</td>
                  <td className={result.stopResult.success ? 'stop-success' : 'stop-fail'}>
                    {result.stopResult.success ? '成功' : '未达标'}
                  </td>
                </tr>
                {result.stopResult.reason && (
                  <tr>
                    <td>原因/风险提示</td>
                    <td>{result.stopResult.reason}</td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>

          <div className="vehicle-result">
            <h3>仿真总结</h3>
            <table>
              <tbody>
                <tr>
                  <td>最大速度</td>
                  <td>{result.summary.maxVelocity.toFixed(2)} m/s</td>
                </tr>
                <tr>
                  <td>总运行时长</td>
                  <td>{result.summary.totalTime.toFixed(1)} s</td>
                </tr>
                <tr>
                  <td>终点位置</td>
                  <td>{result.summary.finalPosition.toFixed(1)} m</td>
                </tr>
                <tr>
                  <td>安全事件数量</td>
                  <td>{result.safetyEvents.length}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </>
      )}
    </div>
  );
}

export default Vehicle;
