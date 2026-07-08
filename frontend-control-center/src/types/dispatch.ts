export interface TrainCar {
  carIndex: number;
  positionMeters: number;
  speed: number;
  mass: number;
}

export interface TrainState {
  trainId: string;
  trainName: string;
  positionMeters: number;
  speed: number; // km/h
  status: 'STOPPED' | 'RUNNING' | 'ARRIVING' | 'DEPARTING' | 'FINISHED';
  currentStationIndex: number;
  nextStationIndex: number;
  departureTime: string;
  nextStationKm: number;
  carCount: number;
  carLength: number;
  cars: TrainCar[];
}

export interface HeadwayInfo {
  fromTrainId: string;
  toTrainId: string;
  distanceMeters: number;
  timeSeconds: number;
  status: 'SAFE' | 'WARNING' | 'DANGER';
}

export interface TrainCommand {
  trainId: string;
  commandType: 'DEPART' | 'HOLD' | 'SLOW' | 'ARRIVE' | 'TERMINATE';
  targetValue: number;
  reason: string;
}

export interface TrainPositionPoint {
  trainId: string;
  timeSeconds: number;
  positionKm: number;
}

export interface StationArrival {
  trainId: string;
  stationName: string;
  stationIndex: number;
  arrivalTimeSeconds: number;
  departureTimeSeconds: number;
  dwellSeconds: number;
}

export interface SimulationSnapshot {
  simulationTime: number;
  simTimeFormatted: string;
  totalTrains: number;
  activeTrains: number;
  trains: TrainState[];
  headways: HeadwayInfo[];
  commands: TrainCommand[];
  positionHistory: TrainPositionPoint[];
  stationArrivals: StationArrival[];
  totalEnergyKwh: number;
  totalTractionKwh: number;
  totalRegenKwh: number;
  peakPowerKw: number;
  maxSpeedLimit: number;
}

export interface StationGeo {
  id: number;
  name: string;
  code: string;
  longitude: number;
  latitude: number;
  km: number;
}

export interface TrackWaypoint {
  km: number;
  lat: number;
  lng: number;
  name: string;
  direction: number;
}

export interface TrackSegment {
  id: number;
  lengthM: number;
  startPointId: number;
  endPointId: number;
  forwardNextSegId: number | null;
  sideNextSegId: number | null;
  endForwardSegId: number | null;
  endSideSegId: number | null;
  zcId: number;
  atsId: number;
  ciId: number;
}

export interface PlatformGeo {
  id: number;
  km: number;
  direction: string;
}

export interface TrackGeometryData {
  trackWaypoints: TrackWaypoint[];
  segments: TrackSegment[];
  platforms: PlatformGeo[];
}

export interface ApiResponse<T> {
  success: boolean;
  message: string;
  data: T;
}

// ==================== 能耗 ====================
export interface EnergyRecord {
  trainId: number;
  totalTractionEnergyKwh: number;
  totalRegenEnergyKwh: number;
  netEnergyKwh: number;
  maxTractionPowerKw: number;
  avgTractionPowerKw: number;
}

export interface EnergySnapshot {
  energyRecords: EnergyRecord[];
  totalTractionKwh: number;
  totalRegenKwh: number;
  netEnergyKwh: number;
  maxPeakKw: number;
  timeOfPeak: number;
  vehiclesAtPeak: number[];
  riskLevel: string; // "safe" | "warning" | "danger"
  powerSupplyThreshold: number;
  thresholdRatio: number;
}

// ==================== 线路设备 ====================

export interface Signal {
  索引编号: number;
  名称: string;
  类型: number;
  属性: string;
  所处Seg编号: number;
  所处Seg偏移量: number;
  防护方向: string;
  灯列信息: string;
  互联互通编号: number;
}

export interface SwitchGeo {
  索引编号: number;
  名称: string;
  联动道岔编号: number;
  方向: number;
  定位SegID: number;
  反位SegID: number;
  汇合SegID: number;
  侧向静态限速: number;
  互联互通编号: number;
}

export interface RouteInfo {
  索引编号: number;
  进路名称: string;
  进路性质: string;
  始端信号机编号: number;
  终端信号机编号: number;
  所属CI区域编号: number;
}

export interface SpeedLimitZone {
  索引编号: number;
  限速区段所处seg编号: number;
  起点所处seg偏移量: number;
  终点所处seg偏移量: number;
  关联道岔编号: number;
  限速值: number;
}

export interface GradientInfo {
  索引编号: number;
  坡度起点所处seg编号: number;
  坡度终点所处seg编号: number;
  坡度值: number;
  倾斜方向: string;
}

export interface DataSummary {
  signals: number;
  switches: number;
  routes: number;
  balises: number;
  speedLimits: number;
  gradients: number;
  tunnels: number;
  bumpers: number;
  floodGates: number;
  platformDoors: number;
  axleCounterSections: number;
  depotDoors: number;
  trackPoints: number;
  logicalSections: number;
  collisionZones: number;
}
