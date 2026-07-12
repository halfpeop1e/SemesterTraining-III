package com.bjtu.railtransit.vehicle.protocol704;

import com.bjtu.railtransit.vehicle.dto.ControlCommand;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Protocol704LocalV1ControlAdapterTest {

    @Test
    void mapsKnownBrakeWithoutClaimingProtocolVerification() {
        MappedControlCommand mapped = mapped("brake", 60.0, 0.72);

        Optional<ControlCommand> adapted = Protocol704LocalV1ControlAdapter.toLocalV1(mapped);

        assertTrue(adapted.isPresent());
        assertEquals("brake", adapted.get().getCommand());
        assertEquals(60.0, adapted.get().getLevelPercent());
        assertEquals(0.72, adapted.get().getTargetDecel());
        assertFalse(mapped.isVerified());
    }

    @Test
    void rejectsSkipAndUnknownCommandsWithoutInference() {
        assertTrue(Protocol704LocalV1ControlAdapter.toLocalV1(mapped("skip", 0.0, 0.0)).isEmpty());
        assertTrue(Protocol704LocalV1ControlAdapter.toLocalV1(mapped("skip_station", 0.0, 0.0)).isEmpty());
        assertTrue(Protocol704LocalV1ControlAdapter.toLocalV1(mapped("unknown", 0.0, 0.0)).isEmpty());
        assertTrue(Protocol704LocalV1ControlAdapter.toLocalV1(null).isEmpty());
    }

    @Test
    void mapsModeDepartureAndDirectionCommands() {
        assertEquals("SET_MANUAL", Protocol704LocalV1ControlAdapter.toLocalV1(mapped("SET_MANUAL", 0, 0)).orElseThrow().getCommand());
        assertEquals("RESUME_ATO", Protocol704LocalV1ControlAdapter.toLocalV1(mapped("RESUME_ATO", 0, 0)).orElseThrow().getCommand());
        assertEquals("DEPART_CONFIRM", Protocol704LocalV1ControlAdapter.toLocalV1(mapped("DEPART_CONFIRM", 0, 0)).orElseThrow().getCommand());
        MappedControlCommand forward = mapped("traction", 50, 0);
        forward.setDirection("FORWARD");
        assertEquals("FORWARD", Protocol704LocalV1ControlAdapter.toLocalV1(forward).orElseThrow().getDirection());
        MappedControlCommand reverse = mapped("traction", 50, 0);
        reverse.setDirection("UNKNOWN");
        assertTrue(Protocol704LocalV1ControlAdapter.toLocalV1(reverse).isEmpty());
    }

    @Disabled("""
            R-07 blocked. Missing contract/data: verified PLC skip encoding, stream framing/reassembly,
            sequence/replay rules, session rollover, ACK correlation, active simulation identity, and
            dispatch/onboard skip lifecycle. Provider: 704/PLC owner plus central dispatch/onboard owner.
            Enable only after a versioned written contract has production producer and consumer support.
            Expected behavior: a verified ordered in-session acknowledged future nonterminal skip applies once,
            completes after mainline passage, and does not open doors, exchange passengers, or enter a siding.
            """)
    @Test
    void r07_skipCommandLifecycleIsVerifiedEndToEnd() {
        throw new AssertionError("Enable only after the external skip execution contract is implemented");
    }

    @Disabled("""
            R-07 blocked. Missing contract/data: command ordering, duplicate suppression, old-frame rejection,
            session rollover semantics, terminal policy, and route-progress authority. Provider: central
            dispatch/onboard owner and 704/PLC owner. Enable only after their versioned contract is implemented.
            Expected behavior: duplicate, old, out-of-order, rollover, invalid, continuous, and terminal skip
            commands cannot cause repeated stopping, passenger exchange, siding entry, or terminal bypass.
            """)
    @Test
    void r07_skipSafetyCasesAreValidatedByTheIntegratedOwner() {
        throw new AssertionError("Enable only after the external skip execution contract is implemented");
    }

    private static MappedControlCommand mapped(String command, double levelPercent, double targetDecel) {
        MappedControlCommand mapped = new MappedControlCommand();
        mapped.setCommand(command);
        mapped.setLevelPercent(levelPercent);
        mapped.setTargetDecel(targetDecel);
        mapped.setVerified(false);
        return mapped;
    }
}
