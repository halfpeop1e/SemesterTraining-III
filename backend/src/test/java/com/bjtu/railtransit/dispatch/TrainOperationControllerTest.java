package com.bjtu.railtransit.dispatch;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.bjtu.railtransit.domain.model.StatusReport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@AutoConfigureMockMvc
class TrainOperationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SimulationService simulationService;

    @BeforeEach
    @AfterEach
    void clearTrains() {
        simulationService.clearTrains();
    }

    @Test
    void addChangePatternAndRemoveTrain() throws Exception {
        mockMvc.perform(post("/api/dispatch/trains")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"trainId":"T99","headLinkId":17,"direction":"UP",
                                 "stationId":1,"destinationStationId":3,"routePattern":"FULL"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.trainId").value("T99"))
                .andExpect(jsonPath("$.data.headLinkId").value(17))
                .andExpect(jsonPath("$.data.routeId").value("L9-FULL"))
                .andExpect(jsonPath("$.data.currentStationIndex").value(0))
                .andExpect(jsonPath("$.data.originStationId").value(1))
                .andExpect(jsonPath("$.data.destinationStationId").value(3))
                .andExpect(jsonPath("$.data.routePattern").value("FULL"));

        mockMvc.perform(post("/api/dispatch/trains/T99/route-pattern")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"routePattern\":\"SHORT_S\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.routePattern").value("SHORT_S"));

        mockMvc.perform(delete("/api/dispatch/trains/T99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.trainId").value("T99"));
    }

    @Test
    void duplicateIsRejectedAndClearReturnsRemovedCount() throws Exception {
        String body = """
                {"trainId":"T1","headLinkId":1,"direction":"DOWN",
                 "stationId":13,"routePattern":"EXPRESS"}
                """;
        mockMvc.perform(post("/api/dispatch/trains")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.success").value(true));
        mockMvc.perform(post("/api/dispatch/trains")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.success").value(false));

        mockMvc.perform(delete("/api/dispatch/trains"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.removed").value(1));
    }

    @Test
    void localSignalTrainsKeepIndependentDirectionsAndDestinations() throws Exception {
        mockMvc.perform(post("/api/dispatch/trains")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"trainId":"LOCAL_UP","headLinkId":1,"direction":"UP",
                                 "stationId":1,"destinationStationId":3,"routePattern":"FULL"}
                                """))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.originStationId").value(1))
                .andExpect(jsonPath("$.data.destinationStationId").value(3));

        mockMvc.perform(post("/api/dispatch/trains")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"trainId":"LOCAL_DOWN","headLinkId":1,"direction":"DOWN",
                                 "stationId":13,"destinationStationId":11,"routePattern":"FULL"}
                                """))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.originStationId").value(13))
                .andExpect(jsonPath("$.data.destinationStationId").value(11));

        mockMvc.perform(get("/api/simulations/snapshot"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.trains.length()").value(2))
                .andExpect(jsonPath("$.data.trains[0].trainId").value("LOCAL_UP"))
                .andExpect(jsonPath("$.data.trains[1].trainId").value("LOCAL_DOWN"));

        mockMvc.perform(delete("/api/dispatch/trains/LOCAL_UP"))
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(get("/api/simulations/snapshot"))
                .andExpect(jsonPath("$.data.trains.length()").value(1))
                .andExpect(jsonPath("$.data.trains[0].trainId").value("LOCAL_DOWN"));
    }

    @Test
    void deletedTrainDoesNotReappearFromStaleOnboardStatus() throws Exception {
        mockMvc.perform(post("/api/dispatch/trains")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"trainId\":\"T99\",\"headLinkId\":17,\"direction\":\"UP\",\"stationId\":1}"))
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(delete("/api/dispatch/trains/T99"))
                .andExpect(jsonPath("$.success").value(true));

        StatusReport staleReport = new StatusReport();
        staleReport.setTrainId("T99");
        staleReport.setDirection("UP");
        staleReport.setPositionMeters(500.0);
        staleReport.setSpeedKmh(30.0);
        simulationService.acceptOnboardReport(staleReport);

        org.junit.jupiter.api.Assertions.assertNull(simulationService.findTrain("T99"));

        simulationService.restoreTrain("T99");
        simulationService.acceptOnboardReport(staleReport);
        org.junit.jupiter.api.Assertions.assertNotNull(simulationService.findTrain("T99"));
    }

    @Test
    void authoritativeDriverDeskReportTakesOverAControlCenterTrain() {
        simulationService.addTrain("LAB1", 1, "UP", 1, "FULL");
        StatusReport report = new StatusReport();
        report.setTrainId("LAB1");
        report.setDirection("UP");
        report.setPositionMeters(500.0);
        report.setSpeedKmh(18.0);
        report.setAccelerationMps2(0.2);
        report.setCurrentStationId("1");
        report.setNextStationId("2");
        report.setPhase("ACCELERATING");
        report.setAuthoritative(true);

        simulationService.acceptOnboardReport(report);

        var train = simulationService.findTrain("LAB1");
        assertEquals("ONBOARD_REPORTED", train.getStateSource());
        assertEquals(500.0, train.getPositionMeters(), 0.001);
        assertEquals(18.0, train.getSpeed(), 0.001);
    }

    @Test
    void driverDeskControlOwnershipPreventsAtsFromGeneratingDepartureCommands() {
        simulationService.addTrain("LAB_DESK", 1, "UP", 1, "FULL");
        simulationService.claimDriverDeskControl("LAB_DESK");

        simulationService.startSimulation();
        simulationService.stepSimulation(1);

        var train = simulationService.findTrain("LAB_DESK");
        assertEquals("READY_TO_DEPART", train.getStatus());
        org.junit.jupiter.api.Assertions.assertNotEquals("DEPART", train.getActiveCommand());
        org.junit.jupiter.api.Assertions.assertTrue(simulationService.getSnapshot().getIntegrationCommands().stream()
                .noneMatch(command -> "LAB_DESK".equals(command.getTrainId())
                        && "DEPART".equals(command.getCommandType())));
    }

    @Test
    void dispatchEndpointRejectsDepartForDriverDeskControlledTrain() throws Exception {
        simulationService.addTrain("LAB_DESK_API", 1, "UP", 1, "FULL");
        simulationService.claimDriverDeskControl("LAB_DESK_API");

        mockMvc.perform(post("/api/dispatch/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"trainId\":\"LAB_DESK_API\",\"commandType\":\"DEPART\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false));
    }
}
