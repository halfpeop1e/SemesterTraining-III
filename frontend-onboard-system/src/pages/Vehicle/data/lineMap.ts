// 郭逸晨车载模块（成员三）—— 阶段 1.5-VIS 线路底图静态数据
//
// 本文件的数据来自仓库根目录的真实 CBTC 线路数据库 `线路数据(1).xls`（只读勘探，
// 未修改该文件），勘探方式：临时 Python 脚本 + xlrd 读取，把结果写入本地 UTF-8
// 文本文件后人工核对，核对完成后临时脚本和临时输出文件已删除，不遗留在仓库中。
//
// ---- 数据来源表与字段 ----
//
// 1. `车站表`（17 行，13 个车站，车站 ID 1~13）：
//    字段 `车站名称` 实际内容是 GGZ / FSP / KYL / FTN / FTD / QLZ / LLQ / LLE /
//    BWR / JBG / BDZ / BQS / GTG 这类 3 字母罗马化编码，不是完整中文站名。
//    该表同时给出每个车站关联的两个 `站台编号`（上行/下行各一个站台）。
//
// 2. `站台表`（60 行）：字段 `站台中心公里标` 给出每个站台的里程标注，格式为
//    `K<公里>+<米>.<毫米>`（如 `K0+313.000` = 313.000m，`k2+448.610` = 2448.610m，
//    源数据大小写不统一，本文件解析时按大小写不敏感处理）。只有站台 ID 1~26
//    （对应车站 1~13 的正线上下行站台）使用这种公里标格式；站台 ID 27 及以后的行
//    该字段是纯数字（1.0），且备注列写着"站1站后"/"2AG"/"转换轨1"等，明显是车辆段/
//    转换轨等非正线站台，因此本文件只采用站台 ID 1~26 这 26 行作为正线站台数据源。
//
// ---- 站名齐全性结论（必须如实说明，不得编造真实站名） ----
//
// `车站表.车站名称` 字段里的 GGZ / FSP / KYL 等只是设备配置里的罗马化编码
// （很可能是站名拼音首字母组合，但源数据未给出对应的完整中文站名字段），本次勘探
// 的全部 33 个 sheet 中，没有找到任何"车站中文全名"字段。因此本文件按以下方式
// 处理站名：
//
//   - 不编造看起来像真实站名的中文名称（例如不会把 GGZ 猜成任何具体的中文站名）。
//   - 显示名使用"站<车站ID> · <罗马化编码>"的格式（如"站1 · GGZ"），明确标注这是
//     配置库中的原始编码而非确认过的正式中文站名。
//   - 郭逸晨如需展示正式中文站名，需要在 `STATIONS` 数组的 `code` 基础上补充
//     `displayNameOverride` 字段（本文件已预留该可选字段，当前全部为 undefined）。
//
// ---- 里程与线路总长 ----
//
// 13 个车站按公里标从小到大排列后（站 1 GGZ 313.000m → 站 13 GTG 16048.920m），
// 里程单调递增，与"沿正线从起点到终点"的顺序一致，说明车站表/站台表给出的公里标
// 本身就是沿正线方向单调的里程，可以直接作为"沿线路里程"使用，不需要额外的
// Seg 表拓扔换算。
//
// 线路总长取最后一站（GTG，16048.920m）为正线里程终点，未额外外推终点位置
// （真实线路在终点站以外可能还有存车线/交路折返轨，但那些不是本组仿真走廊要展示
// 的"正线运行范围"，故不纳入 `lineEndM`）。这与本轮任务描述中"真实线路约 16.5km"
// 的量级一致（本次勘探得到的正线站间里程为 16.05km，量级吻合，未做任何凑数）。
//
// 本文件只解决"沿线路的站点序列 + 里程 + 线路总长"这一件事，用作 LineRunView
// 组件的静态线路底图。真实的区段级动态限速（`静态限速表`/`Seg表`/`坡度表`）
// 需要完整的 Seg 拓扑遍历才能换算成里程区间，超出本轮"底图站点序列"范围，也属于
// 并行进行的"阶段1.6 704语义对齐"任务可能涉及的领域，本文件不处理、不重复实现，
// 避免合并冲突。组件内当前限速的显示改用仿真结果自带的 `speedLimit` prop
// （单一数值），不依赖里程分段限速表。

/** 原始站台公里标字符串解析结果，单位统一换算为米（m）。 */
function parseKmPost(raw: string): number {
  // 格式形如 "K0+313.000" 或 "k16+048.920"："K<公里数>+<米.毫米>"。
  const match = /^[Kk](\d+)\+(\d+(?:\.\d+)?)$/.exec(raw.trim());
  if (!match) {
    throw new Error(`无法解析站台公里标格式: ${raw}`);
  }
  const km = Number(match[1]);
  const meters = Number(match[2]);
  return km * 1000 + meters;
}

/**
 * 车站原始数据来源：`车站表`（车站 ID / 罗马化编码）+ `站台表`
 * （站台中心公里标，取该车站两个正线站台中的任意一个即可，因为同一车站的上下行
 * 站台公里标相同，例如站 1 的站台 1、2 均为 "K0+313.000"）。
 *
 * 逐条数据均可在 `线路数据(1).xls` 中核对：
 * - stationId=1 GGZ  <- 车站表 row(车站ID=1) + 站台表 row(站台ID=1, "K0+313.000")
 * - stationId=2 FSP  <- 车站表 row(车站ID=2) + 站台表 row(站台ID=3, "K1+660.520")
 * - stationId=3 KYL  <- 车站表 row(车站ID=3) + 站台表 row(站台ID=5, "k2+448.610")
 * - stationId=4 FTN  <- 车站表 row(车站ID=4) + 站台表 row(站台ID=7, "k3+429.320")
 * - stationId=5 FTD  <- 车站表 row(车站ID=5) + 站台表 row(站台ID=9, "k5+014.460")
 * - stationId=6 QLZ  <- 车站表 row(车站ID=6) + 站台表 row(站台ID=11, "k6+339.900")
 * - stationId=7 LLQ  <- 车站表 row(车站ID=7) + 站台表 row(站台ID=13, "K8+118.830")
 * - stationId=8 LLE  <- 车站表 row(车站ID=8) + 站台表 row(站台ID=15, "K9+429.160")
 * - stationId=9 BWR  <- 车站表 row(车站ID=9) + 站台表 row(站台ID=17, "K10+598.740")
 * - stationId=10 JBG <- 车站表 row(车站ID=10) + 站台表 row(站台ID=19, "K11+996.970")
 * - stationId=11 BDZ <- 车站表 row(车站ID=11) + 站台表 row(站台ID=21, "K13+906.770")
 * - stationId=12 BQS <- 车站表 row(车站ID=12) + 站台表 row(站台ID=23, "K14+954.010")
 * - stationId=13 GTG <- 车站表 row(车站ID=13) + 站台表 row(站台ID=25, "K16+048.920")
 */
const RAW_STATIONS: Array<{ stationId: number; code: string; kmPost: string }> = [
  { stationId: 1, code: 'GGZ', kmPost: 'K0+313.000' },
  { stationId: 2, code: 'FSP', kmPost: 'K1+660.520' },
  { stationId: 3, code: 'KYL', kmPost: 'k2+448.610' },
  { stationId: 4, code: 'FTN', kmPost: 'k3+429.320' },
  { stationId: 5, code: 'FTD', kmPost: 'k5+014.460' },
  { stationId: 6, code: 'QLZ', kmPost: 'k6+339.900' },
  { stationId: 7, code: 'LLQ', kmPost: 'K8+118.830' },
  { stationId: 8, code: 'LLE', kmPost: 'K9+429.160' },
  { stationId: 9, code: 'BWR', kmPost: 'K10+598.740' },
  { stationId: 10, code: 'JBG', kmPost: 'K11+996.970' },
  { stationId: 11, code: 'BDZ', kmPost: 'K13+906.770' },
  { stationId: 12, code: 'BQS', kmPost: 'K14+954.010' },
  { stationId: 13, code: 'GTG', kmPost: 'K16+048.920' },
];

/** 线路底图上的一个车站。 */
export interface LineStation {
  /** 车站表中的车站 ID（1~13）。 */
  stationId: number;
  /** 车站表 `车站名称` 字段的原始罗马化编码（如 GGZ），非确认过的正式中文站名。 */
  code: string;
  /** 站台表 `站台中心公里标` 原始字符串，保留用于核对/追溯。 */
  rawKmPost: string;
  /** 换算为米制里程后的站台中心位置，单位 m。 */
  positionM: number;
  /**
   * 供 UI 直接展示的站名占位文案："站<ID> · <编码>"。
   * 郭逸晨确认正式中文站名后，可在此文件补充 `displayNameOverride` 并在此处优先使用。
   */
  displayName: string;
  /** 预留字段：郭逸晨确认的正式中文站名。当前勘探数据中不存在，故全部为 undefined。 */
  displayNameOverride?: string;
}

/**
 * 郭逸晨确认的正式中文站名对照表（阶段 4A 补充）。
 * 键为车站表罗马化编码，值为中文站名。
 * 来源：任务指令中郭逸晨提供的编码-中文名对照。
 */
const CHINESE_NAMES: Record<string, string> = {
  GGZ: '郭公庄',
  FSP: '丰台科技园',
  KYL: '科怡路',
  FTN: '丰台南路',
  FTD: '丰台东大街',
  QLZ: '七里庄',
  LLQ: '六里桥',
  LLE: '六里桥东',
  BWR: '北京西站',
  JBG: '军事博物馆',
  BDZ: '白堆子',
  BQS: '白石桥南',
  GTG: '国家图书馆',
};

export const STATIONS: LineStation[] = RAW_STATIONS.map((s) => ({
  stationId: s.stationId,
  code: s.code,
  rawKmPost: s.kmPost,
  positionM: parseKmPost(s.kmPost),
  displayName: `站${s.stationId} · ${s.code}`,
  displayNameOverride: CHINESE_NAMES[s.code],
}));

/** 正线里程起点，单位 m。与后端演示线路的 0m 起点保持一致。 */
export const LINE_START_M = 0;

/**
 * 正线里程终点，单位 m。取最后一站（GTG）的站台中心公里标，
 * 即本次勘探到的正线站点序列所覆盖的最大里程。
 */
export const LINE_END_M = STATIONS[STATIONS.length - 1].positionM;

/** 正线全长，单位 m（= LINE_END_M - LINE_START_M）。 */
export const LINE_TOTAL_LENGTH_M = LINE_END_M - LINE_START_M;

export interface LineMapData {
  lineStartM: number;
  lineEndM: number;
  totalLengthM: number;
  stations: LineStation[];
  /** 数据来源与勘探结论说明，供 UI 或后续文档直接引用，避免结论散落。 */
  sourceNote: string;
}

export const LINE_MAP: LineMapData = {
  lineStartM: LINE_START_M,
  lineEndM: LINE_END_M,
  totalLengthM: LINE_TOTAL_LENGTH_M,
  stations: STATIONS,
  sourceNote:
    '数据来源：线路数据(1).xls 的 车站表 + 站台表（站台中心公里标）。' +
    '真实站点数 13，站名字段仅有罗马化编码（无完整中文站名字段），' +
    '故站名用"站<ID> · <编码>"占位，需郭逸晨补充真实中文站名。',
};
