package com.bjtu.railtransit.dispatch;

import com.bjtu.railtransit.domain.model.DispatchPlan;
import com.bjtu.railtransit.domain.model.LineProfile;
import com.bjtu.railtransit.domain.model.SimulationSnapshot;
import com.bjtu.railtransit.domain.model.TrainCar;
import com.bjtu.railtransit.domain.model.TrainState;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class SimulationService {

    private final LineDataService lineDataService;

    private boolean simulationRunning = false;
    private double simulationTimeSeconds = 0;
    private final Map<String, TrainState> trains = new LinkedHashMap<>();
    private LineProfile lineProfile;

    // Track how many seconds a train has been in its current state for acceleration/deceleration
    private final Map<String, Integer> stateElapsed = new LinkedHashMap<>();

    // 位置历史记录（每 10 秒采样）
    private final List<SimulationSnapshot.TrainPositionPoint> positionHistory = new ArrayList<>();
    // 上次采样时间
    private double lastSampleTime = -10;
    // 到站记录
    private final Map<String, List<SimulationSnapshot.StationArrival>> stationArrivalMap = new LinkedHashMap<>();
    // 总能耗 (kWh)
    private double totalEnergyKwh = 0;

    public SimulationService(LineDataService lineDataService) {
        this.lineDataService = lineDataService;
    }

    public void startSimulation(int durationSec) {
        simulationTimeSeconds = 0;
        simulationRunning = true;
        trains.clear();
        stateElapsed.clear();
        lineProfile = lineDataService.getLineProfile();

        int trainCount = 8;
        int[] departureTimes = {0, 360, 720, 1080, 1440, 1800, 2160, 2520};
        List<LineProfile.Station> stations = lineProfile.getStations();

        for (int i = 0; i < trainCount; i++) {
            String trainId = "T" + (i + 1);
            TrainState train = new TrainState();
            train.setTrainId(trainId);
            train.setTrainName(trainId);
            train.setPositionMeters(0);
            train.setSpeed(0);
            train.setDepartureTime(String.valueOf(departureTimes[i]));

            if (i == 0) {
                train.setStatus("DEPARTING");
            } else {
                train.setStatus("STOPPED");
            }

            train.setCurrentStationIndex(-1);
            train.setNextStationIndex(0);
            if (!stations.isEmpty()) {
                train.setNextStationKm(stations.get(0).getKm());
            }

            List<TrainCar> cars = new ArrayList<>();
            for (int c = 0; c < 6; c++) {
                TrainCar car = new TrainCar();
                car.setCarIndex(c);
                car.setPositionMeters(train.getPositionMeters() - c * 19.0);
                car.setSpeed(0);
                car.setMass(35000.0);
                cars.add(car);
            }
            train.setCarCount(6);
            train.setCarLength(19.0);
            train.setCars(cars);

            trains.put(trainId, train);
            stateElapsed.put(trainId, 0);
        }

        positionHistory.clear();
        stationArrivalMap.clear();
        totalEnergyKwh = 0;
        lastSampleTime = -10;
    }

    public void stepSimulation(int steps) {
        for (int step = 0; step < steps; step++) {
            if (!simulationRunning) {
                return;
            }

            simulationTimeSeconds += 1;
            List<LineProfile.Station> stations = lineProfile.getStations();
            int stationCount = stations.size();

            for (TrainState train : trains.values()) {
                String status = train.getStatus();

                // FINISHED trains don't move
                if ("FINISHED".equals(status)) {
                    continue;
                }

                // STOPPED: check if it's time to depart
                if ("STOPPED".equals(status)) {
                    double depTime = Double.parseDouble(train.getDepartureTime());
                    if (simulationTimeSeconds >= depTime) {
                        train.setStatus("DEPARTING");
                        stateElapsed.put(train.getTrainId(), 0);
                        // Update next station info if needed
                        if (train.getNextStationIndex() < stationCount) {
                            train.setNextStationKm(stations.get(train.getNextStationIndex()).getKm());
                        }
                    }
                    continue;
                }

                // Update speed based on status
                int elapsed = stateElapsed.getOrDefault(train.getTrainId(), 0) + 1;
                stateElapsed.put(train.getTrainId(), elapsed);

                if ("DEPARTING".equals(status)) {
                    double newSpeed = Math.min(60, elapsed * 2.0);
                    train.setSpeed(newSpeed);
                    if (newSpeed >= 60) {
                        train.setStatus("RUNNING");
                    }
                } else if ("RUNNING".equals(status)) {
                    train.setSpeed(60);
                } else if ("ARRIVING".equals(status)) {
                    double newSpeed = Math.max(0, 60 - elapsed * 2.0);
                    train.setSpeed(newSpeed);
                }

                // Advance position
                double deltaMeters = train.getSpeed() / 3.6;
                train.setPositionMeters(train.getPositionMeters() + deltaMeters);

                // Sync car positions
                if (train.getCars() != null) {
                    for (TrainCar car : train.getCars()) {
                        car.setPositionMeters(train.getPositionMeters() - car.getCarIndex() * train.getCarLength());
                        car.setSpeed(train.getSpeed());
                    }
                }

                // Update current station index based on position
                double positionKm = train.getPositionMeters() / 1000.0;
                int newStationIdx = -1;
                for (int i = stationCount - 1; i >= 0; i--) {
                    if (positionKm >= stations.get(i).getKm()) {
                        newStationIdx = i;
                        break;
                    }
                }
                train.setCurrentStationIndex(newStationIdx);

                // Check if approaching next station (for RUNNING or DEPARTING trains)
                if (("RUNNING".equals(train.getStatus()) || "DEPARTING".equals(train.getStatus()))
                        && train.getNextStationIndex() < stationCount) {
                    double nextKm = train.getNextStationKm();
                    double distToStation = nextKm - positionKm;
                    if (distToStation <= 0.2 && distToStation > 0) {
                        train.setStatus("ARRIVING");
                        stateElapsed.put(train.getTrainId(), 0);
                    }
                }

                // Check arrival at station (ARRIVING, speed reached 0)
                if ("ARRIVING".equals(train.getStatus()) && train.getSpeed() <= 0) {
                    train.setSpeed(0);
                    int arrivedIdx = train.getNextStationIndex();
                    train.setCurrentStationIndex(arrivedIdx);
                    int nextIdx = arrivedIdx + 1;

                    if (nextIdx >= stationCount) {
                        train.setStatus("FINISHED");
                    } else {
                        train.setNextStationIndex(nextIdx);
                        train.setNextStationKm(stations.get(nextIdx).getKm());
                        train.setStatus("STOPPED");
                        train.setDepartureTime(String.valueOf((int) simulationTimeSeconds));

                        // 记录到站时刻
                        String tid = train.getTrainId();
                        stationArrivalMap.putIfAbsent(tid, new ArrayList<>());
                        SimulationSnapshot.StationArrival arrival = new SimulationSnapshot.StationArrival();
                        arrival.setTrainId(tid);
                        arrival.setStationIndex(arrivedIdx);
                        arrival.setStationName(stations.get(arrivedIdx).getName());
                        arrival.setArrivalTimeSeconds(simulationTimeSeconds);
                        stationArrivalMap.get(tid).add(arrival);

                        // 更新最近到站记录的发车时间
                        List<SimulationSnapshot.StationArrival> arrs = stationArrivalMap.get(train.getTrainId());
                        if (arrs != null && !arrs.isEmpty()) {
                            SimulationSnapshot.StationArrival last = arrs.get(arrs.size() - 1);
                            if (last.getStationIndex() == arrivedIdx) {
                                last.setDepartureTimeSeconds(Double.parseDouble(train.getDepartureTime()));
                                last.setDwellSeconds(last.getDepartureTimeSeconds() - last.getArrivalTimeSeconds());
                            }
                        }
                    }
                }

                // Check if passed terminal station
                if (!"FINISHED".equals(train.getStatus()) && positionKm > lineProfile.getTotalLengthKm()) {
                    train.setStatus("FINISHED");
                }
            }

            // Update all active trains count
            updateTrainCounts();

            // 采样位置历史 (每10秒)
            if (simulationTimeSeconds - lastSampleTime >= 10) {
                for (TrainState train : trains.values()) {
                    if ("FINISHED".equals(train.getStatus())) continue;
                    SimulationSnapshot.TrainPositionPoint point = new SimulationSnapshot.TrainPositionPoint();
                    point.setTrainId(train.getTrainId());
                    point.setTimeSeconds(simulationTimeSeconds);
                    point.setPositionKm(train.getPositionMeters() / 1000.0);
                    positionHistory.add(point);
                }
                lastSampleTime = simulationTimeSeconds;
            }

            // 能耗估算: P = F*v = m*a*v (简化为牵引时计算)
            for (TrainState train : trains.values()) {
                if ("FINISHED".equals(train.getStatus())) continue;
                if (train.getSpeed() > 0 && !"STOPPED".equals(train.getStatus())) {
                    double speedMs = train.getSpeed() / 3.6;
                    double accel = ("RUNNING".equals(train.getStatus()) || "DEPARTING".equals(train.getStatus())) ? 1.0 : -1.2;
                    double force = (train.getCarCount() * 35000.0) * Math.abs(accel) + 3000.0;
                    double powerWatts = force * speedMs;
                    totalEnergyKwh += powerWatts / 3600000.0;
                }
            }
        }
    }

    private void updateTrainCounts() {
        // This is done in getSnapshot
    }

    public SimulationSnapshot getSnapshot() {
        SimulationSnapshot snapshot = new SimulationSnapshot();
        snapshot.setSimulationTime(simulationTimeSeconds);
        snapshot.setSimTimeFormatted(formatTime(simulationTimeSeconds));

        // Copy train states (defensive copy)
        List<TrainState> trainList = new ArrayList<>();
        int activeCount = 0;
        for (TrainState t : trains.values()) {
            TrainState copy = copyTrainState(t);
            trainList.add(copy);
            if (!"FINISHED".equals(t.getStatus())) {
                activeCount++;
            }
        }
        snapshot.setTotalTrains(trains.size());
        snapshot.setActiveTrains(activeCount);
        snapshot.setTrains(trainList);

        // 收集到站时刻
        List<SimulationSnapshot.StationArrival> arrivals = new ArrayList<>();
        for (List<SimulationSnapshot.StationArrival> list : stationArrivalMap.values()) {
            arrivals.addAll(list);
        }
        snapshot.setStationArrivals(arrivals);

        // Headway calculations
        List<SimulationSnapshot.HeadwayInfo> headwayList = new ArrayList<>();
        List<TrainState> activeTrains = trains.values().stream()
                .filter(t -> !"FINISHED".equals(t.getStatus()))
                .sorted(Comparator.comparingDouble(TrainState::getPositionMeters))
                .collect(Collectors.toList());

        for (int i = 0; i < activeTrains.size() - 1; i++) {
            TrainState following = activeTrains.get(i);
            TrainState leading = activeTrains.get(i + 1);

            SimulationSnapshot.HeadwayInfo headway = new SimulationSnapshot.HeadwayInfo();
            headway.setFromTrainId(following.getTrainId());
            headway.setToTrainId(leading.getTrainId());
            double distance = leading.getPositionMeters() - following.getPositionMeters();
            headway.setDistanceMeters(distance);

            double timeGap = 999;
            if (following.getSpeed() > 0) {
                timeGap = distance / (following.getSpeed() / 3.6);
            }
            headway.setTimeSeconds(timeGap);

            if (distance < 100) {
                headway.setStatus("DANGER");
            } else if (distance < 500) {
                headway.setStatus("WARNING");
            } else {
                headway.setStatus("SAFE");
            }
            headwayList.add(headway);
        }
        snapshot.setHeadways(headwayList);

        // Commands
        List<SimulationSnapshot.TrainCommand> commands = new ArrayList<>();
        for (TrainState train : trains.values()) {
            if ("ARRIVING".equals(train.getStatus())) {
                SimulationSnapshot.TrainCommand cmd = new SimulationSnapshot.TrainCommand();
                cmd.setTrainId(train.getTrainId());
                cmd.setCommandType("ARRIVE");
                cmd.setTargetValue(train.getNextStationKm());
                cmd.setReason("Approaching station, distance < 200m");
                commands.add(cmd);
            }
        }
        snapshot.setCommands(commands);

        snapshot.setPositionHistory(new ArrayList<>(positionHistory));
        snapshot.setTotalEnergyKwh(totalEnergyKwh);

        return snapshot;
    }

    public DispatchPlan getDispatchPlan() {
        DispatchPlan plan = new DispatchPlan();
        plan.setLineId(lineProfile != null ? lineProfile.getLineId() : "line-9");
        plan.setHeadwaySeconds(360);
        plan.setDepartureIntervalSeconds(360);
        plan.setTrainCount(8);

        List<LineProfile.Station> stations = lineProfile.getStations();
        List<DispatchPlan.ScheduleEntry> schedule = new ArrayList<>();
        int[] departureTimes = {0, 360, 720, 1080, 1440, 1800, 2160, 2520};

        for (int i = 0; i < 8 && i < stations.size(); i++) {
            DispatchPlan.ScheduleEntry entry = new DispatchPlan.ScheduleEntry();
            entry.setTrainId("T" + (i + 1));
            entry.setStationIndex(0);
            entry.setPlannedArrivalKm(stations.get(0).getKm());
            entry.setPlannedDepartureTime(formatTime(departureTimes[i]));
            schedule.add(entry);
        }
        plan.setSchedule(schedule);

        return plan;
    }

    public boolean isRunning() {
        return simulationRunning;
    }

    private TrainState copyTrainState(TrainState source) {
        TrainState copy = new TrainState();
        copy.setTrainId(source.getTrainId());
        copy.setTrainName(source.getTrainName());
        copy.setPositionMeters(source.getPositionMeters());
        copy.setSpeed(source.getSpeed());
        copy.setStatus(source.getStatus());
        copy.setCurrentStationIndex(source.getCurrentStationIndex());
        copy.setNextStationIndex(source.getNextStationIndex());
        copy.setDepartureTime(source.getDepartureTime());
        copy.setNextStationKm(source.getNextStationKm());
        copy.setCarCount(source.getCarCount());
        copy.setCarLength(source.getCarLength());

        if (source.getCars() != null) {
            List<TrainCar> copiedCars = new ArrayList<>();
            for (TrainCar car : source.getCars()) {
                TrainCar carCopy = new TrainCar();
                carCopy.setCarIndex(car.getCarIndex());
                carCopy.setPositionMeters(car.getPositionMeters());
                carCopy.setSpeed(car.getSpeed());
                carCopy.setMass(car.getMass());
                copiedCars.add(carCopy);
            }
            copy.setCars(copiedCars);
        }

        return copy;
    }

    private String formatTime(double totalSeconds) {
        int total = (int) totalSeconds;
        int hours = total / 3600;
        int minutes = (total % 3600) / 60;
        int seconds = total % 60;
        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }
}
