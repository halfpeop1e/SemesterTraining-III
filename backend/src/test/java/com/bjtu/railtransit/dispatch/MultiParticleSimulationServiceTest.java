package com.bjtu.railtransit.dispatch;

import com.bjtu.railtransit.domain.model.TrainCar;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiParticleSimulationServiceTest {

    @Test
    void initializesEveryCouplerAtItsFreeLength() {
        MultiParticleSimulationService service = new MultiParticleSimulationService();
        List<TrainCar> cars = service.initConsistWithLoad(0.5);
        service.initCarPositions(cars, 0.0, 0.0);

        for (int i = 0; i < cars.size() - 1; i++) {
            TrainCar front = cars.get(i);
            TrainCar rear = cars.get(i + 1);
            double gap = front.getPositionMeters()
                    - (rear.getPositionMeters() + rear.getLengthMeters());
            assertEquals(0.8, gap, 1e-9);
        }
    }

    @Test
    void massWeightedTrainSpeedDoesNotSawtoothDuringConstantTraction() {
        MultiParticleSimulationService service = new MultiParticleSimulationService();
        List<TrainCar> cars = service.initConsistWithLoad(0.5);
        service.initCarPositions(cars, 0.0, 0.0);

        double previous = service.consistSpeedMps(cars);
        for (int i = 0; i < 40; i++) {
            service.stepConsist(cars, 1.2, 0.05, false);
            double current = service.consistSpeedMps(cars);
            assertTrue(current + 1e-9 >= previous,
                    "consist speed fell from " + previous + " to " + current);
            previous = current;
        }
        assertTrue(previous > 0.0);
    }
}
