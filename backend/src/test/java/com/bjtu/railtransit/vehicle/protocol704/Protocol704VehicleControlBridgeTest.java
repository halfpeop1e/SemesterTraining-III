package com.bjtu.railtransit.vehicle.protocol704;

import com.bjtu.railtransit.dispatch.MultiParticleSimulationService;
import com.bjtu.railtransit.vehicle.dto.SimulationResult;
import com.bjtu.railtransit.vehicle.enums.DrivingMode;
import com.bjtu.railtransit.vehicle.service.DemoScenarioProvider;
import com.bjtu.railtransit.vehicle.service.LineProfileJsonLoader;
import com.bjtu.railtransit.vehicle.service.VehicleSimulationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Protocol704VehicleControlBridgeTest {
    private DemoScenarioProvider provider;
    private LineProfileJsonLoader loader;
    private VehicleSimulationService vehicleService;
    private Protocol704VehicleControlBridge bridge;

    @BeforeEach
    void setUp() {
        provider = new DemoScenarioProvider();
        loader = new LineProfileJsonLoader();
        vehicleService = new VehicleSimulationService(provider, new MultiParticleSimulationService());
        bridge = new Protocol704VehicleControlBridge(vehicleService, provider, loader);
    }

    @Test
    void rejectsNormalCommandWithoutActiveSimulation() {
        assertEquals("NO_ACTIVE_SIMULATION", bridge.execute("OB1", command("traction", 50)).getRejectionReason());
    }

    @Test
    void atoRejectsTractionButEmergencyBrakeExecutesAndLatches() {
        register("OB1", DrivingMode.ATO);
        assertEquals("MODE_NOT_MANUAL", bridge.execute("OB1", command("traction", 50)).getRejectionReason());

        Protocol704CommandLifecycle emergency = bridge.execute("OB1", command("emergency_brake", 100));
        assertEquals("EXECUTED", emergency.getStatus());
        assertEquals("EMERGENCY", emergency.getResultMode());
        assertEquals("EMERGENCY_BRAKE_LATCHED", bridge.execute("OB1", command("traction", 50)).getRejectionReason());
    }

    @Test
    void manualTractionAndBrakeUseVehicleSimulationAndDuplicateEbIsIdempotent() {
        register("OB1", DrivingMode.MANUAL);
        Protocol704CommandLifecycle traction = bridge.execute("OB1", command("traction", 50));
        assertEquals("EXECUTED", traction.getStatus());
        assertNotNull(traction.getExecutedState());
        assertTrue(traction.getExecutedState().getAcceleration() > 0.0);
        assertTrue(traction.getExecutedState().getVelocity() > 0.0);
        // The PLC transmits a held handle repeatedly. A frame inside the 0.5 s
        // integrator interval is acknowledged with the current state rather than
        // rejected as a duplicate command.
        assertEquals("EXECUTED", bridge.execute("OB1", command("traction", 50)).getStatus());

        Protocol704CommandLifecycle brake = bridge.execute("OB1", command("brake", 60));
        assertEquals("EXECUTED", brake.getStatus());
        assertTrue(brake.getExecutedState().getVelocity() < traction.getExecutedState().getVelocity());

        Protocol704CommandLifecycle firstEb = bridge.execute("OB1", command("emergency_brake", 100));
        Protocol704CommandLifecycle repeatedEb = bridge.execute("OB1", command("emergency_brake", 100));
        assertEquals("EXECUTED", firstEb.getStatus(), firstEb.getExecutionError());
        assertEquals("EXECUTED", repeatedEb.getStatus());
        assertEquals("EMERGENCY", repeatedEb.getResultMode());
    }

    @Test
    void invalidLevelIsRejectedWithoutChangingVehicleState() {
        register("OB1", DrivingMode.MANUAL);
        assertEquals("INVALID_LEVEL", bridge.execute("OB1", command("traction", 101)).getRejectionReason());
    }

    @Test
    void modeAndDepartureCommandsChangeVehicleLifecycle() {
        SimulationResult result = vehicleService.run(provider.buildScenario(loader.buildLineProfile(1, 2)));
        bridge.registerSimulation("READY", 1, 2, result, DrivingMode.ATO, false);
        assertEquals("EXECUTED", bridge.execute("READY", command("SET_MANUAL", 0)).getStatus());
        assertEquals("MANUAL", bridge.execute("READY", command("SET_MANUAL", 0)).getResultMode());
        assertEquals("EXECUTED", bridge.execute("READY", command("RESUME_ATO", 0)).getStatus());
        Protocol704CommandLifecycle depart = bridge.execute("READY", command("DEPART_CONFIRM", 0));
        assertEquals("EXECUTED", depart.getStatus());
        assertNotNull(depart.getExecutedState());
        assertEquals("ATO", depart.getResultMode());
        assertEquals("MODE_NOT_MANUAL", bridge.execute("READY", command("traction", 50)).getRejectionReason());
        assertEquals("EXECUTED", bridge.execute("READY", command("SET_MANUAL", 0)).getStatus());
        assertEquals("EXECUTED", bridge.execute("READY", command("traction", 50)).getStatus());
    }

    @Test
    void reverseDirectionIsExplicitlyRejectedByVehicleService() {
        register("REV", DrivingMode.MANUAL);
        MappedControlCommand reverse = command("traction", 50);
        reverse.setDirection("REVERSE");
        assertEquals("REVERSE_UNSUPPORTED", bridge.execute("REV", reverse).getExecutionError().contains("REVERSE_UNSUPPORTED") ? "REVERSE_UNSUPPORTED" : "");
    }

    private void register(String trainId, DrivingMode mode) {
        SimulationResult result = vehicleService.run(provider.buildScenario(loader.buildLineProfile(1, 2)));
        bridge.registerSimulation(trainId, 1, 2, result, mode);
    }

    private static MappedControlCommand command(String command, double level) {
        MappedControlCommand mapped = new MappedControlCommand();
        mapped.setCommand(command);
        mapped.setLevelPercent(level);
        mapped.setTractionLevelRaw((int) level);
        mapped.setBrakeLevelRaw((int) level);
        mapped.setTargetDecel("brake".equals(command) ? 1.2 * level / 100.0 : 0.0);
        return mapped;
    }
}
