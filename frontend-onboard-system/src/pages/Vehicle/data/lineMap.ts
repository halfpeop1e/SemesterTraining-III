/**
 * Beijing Metro Line 9 station chainage derived from the teacher-provided XLS export.
 * Keep this fallback aligned with backend resources/configs/line-profile.json.
 * Runtime integration data still comes from the backend; this list only renders the cab map.
 */
export interface StationInfo {
  id: number;
  stationId: number;
  name: string;
  displayName: string;
  displayNameOverride?: string;
  code: string;
  km: number;
  positionM: number;
  longitude: number;
  latitude: number;
  isTerminal: boolean;
  isHub: boolean;
}

const SOURCE = [
  [1, '郭公庄', 'GGZ', 0.313, 116.301889, 39.814322],
  [2, '丰台科技园', 'FSP', 1.661, 116.297176, 39.825233],
  [3, '科怡路', 'KYL', 2.449, 116.297432, 39.832480],
  [4, '丰台南路', 'FTN', 3.429, 116.296918, 39.841101],
  [5, '丰台东大街', 'FTD', 5.014, 116.293857, 39.855111],
  [6, '七里庄', 'QLZ', 6.340, 116.294212, 39.866121],
  [7, '六里桥', 'LLQ', 8.119, 116.302808, 39.880239],
  [8, '六里桥东', 'LLE', 9.429, 116.315142, 39.886886],
  [9, '北京西站', 'BWR', 10.599, 116.321218, 39.894706],
  [10, '军事博物馆', 'JBG', 11.997, 116.321459, 39.907422],
  [11, '白堆子', 'BDZ', 13.907, 116.325762, 39.923818],
  [12, '白石桥南', 'BQS', 14.954, 116.325680, 39.933022],
  [13, '国家图书馆', 'GTG', 16.049, 116.325190, 39.943114],
] as const;

export const STATIONS: StationInfo[] = SOURCE.map(([id, name, code, km, longitude, latitude]) => ({
  id,
  stationId: id,
  name,
  displayName: name,
  code,
  km,
  positionM: Math.round(km * 1000),
  longitude,
  latitude,
  isTerminal: id === 1 || id === 13,
  isHub: [7, 9, 10, 12, 13].includes(id),
}));

export interface LineMapStation {
  id: number;
  stationId: number;
  name: string;
  displayName: string;
  displayNameOverride?: string;
  positionM: number;
}

export const LINE_MAP = {
  lineStartM: 313,
  lineEndM: 16049,
  totalLengthM: 16049,
  stations: STATIONS.map(({ id, stationId, name, displayName, positionM }) => ({
    id, stationId, name, displayName, positionM,
  })) as LineMapStation[],
};
