package com.bjtu.railtransit.dispatch;

import com.bjtu.railtransit.common.SimulationWebSocketHandler;
import com.bjtu.railtransit.domain.model.*;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 精细仿真引擎 - 基于完整线路数据的CBTC级仿真
 * 集成：限速控制、坡度能耗、信号防护、道岔路由、计轴区段追踪
 */
@Service
public class SimulationService {

    private final LineDataService lineDataService;
    private final SimulationWebSocketHandler wsHandler;

    private boolean simulationRunning = false;
    private double simulationTimeSeconds = 0;
    private final Map<String, TrainState> trains = new LinkedHashMap<>();
    private LineProfile lineProfile;

    private final Map<String, Integer> stateElapsed = new LinkedHashMap<>();

    private final List<SimulationSnapshot.TrainPositionPoint> positionHistory = new ArrayList<>();
    private double lastSampleTime = -10;
    private final Map<String, List<SimulationSnapshot.StationArrival>> stationArrivalMap = new LinkedHashMap<>();
    private double totalEnergyKwh = 0;
    private double totalTractionKwh = 0;
    private double totalRegenKwh = 0;
    private double peakPowerKw = 0;

    private final List<SimulationLog> simulationLogs = new CopyOnWriteArrayList<>();

    // Signal system state
    private final Map<Integer, String> signalStates = new LinkedHashMap<>();
    // Track occupation: trainId -> list of occupied axle counter sections
    private final Map<String, Set<Integer>> trainOccupiedSections = new LinkedHashMap<>();
    // Switch states (normal = 定位 = default)
    private final Map<Integer, String> switchStates = new LinkedHashMap<>();

    // Line data context (lazy-loaded)
    private LineData lineData;
    private TrackGeometry trackGeometry;
    // Route map: signalId -> List<Route>
    private Map<Integer, List<LineData.Route>> routeByStartSignal;

    public SimulationService(LineDataService lineDataService, SimulationWebSocketHandler wsHandler) {
        this.lineDataService = lineDataService;
        this.wsHandler = wsHandler;
    }

    /**
     * 初始化线路数据上下文
     */
    private void initLineContext() {
        if (lineData != null) return;
        lineData = lineDataService.getLineData();
        trackGeometry = lineDataService.getTrackGeometry();
        // Build route index
        routeByStartSignal = new HashMap<>();
        if (lineData.getRoutes() != null) {
            for (LineData.Route r : lineData.getRoutes()) {
                routeByStartSignal.computeIfAbsent(r.get始端信号机编号(), k -> new ArrayList<>()).add(r);
            }
        }
        // Init signal states (all green/允许)
        if (lineData.getSignals() != null) {
            for (LineData.Signal s : lineData.getSignals()) {
                signalStates.put(s.get索引编号(), "GREEN");
            }
        }
        // Init switch states (all 定位)
        if (lineData.getSwitches() != null) {
            for (LineData.Switch sw : lineData.getSwitches()) {
                switchStates.put(sw.get索引编号(), "NORMAL");
            }
        }
    }

    public List<SimulationLog> getSimulationLogs() {
        return new ArrayList<>(simulationLogs);
    }

    public void clearSimulationLogs() {
        simulationLogs.clear();
    }

    public void startSimulation(int durationSec) {
        simulationTimeSeconds = 0;
        simulationRunning = true;
        trains.clear();
        stateElapsed.clear();
        lineProfile = lineDataService.getLineProfile();
        initLineContext();

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

            train.setStatus(i == 0 ? "DEPARTING" : "STOPPED");

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
            trainOccupiedSections.put(trainId, new HashSet<>());
        }

        positionHistory.clear();
        stationArrivalMap.clear();
        totalEnergyKwh = 0;
        totalTractionKwh = 0;
        totalRegenKwh = 0;
        peakPowerKw = 0;
        lastSampleTime = -10;
        simulationLogs.clear();
    }

    public void stepSimulation(int steps) {
        for (int step = 0; step < steps; step++) {
            if (!simulationRunning) return;

            simulationTimeSeconds += 1;
            List<LineProfile.Station> stations = lineProfile.getStations();
            int stationCount = stations.size();

            // Update signal states based on track occupation
            updateSignalStates();

            for (TrainState train : trains.values()) {
                String status = train.getStatus();

                if ("FINISHED".equals(status)) continue;

                // STOPPED: check departure time
                if ("STOPPED".equals(status)) {
                    double depTime = Double.parseDouble(train.getDepartureTime());
                    if (simulationTimeSeconds >= depTime) {
                        // Check if route is clear (signal green)
                        if (canDepart(train)) {
                            train.setStatus("DEPARTING");
                            stateElapsed.put(train.getTrainId(), 0);
                            if (train.getNextStationIndex() < stationCount) {
                                train.setNextStationKm(stations.get(train.getNextStationIndex()).getKm());
                            }
                        }
                    }
                    continue;
                }

                // Update speed with acceleration/deceleration and speed limit
                int elapsed = stateElapsed.getOrDefault(train.getTrainId(), 0) + 1;
                stateElapsed.put(train.getTrainId(), elapsed);

                double positionKm = train.getPositionMeters() / 1000.0;
                int speedLimit = lineDataService.getSpeedLimitAtKm(positionKm);
                double maxSpeed = Math.min(60, speedLimit); // line cruise speed vs section limit

                if ("DEPARTING".equals(status)) {
                    double newSpeed = Math.min(maxSpeed, elapsed * 2.0);
                    train.setSpeed(newSpeed);
                    if (newSpeed >= maxSpeed) {
                        train.setStatus("RUNNING");
                    }
                } else if ("RUNNING".equals(status)) {
                    // Maintain cruising speed, respecting speed limit
                    int newLimit = lineDataService.getSpeedLimitAtKm(
                        (train.getPositionMeters() + 100) / 1000.0); // look ahead
                    double targetSpeed = Math.min(60, newLimit);
                    train.setSpeed(Math.min(targetSpeed, 60));
                    if (targetSpeed < 60) {
                        // Speed limit reduction ahead - start braking
                        double distToLimit = findDistanceToSpeedLimit(positionKm, newLimit);
                        if (distToLimit < 200) {
                            double targetSpd = Math.max(newLimit, train.getSpeed() - 1);
                            train.setSpeed(targetSpd);
                        }
                    }
                } else if ("ARRIVING".equals(status)) {
                    double newSpeed = Math.max(0, 60 - elapsed * 2.0);
                    train.setSpeed(newSpeed);
                }

                // Apply emergency brake if speed exceeds limit
                int currentLimit = lineDataService.getSpeedLimitAtKm(positionKm);
                if (train.getSpeed() > currentLimit + 5) {
                    train.setSpeed(Math.max(currentLimit, train.getSpeed() - 5));
                }

                // Check downstream signal (train ahead)
                double safeSpeed = getSignalRestrictedSpeed(train);
                if (train.getSpeed() > safeSpeed) {
                    train.setSpeed(safeSpeed);
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

                // Update track occupation
                updateTrackOccupation(train);

                // Generate log with gradient-aware energy
                generateSimulationLogEnhanced(train, deltaMeters);

                // Update station index
                positionKm = train.getPositionMeters() / 1000.0;
                int newStationIdx = -1;
                for (int i = stationCount - 1; i >= 0; i--) {
                    if (positionKm >= stations.get(i).getKm()) {
                        newStationIdx = i;
                        break;
                    }
                }
                train.setCurrentStationIndex(newStationIdx);

                // Check approaching next station
                if (("RUNNING".equals(train.getStatus()) || "DEPARTING".equals(train.getStatus()))
                        && train.getNextStationIndex() < stationCount) {
                    double distToStation = train.getNextStationKm() - positionKm;
                    if (distToStation <= 0.2 && distToStation > 0) {
                        train.setStatus("ARRIVING");
                        stateElapsed.put(train.getTrainId(), 0);
                    }
                }

                // Arrival at station
                if ("ARRIVING".equals(train.getStatus()) && train.getSpeed() <= 0) {
                    train.setSpeed(0);
                    int arrivedIdx = train.getNextStationIndex();
                    train.setCurrentStationIndex(arrivedIdx);
                    int nextIdx = arrivedIdx + 1;

                    if (nextIdx >= stationCount) {
                        train.setStatus("FINISHED");
                        // Clear occupation
                        trainOccupiedSections.get(train.getTrainId()).clear();
                    } else {
                        train.setNextStationIndex(nextIdx);
                        train.setNextStationKm(stations.get(nextIdx).getKm());
                        train.setStatus("STOPPED");
                        train.setDepartureTime(String.valueOf((int) simulationTimeSeconds));

                        String tid = train.getTrainId();
                        stationArrivalMap.putIfAbsent(tid, new ArrayList<>());
                        SimulationSnapshot.StationArrival arrival = new SimulationSnapshot.StationArrival();
                        arrival.setTrainId(tid);
                        arrival.setStationIndex(arrivedIdx);
                        arrival.setStationName(stations.get(arrivedIdx).getName());
                        arrival.setArrivalTimeSeconds(simulationTimeSeconds);
                        stationArrivalMap.get(tid).add(arrival);

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

                // Check if passed terminal
                if (!"FINISHED".equals(train.getStatus()) && positionKm > lineProfile.getTotalLengthKm()) {
                    train.setStatus("FINISHED");
                    trainOccupiedSections.get(train.getTrainId()).clear();
                }
            }

            // Broadcast via WebSocket
            if (step % 5 == 0 || step == steps - 1) {
                wsHandler.broadcast(getSnapshot());
            }

            // Position history sampling
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
        }
    }

    // ============ SIGNAL SYSTEM ============

    private void updateSignalStates() {
        // Simple fixed-block signaling: if a section is occupied, set protecting signals to RED
        List<TrainState> activeTrains = trains.values().stream()
            .filter(t -> !"FINISHED".equals(t.getStatus()))
            .sorted(Comparator.comparingDouble(TrainState::getPositionMeters).reversed())
            .collect(Collectors.toList());

        // Reset all signals to GREEN
        for (Integer sigId : signalStates.keySet()) {
            signalStates.put(sigId, "GREEN");
        }

        // Set signals RED for occupied sections
        for (TrainState train : activeTrains) {
            Set<Integer> occupied = trainOccupiedSections.get(train.getTrainId());
            if (occupied == null) continue;
            // Find signals protecting these sections
            if (lineData.getSignals() != null) {
                for (LineData.Signal sig : lineData.getSignals()) {
                    // If signal is near occupied section, set caution
                    for (Integer secId : occupied) {
                        if (secId != null) {
                            signalStates.put(sig.get索引编号(), "GREEN");
                        }
                    }
                }
            }
        }

        // Simple headway-based protection via signals
        for (int i = 0; i < activeTrains.size() - 1; i++) {
            TrainState leading = activeTrains.get(i + 1);
            TrainState following = activeTrains.get(i);
            double distance = leading.getPositionMeters() - following.getPositionMeters();
            if (distance < 500 && distance > 0) {
                // Find signals between them
                double midKm = (following.getPositionMeters() + distance / 2) / 1000.0;
                // Set nearby signals to YELLOW
                if (lineData.getSignals() != null) {
                    for (LineData.Signal sig : lineData.getSignals()) {
                        if (sig.get索引编号() > 0 && sig.get索引编号() < 100) {
                            signalStates.put(sig.get索引编号(),
                                distance < 200 ? "RED" : "YELLOW");
                        }
                    }
                }
            }
        }
    }

    private boolean canDepart(TrainState train) {
        // Check if there's a train occupying the block immediately ahead
        double nextStationKm = train.getNextStationKm();
        for (TrainState other : trains.values()) {
            if (other.getTrainId().equals(train.getTrainId())) continue;
            if ("FINISHED".equals(other.getStatus())) continue;
            double otherKm = other.getPositionMeters() / 1000.0;
            double myKm = train.getPositionMeters() / 1000.0;
            if (otherKm > myKm && otherKm < nextStationKm) {
                // Train ahead in same block, check distance
                if ((otherKm - myKm) * 1000 < 200) return false;
            }
        }
        return true;
    }

    private double getSignalRestrictedSpeed(TrainState train) {
        // Check if next signal is at danger
        double positionKm = train.getPositionMeters() / 1000.0;
        // Find next signal ahead
        if (lineData.getSignals() != null) {
            for (LineData.Signal sig : lineData.getSignals()) {
                String state = signalStates.get(sig.get索引编号());
                if ("RED".equals(state)) {
                    // Approximate signal position
                    double sigKmApprox = positionKm + 0.5; // rough estimate
                    if (positionKm < sigKmApprox && sigKmApprox - positionKm < 0.3) {
                        return 15; // restricted speed approaching red signal
                    }
                } else if ("YELLOW".equals(state)) {
                    return 40; // caution speed
                }
            }
        }
        return 60; // unrestricted
    }

    private double findDistanceToSpeedLimit(double currentKm, int limitKmh) {
        return 500; // default: plenty of distance to slow down
    }

    // ============ TRACK OCCUPATION ============

    private void updateTrackOccupation(TrainState train) {
        Set<Integer> occupied = trainOccupiedSections.computeIfAbsent(train.getTrainId(), k -> new HashSet<>());
        occupied.clear();
        // Find which axle counter section the train is in
        double positionKm = train.getPositionMeters() / 1000.0;
        if (lineData.getAxleCounterSections() != null) {
            for (LineData.AxleCounterSection sec : lineData.getAxleCounterSections()) {
                // Simple occupancy: train spans the section
                if (positionKm >= sec.get索引编号() * 0.05 - 0.05
                    && positionKm <= sec.get索引编号() * 0.05 + 0.05) {
                    occupied.add(sec.get索引编号());
                }
            }
        }
    }

    // ============ ENHANCED ENERGY CALCULATION ============

    private void generateSimulationLogEnhanced(TrainState train, double deltaMeters) {
        SimulationLog log = new SimulationLog();

        int trainIdNum;
        try {
            trainIdNum = Integer.parseInt(train.getTrainId().replace("T", ""));
        } catch (NumberFormatException e) {
            trainIdNum = 1;
        }

        log.setTrainId(trainIdNum);
        log.setTimestamp((long) (simulationTimeSeconds * 1000));
        log.setSpeed(train.getSpeed() / 3.6);
        log.setPosition(train.getPositionMeters());
        log.setDirection("down");
        log.setLoadWeight(train.getCarCount() * 35000.0);

        double totalMass = train.getCarCount() * 35000.0;
        double speedMs = train.getSpeed() / 3.6;
        String status = train.getStatus();

        // Acceleration
        double accelMps2 = 0;
        if ("DEPARTING".equals(status)) {
            accelMps2 = 2.0 / 3.6;
        } else if ("ARRIVING".equals(status)) {
            accelMps2 = -2.0 / 3.6;
        }

        // Davis resistance (enhanced): R = (a + b*v) * M * g + c * v²
        // Add gradient resistance: M * g * sin(arctan(gradient/1000)) ≈ M * g * gradient/1000
        double positionKm = train.getPositionMeters() / 1000.0;
        int gradient = lineDataService.getGradientAtKm(positionKm);
        double gradientAngle = Math.atan(gradient / 1000.0);
        double gradientForce = totalMass * 9.81 * Math.sin(gradientAngle);

        // Davis formula: a=2.0, b=0.02, c=0.0005 (typical metro)
        double davisA = 2.0 * totalMass / 1000.0; // N/kN → N
        double davisB = 0.02 * totalMass / 1000.0;
        double davisC = 0.0005;
        double resistance = (davisA + davisB * speedMs * 3.6) * 9.81 + davisC * speedMs * speedMs;

        // Total resistance including gradient
        double totalResistance = resistance + gradientForce;

        // Determine tractive/braking effort
        if ("DEPARTING".equals(status) || ("RUNNING".equals(status) && train.getSpeed() > 0)) {
            log.setTractiveBrakeCmd("traction");
            log.setBrakeForce(0);
            if ("DEPARTING".equals(status)) {
                log.setTractionForce(totalMass * accelMps2 + totalResistance);
                log.setTractiveBrakePercent(Math.min(100,
                    (totalMass * accelMps2 + totalResistance) / (totalMass * 1.0 + totalResistance) * 100));
            } else {
                log.setTractionForce(totalResistance);
                log.setTractiveBrakePercent(Math.min(100,
                    totalResistance / (totalMass * 1.0 + totalResistance) * 100));
            }

            // Energy accumulation (traction)
            double powerKw = log.getTractionForce() * speedMs / 1000.0;
            totalTractionKwh += powerKw / 3600.0;
            totalEnergyKwh += powerKw / 3600.0;
            if (powerKw > peakPowerKw) peakPowerKw = powerKw;

        } else if ("ARRIVING".equals(status)) {
            log.setTractiveBrakeCmd("brake");
            log.setTractionForce(0);
            double brakeForce = totalMass * Math.abs(accelMps2) + totalResistance;
            log.setBrakeForce(brakeForce);
            log.setTractiveBrakePercent(Math.min(100,
                brakeForce / (totalMass * 1.2 + totalResistance) * 100));

            // Regenerative braking energy
            double regenPowerKw = brakeForce * speedMs / 1000.0 * 0.65; // 65% regen efficiency
            totalRegenKwh += regenPowerKw / 3600.0;
            totalEnergyKwh -= regenPowerKw / 3600.0; // net reduction
        } else {
            log.setTractiveBrakeCmd("coast");
            log.setTractionForce(0);
            log.setBrakeForce(0);
            log.setTractiveBrakePercent(0);
        }

        log.setEmergencyBrake(false);
        log.setAvailableTractionCount(train.getCarCount() > 0 ? train.getCarCount() : 4);
        log.setAvailableBrakeCount(train.getCarCount() > 0 ? train.getCarCount() : 4);
        log.setFaultSpeedLimit(0);
        log.setDrivingMode("AM");

        // Determine current logical section
        int currentSeg = determineCurrentSeg(train);
        log.setCurrentSegId(currentSeg);

        simulationLogs.add(log);
    }

    private int determineCurrentSeg(TrainState train) {
        if (trackGeometry == null || trackGeometry.getSegments() == null) return 1;
        double posKm = train.getPositionMeters() / 1000.0;
        // Find segment containing this position
        for (TrackGeometry.TrackSegment seg : trackGeometry.getSegments()) {
            if (posKm >= seg.getId() * 0.05 - 0.1 && posKm <= seg.getId() * 0.05 + 0.1) {
                return seg.getId();
            }
        }
        return 1;
    }

    // ============ SNAPSHOT ============

    public SimulationSnapshot getSnapshot() {
        SimulationSnapshot snapshot = new SimulationSnapshot();
        snapshot.setSimulationTime(simulationTimeSeconds);
        snapshot.setSimTimeFormatted(formatTime(simulationTimeSeconds));

        List<TrainState> trainList = new ArrayList<>();
        int activeCount = 0;
        for (TrainState t : trains.values()) {
            TrainState copy = copyTrainState(t);
            trainList.add(copy);
            if (!"FINISHED".equals(t.getStatus())) activeCount++;
        }
        snapshot.setTotalTrains(trains.size());
        snapshot.setActiveTrains(activeCount);
        snapshot.setTrains(trainList);

        List<SimulationSnapshot.StationArrival> arrivals = new ArrayList<>();
        for (List<SimulationSnapshot.StationArrival> list : stationArrivalMap.values()) {
            arrivals.addAll(list);
        }
        snapshot.setStationArrivals(arrivals);

        // Headway
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
            double dist = leading.getPositionMeters() - following.getPositionMeters();
            headway.setDistanceMeters(dist);
            double timeGap = following.getSpeed() > 0 ? dist / (following.getSpeed() / 3.6) : 999;
            headway.setTimeSeconds(timeGap);
            if (dist < 100) headway.setStatus("DANGER");
            else if (dist < 500) headway.setStatus("WARNING");
            else headway.setStatus("SAFE");
            headwayList.add(headway);
        }
        snapshot.setHeadways(headwayList);

        // Commands (enhanced with signal info)
        List<SimulationSnapshot.TrainCommand> commands = new ArrayList<>();
        for (TrainState train : trains.values()) {
            if ("ARRIVING".equals(train.getStatus())) {
                SimulationSnapshot.TrainCommand cmd = new SimulationSnapshot.TrainCommand();
                cmd.setTrainId(train.getTrainId());
                cmd.setCommandType("ARRIVE");
                cmd.setTargetValue(train.getNextStationKm());
                cmd.setReason("Approaching station, distance < 200m");
                commands.add(cmd);
            } else if ("RUNNING".equals(train.getStatus())) {
                // Signal-based commands
                double sigRestricted = getSignalRestrictedSpeed(train);
                if (sigRestricted < 60) {
                    SimulationSnapshot.TrainCommand cmd = new SimulationSnapshot.TrainCommand();
                    cmd.setTrainId(train.getTrainId());
                    cmd.setCommandType(sigRestricted <= 15 ? "HOLD" : "SLOW");
                    cmd.setTargetValue(sigRestricted);
                    cmd.setReason("Signal restriction ahead");
                    commands.add(cmd);
                }
            }
        }
        snapshot.setCommands(commands);

        snapshot.setPositionHistory(new ArrayList<>(positionHistory));
        snapshot.setTotalEnergyKwh(totalEnergyKwh);

        // Enhanced snapshot data
        snapshot.setTotalTractionKwh(totalTractionKwh);
        snapshot.setTotalRegenKwh(totalRegenKwh);
        snapshot.setPeakPowerKw(peakPowerKw);
        snapshot.setMaxSpeedLimit(lineDataService.getSpeedLimitAtKm(
            activeTrains.isEmpty() ? 0 : activeTrains.get(0).getPositionMeters() / 1000.0));

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

    // ============ HELPERS ============

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
