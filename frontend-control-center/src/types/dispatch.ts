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
