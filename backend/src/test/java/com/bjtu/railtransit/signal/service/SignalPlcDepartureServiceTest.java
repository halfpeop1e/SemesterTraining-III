package com.bjtu.railtransit.signal.service;

import com.bjtu.railtransit.dispatch.CommandBus;
import com.bjtu.railtransit.dispatch.MultiParticleSimulationService;
import com.bjtu.railtransit.signal.domain.MovingAuthority;
import com.bjtu.railtransit.signal.domain.SignalEvent;
import com.bjtu.railtransit.vehicle.dto.SimulationResult;
import com.bjtu.railtransit.vehicle.enums.DrivingMode;
import com.bjtu.railtransit.vehicle.protocol704.MappedControlCommand;
import com.bjtu.railtransit.vehicle.protocol704.Protocol704VehicleControlBridge;
import com.bjtu.railtransit.vehicle.service.DemoScenarioProvider;
import com.bjtu.railtransit.vehicle.service.LineProfileJsonLoader;
import com.bjtu.railtransit.vehicle.service.VehicleSimulationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SignalPlcDepartureServiceTest {

    private CommandBus commandBus;
    private MovementAuthorityRegistry maRegistry;
    private Protocol704VehicleControlBridge bridge;
    private SignalPlcDepartureService service;
    private VehicleSimulationService vehicleService;
    private DemoScenarioProvider provider;
    private LineProfileJsonLoader loader;

    @BeforeEach
    void setUp() {
        commandBus = new CommandBus();
        maRegistry = new MovementAuthorityRegistry();
        provider = new DemoScenarioProvider();
        loader = new LineProfileJsonLoader();
        vehicleService = new VehicleSimulationService(provider, new MultiParticleSimulationService());
        bridge = new Protocol704VehicleControlBridge(vehicleService, provider, loader);
        service = new SignalPlcDepartureService(commandBus, maRegistry, bridge);
    }

    @Test
    void atoReadyWhenDepartAuthorizedDoorsClosedAndMaValid() {
        register("T1", DrivingMode.MANUAL);
        bridge.syncDepartureAuth("T1", true);
        putMa("T1", 500);

        assertTrue(service.isAtoReady("T1"));
    }

    @Test
    void atoReadyWhenOpenDepartCommandExists() {
        register("T1", DrivingMode.MANUAL);
        commandBus.issue("T1", "DEPART", 0, "ATS", 100, "ATS", 10);
        putMa("T1", 500);

        assertTrue(service.isAtoReady("T1"));
    }

    @Test
    void notAtoReadyWhenDoorsOpen() {
        register("T1", DrivingMode.MANUAL);
        bridge.syncDepartureAuth("T1", true);
        putMa("T1", 500);
        bridge.updateCabInputs("T1", Map.of("door_open_right", true));

        assertFalse(service.isAtoReady("T1"));
    }

    @Test
    void notAtoReadyWhenAlreadyInAtoMode() {
        register("T1", DrivingMode.ATO);
        putMa("T1", 500);

        assertFalse(service.isAtoReady("T1"));
    }

    @Test
    void onDepartureAuthorizedSyncsBridge() {
        register("T1", DrivingMode.MANUAL);

        service.onDepartureAuthorized("T1");

        assertTrue(bridge.isDepartureAuthorized("T1"));
    }

    private void register(String trainId, DrivingMode mode) {
        SimulationResult result = vehicleService.run(provider.buildScenario(loader.buildLineProfile(1, 2)));
        bridge.registerSimulation(trainId, 1, 2, result, mode, false, false, true);
    }

    private void putMa(String trainId, double endM) {
        MovingAuthority ma = new MovingAuthority();
        ma.setTrainId(trainId);
        ma.setEndOfAuthorityM(endM);
        ma.setMaxSpeedKmh(65);
        ma.setEvent(SignalEvent.NONE);
        Map<String, MovingAuthority> values = new LinkedHashMap<>();
        values.put(trainId, ma);
        maRegistry.replace(values, 1.0, "TEST");
    }
}
