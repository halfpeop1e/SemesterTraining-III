package com.bjtu.railtransit.hil;

import com.bjtu.railtransit.dispatch.CommandBus;
import com.bjtu.railtransit.dispatch.LineDataService;
import com.bjtu.railtransit.dispatch.MultiParticleSimulationService;
import com.bjtu.railtransit.energy.TractionPowerSupplyService;
import com.bjtu.railtransit.signal.domain.MovingAuthority;
import com.bjtu.railtransit.signal.domain.SignalEvent;
import com.bjtu.railtransit.signal.service.LineProfileLoader;
import com.bjtu.railtransit.signal.service.MovementAuthorityRegistry;
import com.bjtu.railtransit.signal.service.SignalPlcDepartureService;
import com.bjtu.railtransit.vehicle.dto.SimulationResult;
import com.bjtu.railtransit.vehicle.enums.DrivingMode;
import com.bjtu.railtransit.vehicle.protocol704.MappedControlCommand;
import com.bjtu.railtransit.vehicle.protocol704.Protocol704VehicleControlBridge;
import com.bjtu.railtransit.vehicle.service.DemoScenarioProvider;
import com.bjtu.railtransit.vehicle.service.LineProfileJsonLoader;
import com.bjtu.railtransit.vehicle.service.VehicleSimulationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HilSnapshotProviderTest {
    private Protocol704VehicleControlBridge bridge;
    private MovementAuthorityRegistry registry;
    private SignalPlcDepartureService departure;
    private HilSnapshotProvider snapshots;

    @BeforeEach
    void setUp() {
        DemoScenarioProvider scenarios = new DemoScenarioProvider();
        LineProfileJsonLoader vehicleLine = new LineProfileJsonLoader();
        VehicleSimulationService vehicle = new VehicleSimulationService(
                scenarios, new MultiParticleSimulationService());
        bridge = new Protocol704VehicleControlBridge(vehicle, scenarios, vehicleLine);
        registry = new MovementAuthorityRegistry();
        departure = new SignalPlcDepartureService(new CommandBus(), registry, bridge);
        snapshots = new HilSnapshotProvider(bridge, registry, new LineProfileLoader(),
                new TractionPowerSupplyService(new LineDataService()), departure);

        SimulationResult result = vehicle.run(scenarios.buildScenario(vehicleLine.buildLineProfile(1, 2)));
        bridge.registerSimulation("T1", 1, 2, result, DrivingMode.MANUAL, false, false);
        bridge.updateCabInputs("T1", Map.of(
                "key_switch_on", true, "direction_handle", 0, "master_handle", 0));
        bridge.updateCabInputs("T1", Map.of(
                "key_switch_on", false, "direction_handle", 0, "master_handle", 0));
        bridge.updateCabInputs("T1", Map.of(
                "key_switch_on", true, "direction_handle", 1, "master_handle", 0));
        bridge.syncDepartureAuth("T1", true);
        putMa(1_660.520);
    }

    @Test
    void allLaboratoryEncodersConsumeTheSameAtoReadySnapshot() {
        HilVehicleSnapshot state = snapshots.snapshot("T1");

        assertTrue(state.atoAvailable());
        assertFalse(state.atoActive());
        assertEquals(313.0, state.positionM(), 0.001);
        assertEquals(65.0, state.speedLimitKmh(), 0.001);

        byte[] plc = TeacherDeviceFrameCodec.plcOutput(state,
                TeacherDeviceFrameCodec.PlcOutputFrameFormat.CAPTURE_VARIANT_28);
        byte[] mmi = TeacherDeviceFrameCodec.signalScreen(state);
        byte[] hmi = TeacherDeviceFrameCodec.networkScreen(state);

        assertEquals(28, plc.length);
        assertEquals(28, Short.toUnsignedInt(le(plc).getShort(4)));
        assertEquals(4, Short.toUnsignedInt(le(plc).getShort(6)));
        assertEquals(1, plc[25] & 0x01, "ATO-ready lamp is sourced from the MA interlock");
        assertEquals(0, (plc[25] >>> 2) & 1, "ATO-active stays off until the physical start is accepted");
        assertEquals(0.0, le(mmi).getFloat(44), 0.001);
        assertEquals(0.0, le(hmi).getFloat(40), 0.001);
    }

    @Test
    void acceptedAtoStartAppearsAsActiveInTheSharedHilSnapshot() {
        MappedControlCommand start = new MappedControlCommand();
        start.setCommand("ATO_START");
        start.setDirection("FORWARD");
        start.setMasterHandle(0);
        assertEquals("EXECUTED", bridge.execute("T1", start).getStatus());

        HilVehicleSnapshot state = snapshots.snapshot("T1");
        byte[] plc = TeacherDeviceFrameCodec.plcOutput(state,
                TeacherDeviceFrameCodec.PlcOutputFrameFormat.CAPTURE_VARIANT_28);

        assertTrue(state.atoActive());
        assertFalse(state.atoAvailable());
        assertEquals(28, plc.length);
        assertEquals(1, (plc[25] >>> 2) & 1);
        assertEquals(0, plc[25] & 0x01);
    }

    private void putMa(double endOfAuthorityM) {
        MovingAuthority ma = new MovingAuthority();
        ma.setTrainId("T1");
        ma.setEndOfAuthorityM(endOfAuthorityM);
        ma.setMaxSpeedKmh(65);
        ma.setEvent(SignalEvent.NONE);
        Map<String, MovingAuthority> authorities = new LinkedHashMap<>();
        authorities.put("T1", ma);
        registry.replace(authorities, 1.0, "TEST");
    }

    private static ByteBuffer le(byte[] value) {
        return ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN);
    }
}
