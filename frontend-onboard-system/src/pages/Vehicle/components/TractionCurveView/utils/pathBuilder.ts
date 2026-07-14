export interface ForcePoint {
  time: number;
  tractionKN: number;
  brakeKN: number;
  resistKN: number;
  netKN: number;
}

export function clampVal(v: number, lo: number, hi: number): number {
  return Math.min(Math.max(v, lo), hi);
}

// ── SVG 布局常量 ──
export const SVG_W = 600;
export const SVG_H = 420;
export const MARGIN = { top: 30, right: 40, bottom: 55, left: 70 };
export const PLOT_W = SVG_W - MARGIN.left - MARGIN.right;
export const PLOT_H = SVG_H - MARGIN.top - MARGIN.bottom;
export const Y_MIN = -350;
export const Y_MAX = 350;

export function xScale(t: number, tMin: number, tMax: number) {
  const ratio = (t - tMin) / (tMax - tMin || 1);
  return MARGIN.left + ratio * PLOT_W;
}

export function yScale(forceKN: number) {
  return MARGIN.top + PLOT_H - ((forceKN - Y_MIN) / (Y_MAX - Y_MIN)) * PLOT_H;
}

/** 构建 SVG path，只取可见窗口内的点，且确保时间单调递增 */
export function buildTimePath(
  pts: ForcePoint[],
  getVal: (p: ForcePoint) => number,
  tMin: number,
  tMax: number,
  yClampLo: number,
  yClampHi: number,
): string {
  if (pts.length === 0) return '';
  // 二分查找 tMin 的起始索引
  let startIdx = 0;
  let lo = 0, hi = pts.length - 1;
  while (lo <= hi) {
    const mid = (lo + hi) >> 1;
    if (pts[mid].time < tMin) {
      lo = mid + 1;
    } else {
      startIdx = mid;
      hi = mid - 1;
    }
  }
  if (startIdx >= pts.length || pts[startIdx].time > tMax) return '';
  if (startIdx > 0) startIdx--; // 包含窗口外一个点，保证连线连续

  const parts: string[] = [];
  for (let i = startIdx; i < pts.length; i++) {
    const p = pts[i];
    if (p.time > tMax) break;
    const x = xScale(p.time, tMin, tMax).toFixed(1);
    const y = yScale(clampVal(getVal(p), yClampLo, yClampHi)).toFixed(1);
    parts.push(`${parts.length === 0 ? 'M' : 'L'} ${x} ${y}`);
  }
  return parts.join(' ');
}
