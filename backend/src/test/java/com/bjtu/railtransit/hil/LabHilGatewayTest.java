package com.bjtu.railtransit.hil;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LabHilGatewayTest {
    @Test
    void allowsOnlyProfilesWithAnAuthoritativeOrderTable() {
        assertTrue(LabHilGateway.isSupportedVisionProfile("documented-128"));
        assertTrue(LabHilGateway.isSupportedVisionProfile("DOCUMENTED-128"));
        assertFalse(LabHilGateway.isSupportedVisionProfile("unknown"));
        assertFalse(LabHilGateway.isSupportedVisionProfile(null));
    }
}
