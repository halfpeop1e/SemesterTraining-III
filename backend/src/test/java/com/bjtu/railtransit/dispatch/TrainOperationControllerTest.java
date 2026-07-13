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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
                                 "stationId":1,"routePattern":"FULL"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.trainId").value("T99"))
                .andExpect(jsonPath("$.data.headLinkId").value(17))
                .andExpect(jsonPath("$.data.routeId").value("L9-FULL"))
                .andExpect(jsonPath("$.data.currentStationIndex").value(0))
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
}
