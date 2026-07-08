export interface TrainCar {
  carIndex: number;
  positionMeters: number;
  speed: number;
  mass: number;
}

export interface TrainState {
  trainId: string;
  trainName: string;
  trainNumber: string;      // 正式车次号 (如 90101)
  direction: 'UP' | 'DOWN'; // 运行方向
  routePattern: string;     // 交路模式 FULL | SHORT_N | SHORT_S
  operationLevel: string;   // 运行等级 NORMAL | ENERGY_SAVE | EXPRESS | SLOW
  skipNextStation: boolean; // 下一站甩站通过
  turnbackCount: number;    // 已完成折返次数
  positionMeters: number;
  speed: number; // km/h
  status: 'DEPOT_WAITING' | 'DEPARTING' | 'ACCELERATING' | 'CRUISING' | 'BRAKING' | 'DWELLING' | 'TURNING_BACK' | 'FINISHED';
  currentStationIndex: number;
  nextStationIndex: number;
  departureTime: string;
  nextStationKm: number;
  carCount: number;
  carLength: number;
  cars: TrainCar[];
  delaySeconds: number;
  plannedDwellSeconds: number;
  actualDwellSeconds: number;
  plannedDepartureFromDepot: number;
  plannedArrivalAtStation: number;
  actualArrivalAtStation: number;
  actualDepartureFromStation: number;
  maxSpeedLimit: number;
  acceleration: number;
  sectionDistance: number;  // 当前区间距离 (m)
  sectionProgress: number;  // 当前区间已行进 (m)
  emergencyBraking: boolean;
  movementAuthority: number;
}

export interface HeadwayInfo {
  fromTrainId: string;
  toTrainId: string;
  distanceMeters: number;
  timeSeconds: number;
  status: 'SAFE' | 'CAUTION' | 'WARNING' | 'DANGER';
  safetyDistanceMeters: number;
}

export interface TrainCommand {
  trainId: string;
  commandType: 'DEPART' | 'HOLD' | 'SLOW' | 'ARRIVE' | 'TERMINATE' | 'SPEED_UP' | 'EMERGENCY_BRAKE' | 'TURN_BACK' | 'SKIP_STATION' | 'CHANGE_LEVEL' | 'SHORT_TURN' | 'RESUME_NORMAL';
  targetValue: number;
  reason: string;
  issuedTime: number;
  effectiveTime: number;
  completedTime: number;
  status: 'ISSUED' | 'ACKED' | 'EXECUTING' | 'COMPLETED' | 'REJECTED';
}

export interface TrainPositionPoint {
  trainId: string;
  timeSeconds: number;
  positionKm: number;
  direction?: string; // UP | DOWN
}

export interface StationArrival {
  trainId: string;
  stationName: string;
  stationIndex: number;
  arrivalTimeSeconds: number;
  departureTimeSeconds: number;
  dwellSeconds: number;
  plannedArrivalSeconds: number;
  plannedDepartureSeconds: number;
  plannedDwellSeconds: number;
  arrivalDeviation: number;
  departureDeviation: number;
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
  delayEvents: DelayEvent[];
  passengerFlow: PassengerFlowInfo;
  dispatchInfo: DispatchInfo;
  /** 计划运行图点位 */
  plannedDiagramPoints: TrainPositionPoint[];
  /** 计划偏差 */
  planDeviations: StationArrival[];
}

export interface DelayEvent {
  timeSeconds: number;
  trainId: string;
  delaySeconds: number;
  cause: string;
  affectedTrainId: string;
  positionKm: number;
  eventType: 'PRIMARY_DELAY' | 'PROPAGATED' | 'RECOVERED';
}

export interface PassengerFlowInfo {
  simTimeSeconds: number;
  period: string;
  periodMultiplier: number;
  eventMultiplier: number;
  /** 最大断面客流量 人次/h */
  peakSectionFlow: number;
  /** 峰值断面所在站点ID */
  peakSectionStationId: number;
  /** 各断面明细 */
  sectionFlows: SectionFlowItem[];
  demandHeadway: number;
  requiredTrains: number;
  availableTrains: number;
  targetLoadFactor: number;
}

export interface SectionFlowItem {
  fromStationId: number;
  toStationId: number;
  load: number;
  boarding: number;
  alighting: number;
  loadFactor: number;
}

export interface DispatchInfo {
  recommendedHeadway: number;
  actualHeadway: number;
  onlineTrains: number;
  maxAvailableTrains: number;
  requiredTrains: number;
  fleetSufficient: boolean;
  dispatchMode: string;
}

export interface StationGeo {
  id: number;
  name: string;
  code: string;
  longitude: number;
  latitude: number;
  km: number;
}

export interface ApiResponse<T> {
  success: boolean;
  message: string;
  data: T;
}
